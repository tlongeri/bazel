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
package com.google.devtools.build.lib.concurrent;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;

/**
 * An implementation of MultiThreadPoolsQuiescingExecutor that has 2 ExecutorServices, one with a
 * larger thread pool for IO/Network-bound tasks, and one with a smaller thread pool for CPU-bound
 * tasks.
 *
 * <p>With merged analysis and execution phases, this QueueVisitor is responsible for all 3 phases:
 * loading, analysis and execution. There's an additional 3rd pool for execution tasks. This is done
 * for performance reason: each of these phases has an optimal number of threads for its thread
 * pool.
 */
public final class MultiExecutorQueueVisitor extends AbstractQueueVisitor
    implements MultiThreadPoolsQuiescingExecutor {
  private final ExecutorService regularPoolExecutorService;
  private final ExecutorService cpuHeavyPoolExecutorService;
  @Nullable private final ExecutorService executionPhaseExecutorService;

  private MultiExecutorQueueVisitor(
      ExecutorService regularPoolExecutorService,
      ExecutorService cpuHeavyPoolExecutorService,
      ExecutorService executionPhaseExecutorService,
      boolean failFastOnException,
      ErrorClassifier errorClassifier) {
    super(
        regularPoolExecutorService,
        /*shutdownOnCompletion=*/ true,
        failFastOnException,
        errorClassifier);
    this.regularPoolExecutorService = super.getExecutorService();
    this.cpuHeavyPoolExecutorService = Preconditions.checkNotNull(cpuHeavyPoolExecutorService);
    this.executionPhaseExecutorService = executionPhaseExecutorService;
  }

  public static MultiExecutorQueueVisitor createWithExecutorServices(
      ExecutorService regularPoolExecutorService,
      ExecutorService cpuHeavyPoolExecutorService,
      boolean failFastOnException,
      ErrorClassifier errorClassifier) {
    return createWithExecutorServices(
        regularPoolExecutorService,
        cpuHeavyPoolExecutorService,
        /*executionPhaseExecutorService=*/ null,
        failFastOnException,
        errorClassifier);
  }

  public static MultiExecutorQueueVisitor createWithExecutorServices(
      ExecutorService regularPoolExecutorService,
      ExecutorService cpuHeavyPoolExecutorService,
      ExecutorService executionPhaseExecutorService,
      boolean failFastOnException,
      ErrorClassifier errorClassifier) {
    return new MultiExecutorQueueVisitor(
        regularPoolExecutorService,
        cpuHeavyPoolExecutorService,
        executionPhaseExecutorService,
        failFastOnException,
        errorClassifier);
  }

  @Override
  public void execute(Runnable runnable, ThreadPoolType threadPoolType) {
    super.executeWithExecutorService(runnable, getExecutorServiceByThreadPoolType(threadPoolType));
  }

  @VisibleForTesting
  ExecutorService getExecutorServiceByThreadPoolType(ThreadPoolType threadPoolType) {
    switch (threadPoolType) {
      case REGULAR:
        return regularPoolExecutorService;
      case CPU_HEAVY:
        return cpuHeavyPoolExecutorService;
      case EXECUTION_PHASE:
        Preconditions.checkNotNull(executionPhaseExecutorService);
        return executionPhaseExecutorService;
    }
    throw new IllegalStateException("Invalid ThreadPoolType: " + threadPoolType);
  }

  @Override
  protected void shutdownExecutorService(Throwable catastrophe) {
    if (catastrophe != null) {
      Throwables.throwIfUnchecked(catastrophe);
    }
    internalShutdownExecutorService(regularPoolExecutorService);
    internalShutdownExecutorService(cpuHeavyPoolExecutorService);
    if (executionPhaseExecutorService != null) {
      internalShutdownExecutorService(executionPhaseExecutorService);
    }
  }

  private void internalShutdownExecutorService(ExecutorService executorService) {
    executorService.shutdown();
    while (true) {
      try {
        executorService.awaitTermination(Integer.MAX_VALUE, SECONDS);
        break;
      } catch (InterruptedException e) {
        setInterrupted();
      }
    }
  }
}
