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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;

/**
 * The result of running Bazel module inspection pre-processing, containing the un-pruned and
 * augmented wrappers of the Bazel module dependency graph (post-version-resolution).
 */
@AutoValue
public abstract class BazelModuleInspectorValue implements SkyValue {

  @SerializationConstant
  public static final SkyKey KEY = () -> SkyFunctions.BAZEL_MODULE_INSPECTION;

  public static BazelModuleInspectorValue create(
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex) {
    return new AutoValue_BazelModuleInspectorValue(depGraph, modulesIndex);
  }

  /**
   * The (bidirectional) inspection dep graph, containing wrappers of the {@link Module}, augmented
   * with references to dependants. The order is non-deterministic, inherited from the {@code
   * completeDepGraph} of {@link BazelModuleResolutionValue}. For any KEY in the returned map, it's
   * guaranteed that {@code depGraph[KEY].getKey() == KEY}.
   */
  public abstract ImmutableMap<ModuleKey, AugmentedModule> getDepGraph();

  /**
   * Index of all module keys mentioned in the un-pruned dep graph (loaded or not) for easy lookup.
   * It is a map from <i>module name</i> to the set of {@link ModuleKey}s that point to a version of
   * that module.
   */
  public abstract ImmutableMap<String, ImmutableSet<ModuleKey>> getModulesIndex();

  /**
   * A wrapper for {@link Module}, augmented with references to dependants (and also those who are
   * not used in the final dep graph).
   */
  @AutoValue
  public abstract static class AugmentedModule {
    /** Name of the module. Same as in {@link Module}. */
    public abstract String getName();

    /** Version of the module. Same as in {@link Module}. */
    public abstract Version getVersion();

    /** {@link ModuleKey} of this module. Same as in {@link Module} */
    public abstract ModuleKey getKey();

    /**
     * The set of modules in the resolved dep graph that depend on this module
     * <strong>after</strong> the module resolution.
     */
    public abstract ImmutableSet<ModuleKey> getDependants();

    /**
     * The set of modules in the complete dep graph that originally depended on this module *before*
     * the module resolution (can contain unused nodes).
     */
    public abstract ImmutableSet<ModuleKey> getOriginalDependants();

    /**
     * A bi-map from the repo names of the resolved dependencies of this module to their actual
     * module keys. The inverse lookup is used to check how and why the dependency changed using the
     * other maps.
     */
    public abstract ImmutableBiMap<String, ModuleKey> getDeps();

    /**
     * A bi-map from the repo names of the unused dependencies of this module to their actual module
     * keys. The inverse lookup is used to check how and why the dependency changed using the other
     * maps.
     */
    public abstract ImmutableBiMap<String, ModuleKey> getUnusedDeps();

    /**
     * A map from the repo name of the dependencies of this module to the rules that were used for
     * their resolution (can be either the original dependency, changed by the Minimal-Version
     * Selection algorithm or by an override rule.
     */
    public abstract ImmutableMap<String, ResolutionReason> getDepReasons();

    /** Shortcut for retrieving the union of both used and unused deps based on the unused flag. */
    public ImmutableMap<ModuleKey, String> getAllDeps(boolean unused) {
      if (!unused) {
        return getDeps().inverse();
      } else {
        Map<ModuleKey, String> map = new HashMap<>();
        map.putAll(getDeps().inverse());
        map.putAll(getUnusedDeps().inverse());
        return ImmutableMap.copyOf(map);
      }
    }

    /**
     * Flag that tell whether the module was loaded and added to the dependency graph. Modules
     * overridden by {@code single_version_override} and {@link NonRegistryOverride} are not loaded
     * so their {@code originalDeps} are (yet) unknown.
     */
    public abstract boolean isLoaded();

    /** Flag for checking whether the module is present in the resolved dep graph. */
    public boolean isUsed() {
      return !getDependants().isEmpty();
    }

    /** Returns a new {@link AugmentedModule.Builder} with {@code key} set. */
    public static AugmentedModule.Builder builder(ModuleKey key) {
      return new AutoValue_BazelModuleInspectorValue_AugmentedModule.Builder()
          .setName(key.getName())
          .setVersion(key.getVersion())
          .setKey(key)
          .setLoaded(false);
    }

    /** Builder type for {@link AugmentedModule}. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract AugmentedModule.Builder setName(String value);

      public abstract AugmentedModule.Builder setVersion(Version value);

      public abstract AugmentedModule.Builder setKey(ModuleKey value);

      public abstract AugmentedModule.Builder setLoaded(boolean value);

      abstract ImmutableSet.Builder<ModuleKey> originalDependantsBuilder();

      @CanIgnoreReturnValue
      public AugmentedModule.Builder addOriginalDependant(ModuleKey depKey) {
        originalDependantsBuilder().add(depKey);
        return this;
      }

      abstract ImmutableSet.Builder<ModuleKey> dependantsBuilder();

      @CanIgnoreReturnValue
      public AugmentedModule.Builder addDependant(ModuleKey depKey) {
        dependantsBuilder().add(depKey);
        return this;
      }

      abstract ImmutableBiMap.Builder<String, ModuleKey> depsBuilder();

      @CanIgnoreReturnValue
      public AugmentedModule.Builder addDep(String repoName, ModuleKey key) {
        depsBuilder().put(repoName, key);
        return this;
      }

      abstract ImmutableBiMap.Builder<String, ModuleKey> unusedDepsBuilder();

      @CanIgnoreReturnValue
      public AugmentedModule.Builder addUnusedDep(String repoName, ModuleKey key) {
        unusedDepsBuilder().put(repoName, key);
        return this;
      }

      abstract ImmutableMap.Builder<String, ResolutionReason> depReasonsBuilder();

      @CanIgnoreReturnValue
      public AugmentedModule.Builder addDepReason(String repoName, ResolutionReason reason) {
        depReasonsBuilder().put(repoName, reason);
        return this;
      }

      public abstract AugmentedModule build();
    }

    /** The reason why a final dependency of a module was resolved the way it was. */
    public enum ResolutionReason {
      /** The dependency is the original dependency defined in the MODULE.bazel file. */
      ORIGINAL,
      /** The dependency was replaced by the Minimal-Version Selection algorithm. */
      MINIMAL_VERSION_SELECTION,
      /** The dependency was replaced by a {@code single_version_override} rule. */
      SINGLE_VERSION_OVERRIDE,
      /** The dependency was replaced by a {@code multiple_version_override} rule. */
      MULTIPLE_VERSION_OVERRIDE,
      /** The dependency was replaced by one of the {@link NonRegistryOverride} rules. */
      ARCHIVE_OVERRIDE,
      GIT_OVERRIDE,
      LOCAL_PATH_OVERRIDE,
    }
  }
}
