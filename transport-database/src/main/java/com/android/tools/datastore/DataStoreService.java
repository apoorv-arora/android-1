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
package com.android.tools.datastore;

import static com.android.tools.datastore.DataStoreDatabase.Characteristic.DURABLE;

import com.android.tools.analytics.UsageTracker;
import com.android.tools.datastore.database.DataStoreTable;
import com.android.tools.datastore.database.UnifiedEventsTable;
import com.android.tools.datastore.service.CpuService;
import com.android.tools.datastore.service.EnergyService;
import com.android.tools.datastore.service.EventService;
import com.android.tools.datastore.service.MemoryService;
import com.android.tools.datastore.service.NetworkService;
import com.android.tools.datastore.service.ProfilerService;
import com.android.tools.datastore.service.TransportService;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.profiler.proto.Common;
import com.android.tools.profiler.proto.CpuServiceGrpc;
import com.android.tools.profiler.proto.EnergyServiceGrpc;
import com.android.tools.profiler.proto.EventServiceGrpc;
import com.android.tools.profiler.proto.MemoryServiceGrpc;
import com.android.tools.profiler.proto.NetworkServiceGrpc;
import com.android.tools.profiler.proto.ProfilerServiceGrpc;
import com.android.tools.profiler.proto.Transport;
import com.android.tools.profiler.proto.TransportServiceGrpc;
import com.google.common.annotations.VisibleForTesting;
import com.google.wireless.android.sdk.stats.AndroidProfilerDbStats;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Primary class that initializes the Datastore. This class currently manages connections to perfd and sets up the DataStore service.
 */
public class DataStoreService implements DataStoreTable.DataStoreTableErrorCallback {
  /**
   * DB report timings are set to occur relatively infrequently, as they include a fair amount of
   * data (~100 bytes). Ideally, we would just send a single reporting event, when the user stopped
   * profiling, but in that case, we'd possibly lose some data when a user's application crashed,
   * and we'd almost never count users who leave their IDE open forever. So, instead, we adopt a
   * slow but steady sampling strategy.
   */
  private static final long REPORT_INITIAL_DELAY = TimeUnit.MINUTES.toMillis(15);
  private static final long REPORT_PERIOD = TimeUnit.HOURS.toMillis(1);
  /**
   * Stream 0 is reserved for datastore metadata. Events stored in this stream are generated by the datastore
   * and can be queried via the events pipeline. Example data pushed into this stream are stream connected / disconnected events.
   */
  public static final long DATASTORE_RESERVED_STREAM_ID = -1;

  public static class BackingNamespace {
    public static final BackingNamespace DEFAULT_SHARED_NAMESPACE = new BackingNamespace("default.sql", DURABLE);

    @NotNull public final String myNamespace;
    @NotNull public final DataStoreDatabase.Characteristic myCharacteristic;

    public BackingNamespace(@NotNull String namespace, @NotNull DataStoreDatabase.Characteristic characteristic) {
      myNamespace = namespace;
      myCharacteristic = characteristic;
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(new Object[]{myNamespace, myCharacteristic});
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof BackingNamespace)) {
        return false;
      }

