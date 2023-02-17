// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionProgressReceiverAvailableEvent;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseCompleteEvent;
import com.google.devtools.build.lib.skyframe.ConfigurationPhaseStartedEvent;
import com.google.devtools.build.lib.skyframe.LoadingPhaseStartedEvent;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.AnsiTerminalWriter;
import com.google.devtools.build.lib.util.io.PositionAwareAnsiTerminalWriter;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.time.Instant;

/** Tracks the state of Skymeld builds and determines what to display at each state in the UI. */
final class SkymeldUiStateTracker extends UiStateTracker {

  enum BuildStatus {
    // We explicitly define a starting status, which can be used to determine what to display in
    // cases before the build has started.
    BUILD_NOT_STARTED,
    BUILD_STARTED,
    TARGET_PATTERN_PARSING,
    LOADING_COMPLETE,
    CONFIGURATION, // Analysis with configuration.
    // The order of the AnalysisCompleteEvent and ExecutionProgressReceiverAvailableEvent is not
    // certain, this splits the possible paths of the change in BuildStatus into two.
    ANALYSIS_COMPLETE, // After analysis but before execution.
    ANALYSIS_AND_EXECUTION, // During analysis and execution.
    EXECUTION, // Only execution.
    BUILD_COMPLETED;
  }

  @VisibleForTesting BuildStatus buildStatus = BuildStatus.BUILD_NOT_STARTED;

  SkymeldUiStateTracker(Clock clock, int targetWidth) {
    super(clock, targetWidth);
  }

  SkymeldUiStateTracker(Clock clock) {
    super(clock);
  }

  /**
   * Main method that writes the progress of the build.
   *
   * @param rawTerminalWriter used to write to the terminal.
   * @param shortVersion whether to write a short version of the output.
   * @param timestamp null if the UiOptions specifies not to show timestamps.
   * @throws IOException when attempting to write to the terminal writer.
   */
  @Override
  synchronized void writeProgressBar(
      AnsiTerminalWriter rawTerminalWriter, boolean shortVersion, String timestamp)
      throws IOException {
    PositionAwareAnsiTerminalWriter terminalWriter =
        new PositionAwareAnsiTerminalWriter(rawTerminalWriter);
    if (timestamp != null) {
      terminalWriter.append(timestamp);
    }
    switch (buildStatus) {
      case BUILD_NOT_STARTED:
        return;
      case BUILD_STARTED:
        writeBaseProgress("Loading", "", terminalWriter);
        break;
      case TARGET_PATTERN_PARSING:
        writeLoadingAnalysisPhaseProgress("Loading", "", terminalWriter, false);
        break;
      case LOADING_COMPLETE:
      case CONFIGURATION:
        writeLoadingAnalysisPhaseProgress(
            "Analyzing", additionalMessage, terminalWriter, shortVersion);
        break;
      case ANALYSIS_COMPLETE:
        // Currently, regular Blaze does not add additional information in this phase, and this is
        // left empty on purpose to mimic the same behavior.
        break;
      case ANALYSIS_AND_EXECUTION:
        writeLoadingAnalysisPhaseProgress(
            "Analyzing", additionalMessage, terminalWriter, shortVersion);
        terminalWriter.newline();
        writeExecutionProgress(terminalWriter, shortVersion);
        break;
      case EXECUTION:
        writeExecutionProgress(terminalWriter, shortVersion);
        break;
      case BUILD_COMPLETED:
        writeBaseProgress(ok ? "INFO" : "FAILED", additionalMessage, terminalWriter);
        break;
    }

    if (!shortVersion) {
      reportOnDownloads(terminalWriter);
      maybeReportActiveUploadsOrDownloads(terminalWriter);
      maybeReportBepTransports(terminalWriter);
    }
  }

  void writeBaseProgress(
      String status, String message, PositionAwareAnsiTerminalWriter terminalWriter)
      throws IOException {
    if (ok) {
      terminalWriter.okStatus();
    } else {
      terminalWriter.failStatus();
    }
    terminalWriter.append(status + ":").normal().append(" " + message);
  }

