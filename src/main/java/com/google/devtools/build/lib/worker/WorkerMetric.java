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
package com.google.devtools.build.lib.worker;

import com.google.auto.value.AutoValue;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics.WorkerStats;
import java.time.Instant;
import javax.annotation.Nullable;

/**
 * Contains data about worker statistics during execution. This class contains data for {@link
 * com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics}
 */
@AutoValue
public abstract class WorkerMetric {

  public abstract WorkerProperties getWorkerProperties();

  @Nullable
  public abstract WorkerStat getWorkerStat();

  public abstract boolean isMeasurable();

  public static WorkerMetric create(
      WorkerProperties workerProperties, @Nullable WorkerStat workerStat, boolean isMeasurable) {
    return new AutoValue_WorkerMetric(workerProperties, workerStat, isMeasurable);
  }

  /** Worker measurement of used memory. */
  @AutoValue
  public abstract static class WorkerStat {
    public abstract int getUsedMemoryInKB();

    public abstract Instant getLastCallTime();

    public abstract Instant getCollectTime();

    public static WorkerStat create(int usedMemoryInKB, Instant lastCallTime, Instant collectTime) {
      return new AutoValue_WorkerMetric_WorkerStat(usedMemoryInKB, lastCallTime, collectTime);
    }
  }

  /** Worker properties */
  @AutoValue
  public abstract static class WorkerProperties {
    public abstract int getWorkerId();

    public abstract long getProcessId();

    public abstract String getMnemonic();

    public abstract boolean isMultiplex();

    public abstract boolean isSandboxed();

    public static WorkerProperties create(
        int workerId, long processId, String mnemonic, boolean isMultiplex, boolean isSandboxed) {
      return new AutoValue_WorkerMetric_WorkerProperties(
          workerId, processId, mnemonic, isMultiplex, isSandboxed);
    }
  }

  public WorkerMetrics toProto() {
    WorkerProperties workerProperties = getWorkerProperties();
    WorkerStat workerStat = getWorkerStat();

    WorkerMetrics.Builder builder =
        WorkerMetrics.newBuilder()
            .setWorkerId(workerProperties.getWorkerId())
            .setProcessId((int) workerProperties.getProcessId())
            .setMnemonic(workerProperties.getMnemonic())
            .setIsSandbox(workerProperties.isSandboxed())
            .setIsMultiplex(workerProperties.isMultiplex())
            .setIsMeasurable(isMeasurable());

    if (workerStat != null) {
      WorkerStats stats =
          WorkerMetrics.WorkerStats.newBuilder()
              .setCollectTimeInMs(workerStat.getCollectTime().toEpochMilli())
              .setWorkerMemoryInKb(workerStat.getUsedMemoryInKB())
              .setLastActionStartTimeInMs(workerStat.getLastCallTime().toEpochMilli())
              .build();
      builder.addWorkerStats(stats);
    }

    return builder.build();
  }

}
