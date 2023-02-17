// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.skyframe;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.skyframe.WalkableGraph.WalkableGraphFactory;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Includes options and states used by {@link MemoizingEvaluator#evaluate}, {@link
 * BuildDriver#evaluate} and {@link WalkableGraphFactory#prepareAndGet}
 */
public class EvaluationContext {
  private final int numThreads;
  @Nullable private final Supplier<ExecutorService> executorServiceSupplier;
  private final boolean keepGoing;
  private final ExtendedEventHandler eventHandler;
  private final boolean useForkJoinPool;
  private final boolean isExecutionPhase;
  private final int cpuHeavySkyKeysThreadPoolSize;
  private final int executionPhaseThreadPoolSize;

  protected EvaluationContext(
      int numThreads,
      @Nullable Supplier<ExecutorService> executorServiceSupplier,
      boolean keepGoing,
      ExtendedEventHandler eventHandler,
      boolean useForkJoinPool,
      boolean isExecutionPhase,
      int cpuHeavySkyKeysThreadPoolSize,
      int executionPhaseThreadPoolSize) {
    Preconditions.checkArgument(0 < numThreads, "numThreads must be positive");
    this.numThreads = numThreads;
    this.executorServiceSupplier = executorServiceSupplier;
    this.keepGoing = keepGoing;
    this.eventHandler = Preconditions.checkNotNull(eventHandler);
    this.useForkJoinPool = useForkJoinPool;
    this.isExecutionPhase = isExecutionPhase;
    this.cpuHeavySkyKeysThreadPoolSize = cpuHeavySkyKeysThreadPoolSize;
    this.executionPhaseThreadPoolSize = executionPhaseThreadPoolSize;
  }

  public int getParallelism() {
    return numThreads;
  }

  public Optional<Supplier<ExecutorService>> getExecutorServiceSupplier() {
    return Optional.ofNullable(executorServiceSupplier);
  }

  public boolean getKeepGoing() {
    return keepGoing;
  }

  public ExtendedEventHandler getEventHandler() {
    return eventHandler;
  }

  public boolean getUseForkJoinPool() {
    return useForkJoinPool;
  }

  /**
   * Returns the size of the thread pool for CPU-heavy tasks set by
   * --experimental_skyframe_cpu_heavy_skykeys_thread_pool_size.
   *
   * <p>--experimental_skyframe_cpu_heavy_skykeys_thread_pool_size is currently incompatible with
   * the execution phase, and this method will return -1.
   */
  public int getCPUHeavySkyKeysThreadPoolSize() {
    if (isExecutionPhase) {
      return -1;
    }
    return cpuHeavySkyKeysThreadPoolSize;
  }

  /**
   * Returns the size of the thread pool to be used for the execution phase. Only applicable with
   * --experimental_merged_skyframe_analysis_execution.
   */
  public int getExecutionPhaseThreadPoolSize() {
    return executionPhaseThreadPoolSize;
  }

  public boolean isExecutionPhase() {
    return isExecutionPhase;
  }

  public Builder builder() {
    return newBuilder().copyFrom(this);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for {@link EvaluationContext}. */
  public static class Builder {
    protected int numThreads;
    protected Supplier<ExecutorService> executorServiceSupplier;
    protected boolean keepGoing;
    protected ExtendedEventHandler eventHandler;
    protected boolean useForkJoinPool;
    protected int cpuHeavySkyKeysThreadPoolSize;
    protected int executionJobsThreadPoolSize = 0;
    protected boolean isExecutionPhase = false;

    protected Builder() {}

    protected Builder copyFrom(EvaluationContext evaluationContext) {
      this.numThreads = evaluationContext.numThreads;
      this.executorServiceSupplier = evaluationContext.executorServiceSupplier;
      this.keepGoing = evaluationContext.keepGoing;
      this.eventHandler = evaluationContext.eventHandler;
      this.isExecutionPhase = evaluationContext.isExecutionPhase;
      this.useForkJoinPool = evaluationContext.useForkJoinPool;
      this.executionJobsThreadPoolSize = evaluationContext.executionPhaseThreadPoolSize;
      this.cpuHeavySkyKeysThreadPoolSize = evaluationContext.cpuHeavySkyKeysThreadPoolSize;
      return this;
    }

    public Builder setNumThreads(int numThreads) {
      this.numThreads = numThreads;
      return this;
    }

    public Builder setExecutorServiceSupplier(Supplier<ExecutorService> executorServiceSupplier) {
      this.executorServiceSupplier = executorServiceSupplier;
      return this;
    }

    public Builder setKeepGoing(boolean keepGoing) {
      this.keepGoing = keepGoing;
      return this;
    }

    public Builder setEventHandler(ExtendedEventHandler eventHandler) {
      this.eventHandler = eventHandler;
      return this;
    }

    public Builder setUseForkJoinPool(boolean useForkJoinPool) {
      this.useForkJoinPool = useForkJoinPool;
      return this;
    }

    public Builder setCPUHeavySkyKeysThreadPoolSize(int cpuHeavySkyKeysThreadPoolSize) {
      this.cpuHeavySkyKeysThreadPoolSize = cpuHeavySkyKeysThreadPoolSize;
      return this;
    }

    public Builder setExecutionPhaseThreadPoolSize(int executionJobsThreadPoolSize) {
      this.executionJobsThreadPoolSize = executionJobsThreadPoolSize;
      return this;
    }

    public Builder setExecutionPhase() {
      this.isExecutionPhase = true;
      return this;
    }

    public EvaluationContext build() {
      return new EvaluationContext(
          numThreads,
          executorServiceSupplier,
          keepGoing,
          eventHandler,
          useForkJoinPool,
          isExecutionPhase,
          cpuHeavySkyKeysThreadPoolSize,
          executionJobsThreadPoolSize);
    }
  }
}
