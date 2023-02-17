// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionCacheChecker;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionProgressReceiverAvailableEvent;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.Builder;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyframeErrorProcessor;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.common.options.OptionsProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A {@link Builder} implementation driven by Skyframe.
 */
@VisibleForTesting
public class SkyframeBuilder implements Builder {
  private final ResourceManager resourceManager;
  private final SkyframeExecutor skyframeExecutor;
  private final ModifiedFileSet modifiedOutputFiles;
  private final MetadataProvider fileCache;
  private final ActionInputPrefetcher actionInputPrefetcher;
  private final ActionCacheChecker actionCacheChecker;
  private final BugReporter bugReporter;

  @VisibleForTesting
  public SkyframeBuilder(
      SkyframeExecutor skyframeExecutor,
      ResourceManager resourceManager,
      ActionCacheChecker actionCacheChecker,
      ModifiedFileSet modifiedOutputFiles,
      MetadataProvider fileCache,
      ActionInputPrefetcher actionInputPrefetcher,
      BugReporter bugReporter) {
    this.resourceManager = resourceManager;
    this.skyframeExecutor = skyframeExecutor;
    this.actionCacheChecker = actionCacheChecker;
    this.modifiedOutputFiles = modifiedOutputFiles;
    this.fileCache = fileCache;
    this.actionInputPrefetcher = actionInputPrefetcher;
    this.bugReporter = bugReporter;
  }

  @Override
  public void buildArtifacts(
      Reporter reporter,
      Set<Artifact> artifacts,
      Set<ConfiguredTarget> parallelTests,
      Set<ConfiguredTarget> exclusiveTests,
      Set<ConfiguredTarget> targetsToBuild,
      Set<ConfiguredTarget> targetsToSkip,
      ImmutableSet<AspectKey> aspects,
      Executor executor,
      Set<ConfiguredTargetKey> builtTargets,
      Set<AspectKey> builtAspects,
      OptionsProvider options,
      @Nullable Range<Long> lastExecutionTimeRange,
      TopLevelArtifactContext topLevelArtifactContext,
      boolean trustRemoteArtifacts)
      throws BuildFailedException, AbruptExitException, TestExecException, InterruptedException {
    BuildRequestOptions buildRequestOptions = options.getOptions(BuildRequestOptions.class);
    // TODO(bazel-team): Should use --experimental_fsvc_threads instead of the hardcoded constant
    // but plumbing the flag through is hard.
    int fsvcThreads = buildRequestOptions == null ? 200 : buildRequestOptions.fsvcThreads;
    skyframeExecutor.detectModifiedOutputFiles(
        modifiedOutputFiles, lastExecutionTimeRange, trustRemoteArtifacts, fsvcThreads);
    try (SilentCloseable c = Profiler.instance().profile("configureActionExecutor")) {
      skyframeExecutor.configureActionExecutor(fileCache, actionInputPrefetcher);
    }
    // Note that executionProgressReceiver accesses builtTargets concurrently (after wrapping in a
    // synchronized collection), so unsynchronized access to this variable is unsafe while it runs.
    ExecutionProgressReceiver executionProgressReceiver =
        new ExecutionProgressReceiver(
            Preconditions.checkNotNull(builtTargets),
            Preconditions.checkNotNull(builtAspects),
            countTestActions(exclusiveTests),
            skyframeExecutor.getEventBus());
    skyframeExecutor
        .getEventBus()
        .post(new ExecutionProgressReceiverAvailableEvent(executionProgressReceiver));

    List<DetailedExitCode> detailedExitCodes = new ArrayList<>();
    EvaluationResult<?> result;

    ActionExecutionStatusReporter statusReporter = ActionExecutionStatusReporter.create(
        reporter, skyframeExecutor.getEventBus());

    AtomicBoolean isBuildingExclusiveArtifacts = new AtomicBoolean(false);
    ActionExecutionInactivityWatchdog watchdog =
        new ActionExecutionInactivityWatchdog(
            executionProgressReceiver.createInactivityMonitor(statusReporter),
            executionProgressReceiver.createInactivityReporter(
                statusReporter, isBuildingExclusiveArtifacts),
            options.getOptions(BuildRequestOptions.class).progressReportInterval);

    skyframeExecutor.setActionExecutionProgressReportingObjects(executionProgressReceiver,
        executionProgressReceiver, statusReporter);
    watchdog.start();

    targetsToBuild = Sets.difference(targetsToBuild, targetsToSkip);
    parallelTests = Sets.difference(parallelTests, targetsToSkip);
    exclusiveTests = Sets.difference(exclusiveTests, targetsToSkip);

    try {
      result =
          skyframeExecutor.buildArtifacts(
              reporter,
              resourceManager,
              executor,
              artifacts,
              targetsToBuild,
              aspects,
              parallelTests,
              exclusiveTests,
              options,
              actionCacheChecker,
              executionProgressReceiver,
              topLevelArtifactContext);
      // progressReceiver is finished, so unsynchronized access to builtTargets is now safe.
      DetailedExitCode detailedExitCode =
          SkyframeErrorProcessor.processResult(
              reporter,
              result,
              options.getOptions(KeepGoingOption.class).keepGoing,
              skyframeExecutor.getCyclesReporter(),
              bugReporter);

      if (detailedExitCode != null) {
        detailedExitCodes.add(detailedExitCode);
      }

      // Run exclusive tests: either tagged as "exclusive" or is run in an invocation with
      // --test_output=streamed.
      isBuildingExclusiveArtifacts.set(true);
      for (ConfiguredTarget exclusiveTest : exclusiveTests) {
        // Since only one artifact is being built at a time, we don't worry about an artifact being
        // built and then the build being interrupted.
        result =
            skyframeExecutor.runExclusiveTest(
                reporter,
                resourceManager,
                executor,
                exclusiveTest,
                options,
                actionCacheChecker,
                topLevelArtifactContext);
        detailedExitCode =
            SkyframeErrorProcessor.processResult(
                reporter,
                result,
                options.getOptions(KeepGoingOption.class).keepGoing,
                skyframeExecutor.getCyclesReporter(),
                bugReporter);
        Preconditions.checkState(
            detailedExitCode != null || !result.keyNames().isEmpty(),
            "Build reported as successful but test %s not executed: %s",
            exclusiveTest,
            result);

        if (detailedExitCode != null) {
          detailedExitCodes.add(detailedExitCode);
        }
      }
    } finally {
      watchdog.stop();
      skyframeExecutor.setActionExecutionProgressReportingObjects(null, null, null);
      statusReporter.unregisterFromEventBus();
    }

    if (detailedExitCodes.isEmpty()) {
      return;
    }

    // Use the exit code with the highest priority.
    throw new BuildFailedException(
        null, Collections.max(detailedExitCodes, DetailedExitCodeComparator.INSTANCE));
  }

  ActionCacheChecker getActionCacheChecker() {
    return actionCacheChecker;
  }

  MetadataProvider getFileCache() {
    return fileCache;
  }

  ActionInputPrefetcher getActionInputPrefetcher() {
    return actionInputPrefetcher;
  }

  private static int countTestActions(Iterable<ConfiguredTarget> testTargets) {
    int count = 0;
    for (ConfiguredTarget testTarget : testTargets) {
      count += TestProvider.getTestStatusArtifacts(testTarget).size();
    }
    return count;
  }

}
