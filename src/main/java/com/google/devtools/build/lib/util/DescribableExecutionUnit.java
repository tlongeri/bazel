// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import javax.annotation.Nullable;

/**
 * Something executable that can be described by {@link CommandFailureUtils#describeCommandFailure}.
 */
public interface DescribableExecutionUnit {

  @Nullable
  default String getTargetLabel() {
    return null;
  }

  /** Returns the command (the first element) and its arguments. */
  ImmutableList<String> getArguments();

  /**
   * Returns the initial environment of the process. If null, the environment is inherited from the
   * parent process.
   */
  ImmutableMap<String, String> getEnvironment();

  /** Returns the Label of the execution platform for the command, if any, as a String. */
  @Nullable
  default String getExecutionPlatformLabelString() {
    return null;
  }

  /** Returns the configuration hash for this command, if any. */
  @Nullable
  default String getConfigurationChecksum() {
    return null;
  }
}
