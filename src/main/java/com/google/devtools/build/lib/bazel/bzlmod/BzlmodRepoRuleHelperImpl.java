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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.io.IOException;
import java.util.Optional;

/** A helper class to get {@link RepoSpec} for Bzlmod generated repositories. */
public final class BzlmodRepoRuleHelperImpl implements BzlmodRepoRuleHelper {

  @Override
  public Optional<RepoSpec> getRepoSpec(Environment env, RepositoryName repositoryName)
      throws InterruptedException, IOException {

    RootModuleFileValue root =
        (RootModuleFileValue) env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableMap<String, ModuleOverride> overrides = root.getOverrides();

    // Step 1: Look for repositories defined by non-registry overrides.
    Optional<RepoSpec> repoSpec = checkRepoFromNonRegistryOverrides(root, repositoryName);
    if (repoSpec.isPresent()) {
      return repoSpec;
    }

    // BazelModuleResolutionValue is affected by repos found in Step 1, therefore it should NOT be
    // requested in Step 1 to avoid cycle dependency.
    BazelModuleResolutionValue bazelModuleResolutionValue =
        (BazelModuleResolutionValue) env.getValue(BazelModuleResolutionValue.KEY);
    if (env.valuesMissing()) {
      return null;
    }

    // Step 2: Look for repositories derived from Bazel Modules.
    return checkRepoFromBazelModules(
        bazelModuleResolutionValue, overrides, env.getListener(), repositoryName);
  }

  private static Optional<RepoSpec> checkRepoFromNonRegistryOverrides(
      RootModuleFileValue root, RepositoryName repositoryName) {
    String moduleName = root.getNonRegistryOverrideCanonicalRepoNameLookup().get(repositoryName);
    if (moduleName == null) {
      return Optional.empty();
    }
    NonRegistryOverride override = (NonRegistryOverride) root.getOverrides().get(moduleName);
    return Optional.of(override.getRepoSpec(repositoryName));
  }

  private static Optional<RepoSpec> checkRepoFromBazelModules(
      BazelModuleResolutionValue bazelModuleResolutionValue,
      ImmutableMap<String, ModuleOverride> overrides,
      ExtendedEventHandler eventListener,
      RepositoryName repositoryName)
      throws InterruptedException, IOException {
    ModuleKey moduleKey =
        bazelModuleResolutionValue.getCanonicalRepoNameLookup().get(repositoryName);
    if (moduleKey == null) {
      return Optional.empty();
    }
    Module module = bazelModuleResolutionValue.getDepGraph().get(moduleKey);
    Registry registry = checkNotNull(module.getRegistry());
    RepoSpec repoSpec = registry.getRepoSpec(moduleKey, repositoryName, eventListener);
    repoSpec = maybeAppendAdditionalPatches(repoSpec, overrides.get(moduleKey.getName()));
    return Optional.of(repoSpec);
  }

  private static RepoSpec maybeAppendAdditionalPatches(RepoSpec repoSpec, ModuleOverride override) {
    if (!(override instanceof SingleVersionOverride)) {
      return repoSpec;
    }
    SingleVersionOverride singleVersion = (SingleVersionOverride) override;
    if (singleVersion.getPatches().isEmpty()) {
      return repoSpec;
    }
    ImmutableMap.Builder<String, Object> attrBuilder = ImmutableMap.builder();
    attrBuilder.putAll(repoSpec.attributes());
    attrBuilder.put("patches", singleVersion.getPatches());
    attrBuilder.put("patch_args", ImmutableList.of("-p" + singleVersion.getPatchStrip()));
    return RepoSpec.builder()
        .setBzlFile(repoSpec.bzlFile())
        .setRuleClassName(repoSpec.ruleClassName())
        .setAttributes(attrBuilder.buildOrThrow())
        .build();
  }
}
