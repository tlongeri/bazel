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

package com.google.devtools.build.lib.actions;

import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.errorprone.annotations.CheckReturnValue;

/**
 * Interface to be used by threads doing work that should be tracked. {@link #started} is called at
 * the beginning of the thread's work. When not tracking work, the {@link #NULL_INSTANCE} should be
 * used to avoid memory and process overhead.
 */
public interface ThreadStateReceiver {
  ThreadStateReceiver NULL_INSTANCE = () -> () -> {};

  @CheckReturnValue
  SilentCloseable started();
}
