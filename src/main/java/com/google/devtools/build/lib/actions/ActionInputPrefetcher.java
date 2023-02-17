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
package com.google.devtools.build.lib.actions;

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;

/** Prefetches files to local disk. */
public interface ActionInputPrefetcher {
  public static final ActionInputPrefetcher NONE =
      new ActionInputPrefetcher() {
        @Override
        public ListenableFuture<Void> prefetchFiles(
            Iterable<? extends ActionInput> inputs, MetadataProvider metadataProvider) {
          // Do nothing.
          return immediateVoidFuture();
        }

        @Override
        public boolean supportsPartialTreeArtifactInputs() {
          return true;
        }
      };

  /**
   * Initiates best-effort prefetching of all given inputs.
   *
   * <p>For any path not under this prefetcher's control, the call should be a no-op.
   *
   * @return future success if prefetch is finished or {@link IOException}.
   */
  ListenableFuture<Void> prefetchFiles(
      Iterable<? extends ActionInput> inputs, MetadataProvider metadataProvider);

  /**
   * Whether the prefetcher is able to fetch individual files in a tree artifact without fetching
   * the entire tree artifact.
   */
  boolean supportsPartialTreeArtifactInputs();
}
