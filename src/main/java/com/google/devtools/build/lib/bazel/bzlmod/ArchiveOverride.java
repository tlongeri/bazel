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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.RepositoryName;

/** Specifies that a module should be retrieved from an archive. */
@AutoValue
public abstract class ArchiveOverride implements NonRegistryOverride {

  public static ArchiveOverride create(
      ImmutableList<String> urls,
      ImmutableList<String> patches,
      String integrity,
      String stripPrefix,
      int patchStrip) {
    return new AutoValue_ArchiveOverride(urls, patches, integrity, stripPrefix, patchStrip);
  }

  /** The URLs pointing at the archives. Can be HTTP(S) or file URLs. */
  public abstract ImmutableList<String> getUrls();

  /** The patches to apply after extracting the archive. Should be a list of labels. */
  public abstract ImmutableList<String> getPatches();

  /** The subresource integirty metadata of the archive. */
  public abstract String getIntegrity();

  /** The prefix to strip from paths in the archive. */
  public abstract String getStripPrefix();

  /** The number of path segments to strip from the paths in the supplied patches. */
  public abstract int getPatchStrip();

  /** Returns the {@link RepoSpec} that defines this repository. */
  @Override
  public RepoSpec getRepoSpec(RepositoryName repoName) {
    return new ArchiveRepoSpecBuilder()
        .setRepoName(repoName.getName())
        .setUrls(getUrls())
        .setIntegrity(getIntegrity())
        .setStripPrefix(getStripPrefix())
        .setPatches(getPatches())
        .setPatchStrip(getPatchStrip())
        .build();
  }
}