      BackingNamespace other = (BackingNamespace)obj;
      return myNamespace.equals(other.myNamespace) && myCharacteristic == other.myCharacteristic;
    }
  }

  private LogService.Logger getLogger() {
    return myLogService.getLogger(DataStoreService.class.getCanonicalName());
  }

  @NotNull private final LogService myLogService;
  private final String myDatastoreDirectory;
  private final Map<BackingNamespace, DataStoreDatabase> myDatabases = new HashMap<>();
  private final ServerBuilder myServerBuilder;
  private final Server myServer;
  private final List<ServicePassThrough> myServices = new ArrayList<>();
  private final Consumer<Runnable> myFetchExecutor;
  @NotNull
  private Consumer<Throwable> myNoPiiExceptionHandler;
  private TransportService myTransportService;
  private final ServerInterceptor myInterceptor;
  /**
   * Mapping a stream id to its DataStoreClient.
   */
  private final Map<Long, DataStoreClient> myConnectedClients = new HashMap<>();

  private final Timer myReportTimer;

  /**
   * @param fetchExecutor A callback which is given a {@link Runnable} for each datastore service.
   *                      The runnable, when run, begins polling the target service. You probably
   *                      want to run it on a background thread.
   */
  public DataStoreService(@NotNull String serviceName,
                          @NotNull String datastoreDirectory,
                          @NotNull Consumer<Runnable> fetchExecutor,
                          @NotNull LogService logService) {
    this(serviceName, datastoreDirectory, fetchExecutor, logService, null);
  }

  @VisibleForTesting
  public DataStoreService(@NotNull String serviceName,
                          @NotNull String datastoreDirectory,
                          @NotNull Consumer<Runnable> fetchExecutor,
                          @NotNull LogService logService,
                          @Nullable ServerInterceptor interceptor) {
    myLogService = logService;
    myFetchExecutor = fetchExecutor;
    myInterceptor = interceptor;
    myDatastoreDirectory = datastoreDirectory;
    myServerBuilder = InProcessServerBuilder.forName(serviceName).directExecutor();
    // Calling set with null resets the exception handler to the default exception handler.
    // getLogger().error(exception);
    setNoPiiExceptionHandler(null);
    createPollers();
    myServer = myServerBuilder.build();
    try {
      myServer.start();
    }
    catch (IOException ex) {
      getLogger().error(ex.getMessage());
    }

    myReportTimer = new Timer("DataStoreReportTimer");
    myReportTimer.schedule(new ReportTimerTask(), REPORT_INITIAL_DELAY, REPORT_PERIOD);
    DataStoreTable.addDataStoreErrorCallback(this);
  }

  /**
   * @param noPiiExceptionHandler Consumer of the throwable error to report. Otherwise null to reset the exception handler back to default.
   */
  public void setNoPiiExceptionHandler(@Nullable Consumer<Throwable> noPiiExceptionHandler) {
    if (noPiiExceptionHandler == null) {
      myNoPiiExceptionHandler = (t) -> getLogger().error(t);
    } else {
      myNoPiiExceptionHandler = noPiiExceptionHandler;
    }
  }

  @VisibleForTesting
  public Map<BackingNamespace, DataStoreDatabase> getDatabases() {
    return myDatabases;
  }

  /**
   * Entry point for the datastore pollers and passthrough services are created,
   * and registered as the set of features the datastore supports.
   */
  public void createPollers() {
    // TODO b/73538507 shared between all services to support inserting file content into generic byte cache (e.g. importing hprof)
    // We should be able to keep this inside TransportService after legacy pipeline removal.
    UnifiedEventsTable unifiedTable = new UnifiedEventsTable();
    myTransportService = new TransportService(this, unifiedTable, myFetchExecutor);
    registerService(myTransportService);
    registerService(new ProfilerService(this, myLogService));
    registerService(new EventService(this, myFetchExecutor));
    registerService(new CpuService(this, myFetchExecutor, myLogService));
    registerService(new MemoryService(this, unifiedTable, myFetchExecutor, myLogService));
    registerService(new NetworkService(this, myFetchExecutor));
    registerService(new EnergyService(this, myFetchExecutor, myLogService));
  }

  @VisibleForTesting
  @NotNull
  public DataStoreDatabase createDatabase(@NotNull String dbPath,
                                          @NotNull DataStoreDatabase.Characteristic characteristic,
                                          Consumer<Throwable> noPiiExceptionHandler) {
    return new DataStoreDatabase(dbPath, characteristic, myLogService, noPiiExceptionHandler);
  }

  /**
   * Register's the service with the DataStore and manages the list of pass through to initialize a connection to the appropriate device.
   *
   * @param service The service to register with the datastore. This service will be setup as a listener for studio to talk to.
   */
  void registerService(@NotNull ServicePassThrough service) {
    myServices.add(service);
    List<BackingNamespace> namespaces = service.getBackingNamespaces();
    namespaces.forEach(namespace -> {
      assert !namespace.myNamespace.isEmpty();
      DataStoreDatabase db = myDatabases.computeIfAbsent(namespace, backingNamespace -> createDatabase(
        myDatastoreDirectory + backingNamespace.myNamespace, backingNamespace.myCharacteristic, myNoPiiExceptionHandler));
      service.setBackingStore(namespace, db.getConnection());
    });

    // Build server and start listening for RPC calls for the registered service
    if (myInterceptor != null) {
      myServerBuilder.addService(ServerInterceptors.intercept(service.bindService(), myInterceptor));
    }
    else {
      myServerBuilder.addService(service.bindService());
    }
  }

  /**
   * Connects the DataStoreService to a channel, associating it with the given Stream. The stream will get an STREAM_CONNECTED event created
   * once the conneciton is estebablished, and callers can query for the stream information via
   * {@link TransportServiceGrpc.TransportServiceBlockingStub#getEventGroups(Transport.GetEventGroupsRequest)}.
   */
  public void connect(@NotNull Common.Stream stream, @NotNull ManagedChannel channel) {
    assert stream.getStreamId() != 0;
    long streamId = stream.getStreamId();
    if (!myConnectedClients.containsKey(streamId)) {
      myConnectedClients.put(streamId, new DataStoreClient(channel));
      myTransportService.connectToChannel(stream, channel);
    }
  }

  /**
   * Disconnect from the specified channel.
   */
  public void disconnect(long streamId) {
    if (myConnectedClients.containsKey(streamId)) {
      DataStoreClient client = myConnectedClients.remove(streamId);
      // Shutdown instead of shutdown now so that the client have a chance to receive all the remaining events that need to be streamed
      // through.
      client.getChannel().shutdown();
      myTransportService.disconnectFromChannel(client.getChannel());
    }
  }

  public void shutdown() {
    myReportTimer.cancel();
    myServer.shutdownNow();
    for (DataStoreClient client : myConnectedClients.values()) {
      client.getChannel().shutdownNow();
    }
    myConnectedClients.clear();
    myDatabases.forEach((name, db) -> db.disconnect());
    DataStoreTable.removeDataStoreErrorCallback(this);
  }

  @VisibleForTesting
  List<ServicePassThrough> getRegisteredServices() {
    return myServices;
  }

  public CpuServiceGrpc.CpuServiceBlockingStub getCpuClient(long streamId) {
    return myConnectedClients.containsKey(streamId) ? myConnectedClients.get(streamId).getCpuClient() : null;
  }

  public EnergyServiceGrpc.EnergyServiceBlockingStub getEnergyClient(long streamId) {
    return myConnectedClients.containsKey(streamId) ? myConnectedClients.get(streamId).getEnergyClient() : null;
  }

  public EventServiceGrpc.EventServiceBlockingStub getEventClient(long streamId) {
    return myConnectedClients.containsKey(streamId) ? myConnectedClients.get(streamId).getEventClient() : null;
  }

  public NetworkServiceGrpc.NetworkServiceBlockingStub getNetworkClient(long streamId) {
    return myConnectedClients.containsKey(streamId) ? myConnectedClients.get(streamId).getNetworkClient() : null;
  }

  public MemoryServiceGrpc.MemoryServiceBlockingStub getMemoryClient(long streamId) {
    return myConnectedClients.containsKey(streamId) ? myConnectedClients.get(streamId).getMemoryClient() : null;
  }

  public ProfilerServiceGrpc.ProfilerServiceBlockingStub getProfilerClient(long streamId) {
    return myConnectedClients.containsKey(streamId) ? myConnectedClients.get(streamId).getProfilerClient() : null;
  }

  public TransportServiceGrpc.TransportServiceBlockingStub getTransportClient(long streamId) {
    return myConnectedClients.containsKey(streamId) ? myConnectedClients.get(streamId).getTransportClient() : null;
  }

  @Override
  public void onDataStoreError(Throwable t) {
    myNoPiiExceptionHandler.accept(t);
  }

  @NotNull
  public LogService getLogService() {
    return myLogService;
  }

  /**
   * This class is used to manage the stub to each service per device.
   */
  private static class DataStoreClient {
    @NotNull private final ManagedChannel myChannel;
    @NotNull private final TransportServiceGrpc.TransportServiceBlockingStub myTransportClient;
    @Nullable private ProfilerServiceGrpc.ProfilerServiceBlockingStub myProfilerClient;
    @Nullable private CpuServiceGrpc.CpuServiceBlockingStub myCpuClient;
    @Nullable private EnergyServiceGrpc.EnergyServiceBlockingStub myEnergyClient;
    @Nullable private EventServiceGrpc.EventServiceBlockingStub myEventClient;
    @Nullable private MemoryServiceGrpc.MemoryServiceBlockingStub myMemoryClient;
    @Nullable private NetworkServiceGrpc.NetworkServiceBlockingStub myNetworkClient;

    public DataStoreClient(@NotNull ManagedChannel channel) {
      myChannel = channel;
      myTransportClient = TransportServiceGrpc.newBlockingStub(channel);
      if (!StudioFlags.PROFILER_UNIFIED_PIPELINE.get()) {
        myProfilerClient = ProfilerServiceGrpc.newBlockingStub(channel);
        myCpuClient = CpuServiceGrpc.newBlockingStub(channel);
        myEnergyClient = EnergyServiceGrpc.newBlockingStub(channel);
        myEventClient = EventServiceGrpc.newBlockingStub(channel);
        myMemoryClient = MemoryServiceGrpc.newBlockingStub(channel);
        myNetworkClient = NetworkServiceGrpc.newBlockingStub(channel);
      }
    }

    @NotNull
    public ManagedChannel getChannel() {
      return myChannel;
    }

    @NotNull
    public TransportServiceGrpc.TransportServiceBlockingStub getTransportClient() {
      return myTransportClient;
    }

    @Nullable
    public ProfilerServiceGrpc.ProfilerServiceBlockingStub getProfilerClient() {
      return myProfilerClient;
    }

    @Nullable
    public CpuServiceGrpc.CpuServiceBlockingStub getCpuClient() {
      return myCpuClient;
    }

    @Nullable
    public EnergyServiceGrpc.EnergyServiceBlockingStub getEnergyClient() {
      return myEnergyClient;
    }

    @Nullable
    public EventServiceGrpc.EventServiceBlockingStub getEventClient() {
      return myEventClient;
    }

    @Nullable
    public MemoryServiceGrpc.MemoryServiceBlockingStub getMemoryClient() {
      return myMemoryClient;
    }

    @Nullable
    public NetworkServiceGrpc.NetworkServiceBlockingStub getNetworkClient() {
      return myNetworkClient;
    }
  }

  private final class ReportTimerTask extends TimerTask {
    private long myStartTime = System.nanoTime();

    @Override
    public void run() {
      AndroidProfilerDbStats.Builder dbStats = AndroidProfilerDbStats.newBuilder();
      // Cast to int. Unlikely we'll ever have more than 2 billion seconds (e.g. ~60 years) here...
      dbStats.setAgeSec((int)TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - myStartTime));
      collectReport(dbStats);

      AndroidStudioEvent.Builder event = AndroidStudioEvent
        .newBuilder()
        .setKind(AndroidStudioEvent.EventKind.ANDROID_PROFILER_DB_STATS)
        .setAndroidProfilerDbStats(dbStats);

      UsageTracker.log(event);
    }

    private void collectReport(AndroidProfilerDbStats.Builder dbStats) {
      try {
        File dbFile = new File(myDatastoreDirectory, BackingNamespace.DEFAULT_SHARED_NAMESPACE.myNamespace);
        dbStats.setTotalDiskMb((int)(dbFile.length() / 1024 / 1024)); // Bytes -> MB

        for (DataStoreDatabase db : myDatabases.values()) {
          try (
            Statement tableStatement = db.getConnection().createStatement();
            ResultSet tableResults = tableStatement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (tableResults.next()) {
              String tableName = tableResults.getString(1);
              try (
                Statement sizeStatement = db.getConnection().createStatement();
                ResultSet sizeResult = sizeStatement.executeQuery(String.format("SELECT COUNT(*) FROM %s", tableName))) {
                int tableSize = sizeResult.getInt(1);
                dbStats.addTablesBuilder().setName(tableName).setNumRecords(tableSize).build();
              }
            }
          }
        }
      }
      catch (SQLException ignored) {
      }
    }
  }
}
