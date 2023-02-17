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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import net.starlark.java.eval.StarlarkInt;

/**
 * Builder for a {@link RepoSpec} object that indicates how to materialize a repo corresponding to
 * an {@code http_archive} repo rule call.
 */
public class ArchiveRepoSpecBuilder {
  private final ImmutableMap.Builder<String, Object> attrBuilder;

  public ArchiveRepoSpecBuilder() {
    attrBuilder = new ImmutableMap.Builder<>();
  }

  @CanIgnoreReturnValue
  public ArchiveRepoSpecBuilder setRepoName(String repoName) {
    attrBuilder.put("name", repoName);
    return this;
  }

  @CanIgnoreReturnValue
  public ArchiveRepoSpecBuilder setUrls(ImmutableList<String> urls) {
    attrBuilder.put("urls", urls);
    return this;
  }

  @CanIgnoreReturnValue
  public ArchiveRepoSpecBuilder setIntegrity(String integrity) {
    attrBuilder.put("integrity", integrity);
    return this;
  }

  @CanIgnoreReturnValue
  public ArchiveRepoSpecBuilder setStripPrefix(String stripPrefix) {
    attrBuilder.put("strip_prefix", stripPrefix);
    return this;
  }

  @CanIgnoreReturnValue
  public ArchiveRepoSpecBuilder setPatches(ImmutableList<String> patches) {
    attrBuilder.put("patches", patches);
    return this;
  }

  @CanIgnoreReturnValue
  public ArchiveRepoSpecBuilder setPatchStrip(int patchStrip) {
    attrBuilder.put("patch_args", ImmutableList.of("-p" + patchStrip));
    return this;
  }

  @CanIgnoreReturnValue
  public ArchiveRepoSpecBuilder setRemotePatches(ImmutableMap<String, String> remotePatches) {
    attrBuilder.put("remote_patches", remotePatches);
    return this;
  }

  @CanIgnoreReturnValue
  public ArchiveRepoSpecBuilder setRemotePatchStrip(int remotePatchStrip) {
    attrBuilder.put("remote_patch_strip", StarlarkInt.of(remotePatchStrip));
    return this;
  }

  public RepoSpec build() {
    return RepoSpec.builder()
        .setBzlFile("@bazel_tools//tools/build_defs/repo:http.bzl")
        .setRuleClassName("http_archive")
        .setAttributes(attrBuilder.buildOrThrow())
        .build();
  }
}
