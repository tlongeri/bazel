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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyframeIterableResult;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Runs module discovery. This step of module resolution reads the module file of the root module
 * (i.e. the current workspace), adds its direct {@code bazel_dep}s to the dependency graph, and
 * repeats the step for any added dependencies until the entire graph is discovered.
 */
final class Discovery {
  private Discovery() {}

  /**
   * Runs module discovery. This function follows SkyFunction semantics (returns null if a Skyframe
   * dependency is missing and this function needs a restart).
   */
  @Nullable
  public static ImmutableMap<ModuleKey, Module> run(Environment env, RootModuleFileValue root)
      throws SkyFunctionException, InterruptedException {
    String rootModuleName = root.getModule().getName();
    ImmutableMap<String, ModuleOverride> overrides = root.getOverrides();
    Map<ModuleKey, Module> depGraph = new HashMap<>();
    depGraph.put(ModuleKey.ROOT, rewriteDepKeys(root.getModule(), overrides, rootModuleName));
    Queue<ModuleKey> unexpanded = new ArrayDeque<>();
    unexpanded.add(ModuleKey.ROOT);
    while (!unexpanded.isEmpty()) {
      Set<SkyKey> unexpandedSkyKeys = new HashSet<>();
      while (!unexpanded.isEmpty()) {
        Module module = depGraph.get(unexpanded.remove());
        for (ModuleKey depKey : module.getDeps().values()) {
          if (depGraph.containsKey(depKey)) {
            continue;
          }
          unexpandedSkyKeys.add(ModuleFileValue.key(depKey, overrides.get(depKey.getName())));
        }
      }
      SkyframeIterableResult result = env.getOrderedValuesAndExceptions(unexpandedSkyKeys);
      for (SkyKey skyKey : unexpandedSkyKeys) {
        ModuleKey depKey = ((ModuleFileValue.Key) skyKey).getModuleKey();
        ModuleFileValue moduleFileValue = (ModuleFileValue) result.next();
        if (moduleFileValue == null) {
          // Don't return yet. Try to expand any other unexpanded nodes before returning.
          depGraph.put(depKey, null);
        } else {
          depGraph.put(
              depKey, rewriteDepKeys(moduleFileValue.getModule(), overrides, rootModuleName));
          unexpanded.add(depKey);
        }
      }
    }
    if (env.valuesMissing()) {
      return null;
    }
    return ImmutableMap.copyOf(depGraph);
  }

  private static Module rewriteDepKeys(
      Module module, ImmutableMap<String, ModuleOverride> overrides, String rootModuleName) {
    return module.withDepKeysTransformed(
        depKey -> {
          if (rootModuleName.equals(depKey.getName())) {
            return ModuleKey.ROOT;
          }

          Version newVersion = depKey.getVersion();
          @Nullable ModuleOverride override = overrides.get(depKey.getName());
          if (override instanceof NonRegistryOverride) {
            newVersion = Version.EMPTY;
          } else if (override instanceof SingleVersionOverride) {
            Version overrideVersion = ((SingleVersionOverride) override).getVersion();
            if (!overrideVersion.isEmpty()) {
              newVersion = overrideVersion;
            }
          }

          return ModuleKey.create(depKey.getName(), newVersion);
        });
  }
}
