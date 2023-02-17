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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.io.IOException;
import java.util.Optional;

/** A helper to get {@link RepoSpec} for Bzlmod generated repositories. */
public interface BzlmodRepoRuleHelper {
  Optional<RepoSpec> getRepoSpec(Environment env, RepositoryName repositoryName)
      throws InterruptedException, IOException;
}
