// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.runtime.MemoryPressureEvent;

/**
 * Drops unnecessary temporary Skyframe state in response to memory pressure.
 *
 * <p>In doing we effectively limit the contribution of this temporary state to Blaze's high water
 * mark memory usage.
 *
 * <p>This is a massive mitigation for a theoretical memory performance issue with
 * SkyKeyComputeState: If many nodes are dormant, waiting for their deps to be computed, and they
 * all have SkyKeyComputeState instances to be used, and those instances have a large total retained
 * heap, then they are contributing to Blaze's high water mark memory usage. This problem typically
 * occurs in practice when Blaze would already be memory constrained (i.e. Xmx is too small relative
 * to its workload). Thankfully, our mitigation lets us not have to make a tradeoff between (i) not
 * being able to use SkyKeyComputeState to improve performance of Blaze's SkyFunctions and (ii)
 * using SkyKeyComputeState but then GC thrashing and suffering when Blaze is memory constrained.
 * Instead, we get the best of both worlds.
 */
public class SkyframeHighWaterMarkLimiter {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final SkyframeExecutor skyframeExecutor;
  private final int threshold;

  public SkyframeHighWaterMarkLimiter(SkyframeExecutor skyframeExecutor, int threshold) {
    this.skyframeExecutor = skyframeExecutor;
    this.threshold = threshold;
  }

  @Subscribe
  void handle(MemoryPressureEvent event) {
    int actual = (int) ((event.tenuredSpaceUsedBytes() * 100L) / event.tenuredSpaceMaxBytes());
    if (actual >= threshold) {
      logger.atInfo().atMostEvery(10, SECONDS).log(
          "Dropping unnecessary temporary Skyframe state in response to memory pressure. actual=%s"
              + " threshold=%s",
          actual, threshold);
      skyframeExecutor.dropUnnecessaryTemporarySkyframeState();
    }
  }
}