  void writeLoadingAnalysisPhaseProgress(
      String status,
      String message,
      PositionAwareAnsiTerminalWriter terminalWriter,
      boolean shortVersion)
      throws IOException {
    writeBaseProgress(status, message, terminalWriter);

    if (packageProgressReceiver != null) {
      Pair<String, String> progress = packageProgressReceiver.progressState();
      String analysisProgress = progress.getFirst();

      if (configuredTargetProgressReceiver != null) {
        analysisProgress += ", " + configuredTargetProgressReceiver.getProgressString();
      }

      if (message.isEmpty()) {
        terminalWriter.append(analysisProgress);
      } else {
        terminalWriter.append(" (" + analysisProgress + ")");
      }
      if (!progress.getSecond().isEmpty() && !shortVersion) {
        terminalWriter.newline().append("    " + progress.getSecond());
      }
    }
  }

  @Override
  void buildStarted() {
    buildStatus = BuildStatus.BUILD_STARTED;
  }

  @Override
  void loadingStarted(LoadingPhaseStartedEvent event) {
    buildStatus = BuildStatus.TARGET_PATTERN_PARSING;
    packageProgressReceiver = event.getPackageProgressReceiver();
  }

  @Override
  void loadingComplete(LoadingPhaseCompleteEvent event) {
    buildStatus = BuildStatus.LOADING_COMPLETE;
    int labelsCount = event.getLabels().size();
    if (labelsCount == 1) {
      additionalMessage = "target " + Iterables.getOnlyElement(event.getLabels());
    } else {
      additionalMessage = labelsCount + " targets";
    }
  }

  @Override
  void configurationStarted(ConfigurationPhaseStartedEvent event) {
    buildStatus = BuildStatus.CONFIGURATION;
    configuredTargetProgressReceiver = event.getConfiguredTargetProgressReceiver();
  }

  /**
   * Make the state tracker aware of the fact that the analysis has finished. Return a summary of
   * the work done in the analysis phase.
   */
  @Override
  @CanIgnoreReturnValue
  synchronized String analysisComplete() {
    // This is where the path of the BuildStatus splits, the BuildStatus at this point could be
    // either CONFIGURATION or ANALYSIS_AND_EXECUTION.
    buildStatus =
        BuildStatus.CONFIGURATION.equals(buildStatus)
            ? BuildStatus.ANALYSIS_COMPLETE
            : BuildStatus.EXECUTION;
    String workDone = "Analyzed " + additionalMessage;
    if (packageProgressReceiver != null) {
      Pair<String, String> progress = packageProgressReceiver.progressState();
      workDone += " (" + progress.getFirst();
      if (configuredTargetProgressReceiver != null) {
        workDone += ", " + configuredTargetProgressReceiver.getProgressString();
      }
      workDone += ")";
    }
    workDone += ".";
    packageProgressReceiver = null;
    configuredTargetProgressReceiver = null;
    return workDone;
  }

  @Override
  synchronized void progressReceiverAvailable(ExecutionProgressReceiverAvailableEvent event) {
    executionProgressReceiver = event.getExecutionProgressReceiver();
    // This is where the path of the BuildStatus splits, the BuildStatus at this point could be
    // either CONFIGURATION or ANALYSIS_COMPLETE.
    buildStatus =
        BuildStatus.CONFIGURATION.equals(buildStatus)
            ? BuildStatus.ANALYSIS_AND_EXECUTION
            : BuildStatus.EXECUTION;
  }

  @Override
  void buildComplete(BuildCompleteEvent event) {
    buildStatus = BuildStatus.BUILD_COMPLETED;
    buildCompleteAt = Instant.ofEpochMilli(clock.currentTimeMillis());

    if (event.getResult().getSuccess()) {
      int actionsCompleted = this.actionsCompleted.get();
      if (failedTests == 0) {
        additionalMessage =
            "Build completed successfully, "
                + actionsCompleted
                + pluralize(" total action", actionsCompleted);
      } else {
        additionalMessage =
            "Build completed, "
                + failedTests
                + pluralize(" test", failedTests)
                + " FAILED, "
                + actionsCompleted
                + pluralize(" total action", actionsCompleted);
      }
    } else {
      ok = false;
      additionalMessage = "Build did NOT complete successfully";
    }
  }

  @Override
  protected boolean buildCompleted() {
    return BuildStatus.BUILD_COMPLETED.equals(buildStatus);
  }
}
