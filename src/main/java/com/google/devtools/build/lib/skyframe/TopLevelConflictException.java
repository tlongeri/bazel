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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.ConflictException;

/**
 * Encapsulates a collection of action conflicts of the transitive closure of a top-level
 * ActionLookupKey.
 */
final class TopLevelConflictException extends Exception {

  private final ImmutableMap<ActionAnalysisMetadata, ConflictException> transitiveActionConflicts;

  TopLevelConflictException(
      String message, ImmutableMap<ActionAnalysisMetadata, ConflictException> actionConflicts) {
    super(message);
    this.transitiveActionConflicts = actionConflicts;
  }

  ImmutableMap<ActionAnalysisMetadata, ConflictException> getTransitiveActionConflicts() {
    return transitiveActionConflicts;
  }
}
