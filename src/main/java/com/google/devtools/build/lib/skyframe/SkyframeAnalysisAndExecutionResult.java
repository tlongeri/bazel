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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.PackageRoots;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.WalkableGraph;

/** Encapsulates the raw analysis result of top level targets and aspects coming from Skyframe. */
public final class SkyframeAnalysisAndExecutionResult extends SkyframeAnalysisResult {
  private final DetailedExitCode representativeExecutionExitCode;

  SkyframeAnalysisAndExecutionResult(
      boolean hasLoadingError,
      boolean hasAnalysisError,
      boolean hasActionConflicts,
      ImmutableList<ConfiguredTarget> configuredTargets,
      WalkableGraph walkableGraph,
      ImmutableMap<AspectKey, ConfiguredAspect> aspects,
      PackageRoots packageRoots,
      DetailedExitCode representativeExecutionExitCode) {
    super(
        hasLoadingError,
        hasAnalysisError,
        hasActionConflicts,
        configuredTargets,
        walkableGraph,
        aspects,
        packageRoots);
    this.representativeExecutionExitCode = representativeExecutionExitCode;
  }

  public DetailedExitCode getRepresentativeExecutionExitCode() {
    return representativeExecutionExitCode;
  }

  /**
   * Returns an equivalent {@link SkyframeAnalysisAndExecutionResult}, except with errored targets
   * removed from the configured target list.
   */
  @Override
  public SkyframeAnalysisAndExecutionResult withAdditionalErroredTargets(
      ImmutableSet<ConfiguredTarget> erroredTargets) {
    return new SkyframeAnalysisAndExecutionResult(
        hasLoadingError(),
        /*hasAnalysisError=*/ true,
        hasActionConflicts(),
        Sets.difference(ImmutableSet.copyOf(getConfiguredTargets()), erroredTargets)
            .immutableCopy()
            .asList(),
        getWalkableGraph(),
        getAspects(),
        getPackageRoots(),
        representativeExecutionExitCode);
  }
}
