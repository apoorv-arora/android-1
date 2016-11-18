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
package com.android.tools.profilers;

import com.android.tools.profiler.proto.CpuProfiler;
import com.google.common.collect.ImmutableMap;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;

import java.awt.*;
import java.util.Map;

public class ProfilerColors {

  public static final Color CPU_USAGE = new JBColor(0x62C88E, 0x62C88E);

  public static final Color CPU_OTHER_USAGE = new JBColor(0xDFE6EE, 0xDFE6EE);

  public static final Color MONITOR_BACKGROUND = JBColor.background();

  public static final Color MONITOR_BORDER = new JBColor(0xC9C9C9, 0xC9C9C9);

  public static final Color NETWORK_CONNECTIONS_COLOR = new JBColor(0x9C928B, 0x9C928B);

  public static final Color NETWORK_RECEIVING_COLOR = new JBColor(0x5983E0, 0x5983E0);

  public static final Color NETWORK_SENDING_COLOR = new JBColor(0xEF9F5C, 0xEF9F5C);

  public static final Color NETWORK_WAITING_COLOR = new JBColor(0xAAAAAA, 0xAAAAAA);

  public static final Color MEMORY_TOTAL = new JBColor(new Color(98, 180, 223), new Color(83, 172, 209));

  public static final Color MEMORY_JAVA = new JBColor(new Color(98, 180, 223), new Color(83, 172, 209));

  public static final Color MEMORY_NATIVE = new JBColor(new Color(73, 170, 208), new Color(47, 140, 177));

  public static final Color MEMORY_CODE = new JBColor(new Color(127, 212, 144), new Color(97, 191, 114));

  public static final Color MEMORY_STACK = MEMORY_CODE; // TODO awaiting final color choice

  public static final Color MEMORY_GRAPHCIS = new JBColor(new Color(251, 207, 140), new Color(232, 187, 105));

  public static final Color MEMORY_OTHERS = new JBColor(new Color(103, 144, 178), new Color(59, 85, 97));

  public static final Color MEMORY_OBJECTS = new JBColor(new Color(89, 121, 138), new Color(65, 85, 93));

  public static final Map<CpuProfiler.GetThreadsResponse.State, Color> THREAD_STATES = ImmutableMap.of(
    CpuProfiler.GetThreadsResponse.State.RUNNING, CPU_USAGE,
    CpuProfiler.GetThreadsResponse.State.SLEEPING, new JBColor(0xEDEFF1, 0xEDEFF1),
    CpuProfiler.GetThreadsResponse.State.DEAD, Gray.TRANSPARENT);

  public static final Color THREAD_HOVER_BACKGROUND = new JBColor(0xEAEFFA, 0xEAEFFA);

  public static final Color THREAD_SELECTED_BACKGROUND = new JBColor(0x3476DC, 0x3476DC);
}
