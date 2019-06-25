/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.datastore.poller;

import com.android.tools.datastore.database.MemoryStatsTable;
import com.android.tools.profiler.proto.Common;
import com.android.tools.profiler.proto.MemoryProfiler.*;
import com.android.tools.profiler.proto.MemoryServiceGrpc;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MemoryDataPoller extends PollRunner {
  private long myDataRequestStartTimestampNs = Long.MIN_VALUE;
  private AllocationsInfo myPendingAllocationSample = null;
  private HeapDumpInfo myPendingHeapDumpSample = null;
  private final MemoryServiceGrpc.MemoryServiceBlockingStub myPollingService;
  private final MemoryStatsTable myMemoryStatsTable;
  private final Common.Session mySession;
  private final Consumer<Runnable> myFetchExecutor;

  public MemoryDataPoller(@NotNull Common.Session session,
                          @NotNull MemoryStatsTable statsTable,
                          @NotNull MemoryServiceGrpc.MemoryServiceBlockingStub pollingService,
                          @NotNull Consumer<Runnable> fetchExecutor) {
    super(POLLING_DELAY_NS);
    mySession = session;
    myMemoryStatsTable = statsTable;
    myPollingService = pollingService;
    myFetchExecutor = fetchExecutor;
  }

  @Override
  public void stop() {
    if (myPendingHeapDumpSample != null) {
      myMemoryStatsTable.insertOrReplaceHeapInfo(
        mySession, myPendingHeapDumpSample.toBuilder().setEndTime(myPendingHeapDumpSample.getStartTime() + 1).setSuccess(false).build());
      myPendingHeapDumpSample = null;
    }

    // O+ live allocation tracking sample is always ongoing for the during of the app, so we don't mark its end time here.
    if (myPendingAllocationSample != null && myPendingAllocationSample.getLegacy()) {
      myMemoryStatsTable.insertOrReplaceAllocationsInfo(
        mySession,
        myPendingAllocationSample.toBuilder().setEndTime(myPendingAllocationSample.getStartTime() + 1)
          .setStatus(AllocationsInfo.Status.FAILURE_UNKNOWN).build());
    }
    myPendingAllocationSample = null;

    super.stop();
  }

  @Override
  public void poll() {
    MemoryRequest.Builder dataRequestBuilder =
      MemoryRequest.newBuilder().setSession(mySession).setStartTime(myDataRequestStartTimestampNs).setEndTime(Long.MAX_VALUE);
    MemoryData response = myPollingService.getData(dataRequestBuilder.build());

    // TODO: A UI request may come in while mid way through the poll, this can cause us to have partial data
    // returned to the UI. This can be solved using transactions in the DB when this class is moved fully over.
    myMemoryStatsTable.insertMemory(mySession, response.getMemSamplesList());
    myMemoryStatsTable.insertAllocStats(mySession, response.getAllocStatsSamplesList());
    myMemoryStatsTable.insertGcStats(mySession, response.getGcStatsSamplesList());

    List<AllocationsInfo> allocDumpsToFetch = new ArrayList<>();
    for (int i = 0; i < response.getAllocationsInfoCount(); i++) {
      if (myPendingAllocationSample != null) {
        assert i == 0;
        AllocationsInfo info = response.getAllocationsInfo(i);
        assert myPendingAllocationSample.getStartTime() == info.getStartTime();
        // Deduping - ignoring identical ongoing tracking samples.
        if (info.getEndTime() == Long.MAX_VALUE) {
          break;
        }

        myMemoryStatsTable.insertOrReplaceAllocationsInfo(mySession, info);
        allocDumpsToFetch.add(info);
        myPendingAllocationSample = null;
      }
      else {
        AllocationsInfo info = response.getAllocationsInfo(i);
        myMemoryStatsTable.insertOrReplaceAllocationsInfo(mySession, info);
        if (info.getEndTime() == Long.MAX_VALUE) {
          // Note - there should be at most one unfinished allocation tracking info at a time. e.g. the final info from the response.
          assert i == response.getAllocationsInfoCount() - 1;
          myPendingAllocationSample = info;
        }
        else {
          allocDumpsToFetch.add(info);
        }
      }
    }

    for (int i = 0; i < response.getHeapDumpInfosCount(); i++) {
      HeapDumpInfo info = response.getHeapDumpInfos(i);
      if (myPendingHeapDumpSample != null) {
        assert i == 0;
        assert myPendingHeapDumpSample.getStartTime() == info.getStartTime();
        if (info.getEndTime() == Long.MAX_VALUE) {
          // Deduping - ignoring identical ongoing heap dump samples.
          break;
        }
        myMemoryStatsTable.insertOrReplaceHeapInfo(mySession, info);
        myPendingHeapDumpSample = null;
      }
      else {
        myMemoryStatsTable.insertOrReplaceHeapInfo(mySession, info);
        if (info.getEndTime() == Long.MAX_VALUE) {
          // Note - there should be at most one unfinished heap dump request at a time. e.g. the final info from the response.
          assert i == response.getHeapDumpInfosCount() - 1;
          myPendingHeapDumpSample = info;
        }
      }
    }

    // O+ allocation tracking fetches data continuously and does not go through the following code path - hence we filter out those samples.
    fetchLegacyAllocData(allocDumpsToFetch.stream().filter(AllocationsInfo::getLegacy).collect(Collectors.toList()));

    if (response.getEndTimestamp() > myDataRequestStartTimestampNs) {
      myDataRequestStartTimestampNs = response.getEndTimestamp();
    }
  }

  private void fetchLegacyAllocData(@NotNull List<AllocationsInfo> dumpsToFetch) {
    if (dumpsToFetch.isEmpty()) {
      return;
    }

    Runnable query = () -> {
      HashSet<Integer> classesToFetch = new HashSet<>();
      HashSet<Integer> stacksToFetch = new HashSet<>();
      HashMap<Long, LegacyAllocationEventsResponse> eventsToSave = new HashMap<>();
      for (AllocationsInfo sample : dumpsToFetch) {
        LegacyAllocationEventsResponse allocEventsResponse = myPollingService.getLegacyAllocationEvents(
          LegacyAllocationEventsRequest.newBuilder().setSession(mySession).setStartTime(sample.getStartTime())
            .setEndTime(sample.getEndTime())
            .build());
        eventsToSave.put(sample.getStartTime(), allocEventsResponse);

        allocEventsResponse.getEventsList().forEach(event -> {
          classesToFetch.add(event.getClassId());
          stacksToFetch.add(event.getStackId());
        });
      }

      // Note: the class/stack information are saved first to the table to avoid the events referencing yet-to-exist data
      // in the tables.
      LegacyAllocationContextsRequest contextRequest =
        LegacyAllocationContextsRequest.newBuilder().setSession(mySession).setSession(mySession).addAllClassIds(classesToFetch)
          .addAllStackIds(stacksToFetch).build();
      LegacyAllocationContextsResponse contextsResponse = myPollingService.getLegacyAllocationContexts(contextRequest);
      myMemoryStatsTable.insertLegacyAllocationContext(mySession, contextsResponse.getClassesList(), contextsResponse.getStacksList());
      eventsToSave
        .forEach((startTime, response) -> myMemoryStatsTable.updateLegacyAllocationEvents(mySession, startTime, response));
    };
    myFetchExecutor.accept(query);
  }
}