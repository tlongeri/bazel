// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.exec.SpawnCache;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.common.RemotePathResolver.DefaultRemotePathResolver;
import com.google.devtools.build.lib.remote.common.RemotePathResolver.SiblingRepositoryLayoutResolver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.runtime.BlockWaitingModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.vfs.Path;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/** Provides a remote execution context. */
final class RemoteActionContextProvider {

  private final Executor executor;
  private final CommandEnvironment env;
  @Nullable private final RemoteCache remoteCache;
  @Nullable private final RemoteExecutionClient remoteExecutor;
  @Nullable private final ListeningScheduledExecutorService retryScheduler;
  private final DigestUtil digestUtil;
  @Nullable private final Path logDir;
  private ImmutableSet<ActionInput> filesToDownload = ImmutableSet.of();
  private RemoteExecutionService remoteExecutionService;

  private RemoteActionContextProvider(
      Executor executor,
      CommandEnvironment env,
      @Nullable RemoteCache remoteCache,
      @Nullable RemoteExecutionClient remoteExecutor,
      @Nullable ListeningScheduledExecutorService retryScheduler,
      DigestUtil digestUtil,
      @Nullable Path logDir) {
    this.executor = executor;
    this.env = Preconditions.checkNotNull(env, "env");
    this.remoteCache = remoteCache;
    this.remoteExecutor = remoteExecutor;
    this.retryScheduler = retryScheduler;
    this.digestUtil = digestUtil;
    this.logDir = logDir;
  }

  public static RemoteActionContextProvider createForPlaceholder(
      CommandEnvironment env,
      ListeningScheduledExecutorService retryScheduler,
      DigestUtil digestUtil) {
    return new RemoteActionContextProvider(
        directExecutor(),
        env,
        /*remoteCache=*/ null,
        /*remoteExecutor=*/ null,
        retryScheduler,
        digestUtil,
        /*logDir=*/ null);
  }

  public static RemoteActionContextProvider createForRemoteCaching(
      Executor executor,
      CommandEnvironment env,
      RemoteCache remoteCache,
      ListeningScheduledExecutorService retryScheduler,
      DigestUtil digestUtil) {
    return new RemoteActionContextProvider(
        executor,
        env,
        remoteCache,
        /*remoteExecutor=*/ null,
        retryScheduler,
        digestUtil,
        /*logDir=*/ null);
  }

  public static RemoteActionContextProvider createForRemoteExecution(
      Executor executor,
      CommandEnvironment env,
      RemoteExecutionCache remoteCache,
      RemoteExecutionClient remoteExecutor,
      ListeningScheduledExecutorService retryScheduler,
      DigestUtil digestUtil,
      Path logDir) {
    return new RemoteActionContextProvider(
        executor, env, remoteCache, remoteExecutor, retryScheduler, digestUtil, logDir);
  }

  private RemotePathResolver createRemotePathResolver() {
    Path execRoot = env.getExecRoot();
    BuildLanguageOptions buildLanguageOptions =
        env.getOptions().getOptions(BuildLanguageOptions.class);
    RemotePathResolver remotePathResolver;
    if (buildLanguageOptions != null && buildLanguageOptions.experimentalSiblingRepositoryLayout) {
      RemoteOptions remoteOptions = checkNotNull(env.getOptions().getOptions(RemoteOptions.class));
      remotePathResolver =
          new SiblingRepositoryLayoutResolver(
              execRoot, remoteOptions.incompatibleRemoteOutputPathsRelativeToInputRoot);
    } else {
      remotePathResolver = new DefaultRemotePathResolver(execRoot);
    }
    return remotePathResolver;
  }

  private RemoteExecutionService getRemoteExecutionService() {
    if (remoteExecutionService == null) {
      Path workingDirectory = env.getWorkingDirectory();
      RemoteOptions remoteOptions = checkNotNull(env.getOptions().getOptions(RemoteOptions.class));
      Path captureCorruptedOutputsDir = null;
      if (remoteOptions.remoteCaptureCorruptedOutputs != null
          && !remoteOptions.remoteCaptureCorruptedOutputs.isEmpty()) {
        captureCorruptedOutputsDir =
            workingDirectory.getRelative(remoteOptions.remoteCaptureCorruptedOutputs);
      }

      boolean verboseFailures =
          checkNotNull(env.getOptions().getOptions(ExecutionOptions.class)).verboseFailures;
      remoteExecutionService =
          new RemoteExecutionService(
              executor,
              env.getReporter(),
              verboseFailures,
              env.getExecRoot(),
              createRemotePathResolver(),
              env.getBuildRequestId(),
              env.getCommandId().toString(),
              digestUtil,
              checkNotNull(env.getOptions().getOptions(RemoteOptions.class)),
              remoteCache,
              remoteExecutor,
              filesToDownload,
              captureCorruptedOutputsDir);
      env.getEventBus().register(remoteExecutionService);
    }

    return remoteExecutionService;
  }

  /**
   * Registers a remote spawn strategy if this instance was created with an executor, otherwise does
   * nothing.
   *
   * @param registryBuilder builder with which to register the strategy
   */
  public void registerRemoteSpawnStrategy(SpawnStrategyRegistry.Builder registryBuilder) {
    boolean verboseFailures =
        checkNotNull(env.getOptions().getOptions(ExecutionOptions.class)).verboseFailures;
    RemoteSpawnRunner spawnRunner =
        new RemoteSpawnRunner(
            env.getExecRoot(),
            checkNotNull(env.getOptions().getOptions(RemoteOptions.class)),
            env.getOptions().getOptions(ExecutionOptions.class),
            verboseFailures,
            env.getReporter(),
            retryScheduler,
            logDir,
            getRemoteExecutionService());
    registryBuilder.registerStrategy(
        new RemoteSpawnStrategy(env.getExecRoot(), spawnRunner, verboseFailures), "remote");
  }

  /**
   * Registers a spawn cache action context
   *
   * @param registryBuilder builder with which to register the cache
   */
  public void registerSpawnCache(ModuleActionContextRegistry.Builder registryBuilder) {
    RemoteSpawnCache spawnCache =
        new RemoteSpawnCache(
            env.getExecRoot(),
            checkNotNull(env.getOptions().getOptions(RemoteOptions.class)),
            checkNotNull(env.getOptions().getOptions(ExecutionOptions.class)).verboseFailures,
            getRemoteExecutionService());
    registryBuilder.register(SpawnCache.class, spawnCache, "remote-cache");
  }

  /** Returns the remote cache. */
  RemoteCache getRemoteCache() {
    return remoteCache;
  }

  RemoteExecutionClient getRemoteExecutionClient() {
    return remoteExecutor;
  }

  void setFilesToDownload(ImmutableSet<ActionInput> topLevelOutputs) {
    this.filesToDownload = Preconditions.checkNotNull(topLevelOutputs, "filesToDownload");
  }

  public void afterCommand() {
    if (remoteExecutionService != null) {
      BlockWaitingModule blockWaitingModule =
          checkNotNull(env.getRuntime().getBlazeModule(BlockWaitingModule.class));
      blockWaitingModule.submit(() -> remoteExecutionService.shutdown());
    } else {
      if (remoteCache != null) {
        remoteCache.release();
      }
      if (remoteExecutor != null) {
        remoteExecutor.close();
      }
    }
  }
}
