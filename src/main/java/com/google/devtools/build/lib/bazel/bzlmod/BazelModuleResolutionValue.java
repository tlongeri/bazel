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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;

/**
 * The result of running Bazel module resolution, containing the Bazel module dependency graph
 * post-version-resolution.
 */
@AutoValue
public abstract class BazelModuleResolutionValue implements SkyValue {
  @SerializationConstant
  public static final SkyKey KEY = () -> SkyFunctions.BAZEL_MODULE_RESOLUTION;

  public static BazelModuleResolutionValue create(
      ImmutableMap<ModuleKey, Module> depGraph,
      ImmutableMap<String, ModuleKey> canonicalRepoNameLookup,
      ImmutableMap<String, ModuleKey> moduleNameLookup,
      ImmutableList<AbridgedModule> abridgedModules,
      ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsagesTable,
      ImmutableMap<ModuleExtensionId, String> extensionUniqueNames) {
    return new AutoValue_BazelModuleResolutionValue(
        depGraph,
        canonicalRepoNameLookup,
        moduleNameLookup,
        abridgedModules,
        extensionUsagesTable,
        extensionUniqueNames);
  }

  /**
   * The post-selection dep graph. Must have BFS iteration order, starting from the root module. For
   * any KEY in the returned map, it's guaranteed that {@code depGraph[KEY].getKey() == KEY}.
   */
  public abstract ImmutableMap<ModuleKey, Module> getDepGraph();

  /** A mapping from a canonical repo name to the key of the module backing it. */
  public abstract ImmutableMap<String, ModuleKey> getCanonicalRepoNameLookup();

  /**
   * A mapping from a plain module name to the key of the module. Does not include the root module,
   * or modules with multiple-version overrides.
   */
  public abstract ImmutableMap<String, ModuleKey> getModuleNameLookup();

  /** All modules in the same order as {@link #getDepGraph}, but with limited information. */
  public abstract ImmutableList<AbridgedModule> getAbridgedModules();

  /**
   * All module extension usages grouped by the extension's ID and the key of the module where this
   * usage occurs. For each extension identifier ID, extensionUsagesTable[ID][moduleKey] is the
   * ModuleExtensionUsage of ID in the module keyed by moduleKey.
   */
  public abstract ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage>
      getExtensionUsagesTable();

  /**
   * A mapping from the ID of a module extension to a unique string that serves as its "name". This
   * is not the same as the extension's declared name, as the declared name is only unique within
   * the .bzl file, whereas this unique name is guaranteed to be unique across the workspace.
   */
  public abstract ImmutableMap<ModuleExtensionId, String> getExtensionUniqueNames();

  /**
   * Returns the full {@link RepositoryMapping} for the given module, including repos from Bazel
   * module deps and module extensions.
   */
  public final RepositoryMapping getFullRepoMapping(ModuleKey key) {
    ImmutableMap.Builder<RepositoryName, RepositoryName> mapping = ImmutableMap.builder();
    for (Map.Entry<ModuleExtensionId, ModuleExtensionUsage> e :
        getExtensionUsagesTable().column(key).entrySet()) {
      ModuleExtensionId extensionId = e.getKey();
      ModuleExtensionUsage usage = e.getValue();
      for (Map.Entry<String, String> entry : usage.getImports().entrySet()) {
        String canonicalRepoName =
            getExtensionUniqueNames().get(extensionId) + "." + entry.getValue();
        mapping.put(
            RepositoryName.createUnvalidated(entry.getKey()),
            RepositoryName.createUnvalidated(canonicalRepoName));
      }
    }
    return getDepGraph()
        .get(key)
        .getRepoMappingWithBazelDepsOnly()
        .withAdditionalMappings(mapping.build());
  }
}
