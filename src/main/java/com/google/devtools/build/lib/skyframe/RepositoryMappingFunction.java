// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionResolutionValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** {@link SkyFunction} for {@link RepositoryMappingValue}s. */
public class RepositoryMappingFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    RepositoryName repositoryName = (RepositoryName) skyKey.argument();

    BazelModuleResolutionValue bazelModuleResolutionValue = null;
    if (Preconditions.checkNotNull(RepositoryDelegatorFunction.ENABLE_BZLMOD.get(env))) {
      bazelModuleResolutionValue =
          (BazelModuleResolutionValue) env.getValue(BazelModuleResolutionValue.KEY);
      if (env.valuesMissing()) {
        return null;
      }

      // The root module should be able to see repos defined in WORKSPACE. Therefore, we find all
      // workspace repos and add them as extra visible repos in root module's repo mappings.
      if (repositoryName.isMain()) {
        SkyKey externalPackageKey = PackageValue.key(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
        PackageValue externalPackageValue = (PackageValue) env.getValue(externalPackageKey);
        if (env.valuesMissing()) {
          return null;
        }
        Map<RepositoryName, RepositoryName> additionalMappings =
            externalPackageValue.getPackage().getTargets().entrySet().stream()
                // We need to filter out the non repository rule targets in the //external package.
                .filter(
                    entry ->
                        entry.getValue().getAssociatedRule() != null
                            && !entry.getValue().getAssociatedRule().getRuleClass().equals("bind"))
                .collect(
                    Collectors.toMap(
                        entry -> RepositoryName.createUnvalidated(entry.getKey()),
                        entry -> RepositoryName.createUnvalidated(entry.getKey())));
        return RepositoryMappingValue.withMapping(
            computeForBazelModuleRepo(repositoryName, bazelModuleResolutionValue)
                .get()
                .withAdditionalMappings(additionalMappings));
      }

      // Try and see if this is a repo generated from a Bazel module.
      Optional<RepositoryMapping> mapping =
          computeForBazelModuleRepo(repositoryName, bazelModuleResolutionValue);
      if (mapping.isPresent()) {
        return RepositoryMappingValue.withMapping(mapping.get());
      }

      // Now try and see if this is a repo generated from a module extension.
      // @bazel_tools and @local_config_platform are loaded most of the time, but we don't want
      // them to always trigger module extension resolution.
      // Keep this in sync with {@BzlmodRepoRuleFunction}
      if (!repositoryName.equals(RepositoryName.BAZEL_TOOLS)
          && !repositoryName.equals(RepositoryName.LOCAL_CONFIG_PLATFORM)) {
        ModuleExtensionResolutionValue moduleExtensionResolutionValue =
            (ModuleExtensionResolutionValue) env.getValue(ModuleExtensionResolutionValue.KEY);
        if (env.valuesMissing()) {
          return null;
        }
        mapping =
            computeForModuleExtensionRepo(
                repositoryName, bazelModuleResolutionValue, moduleExtensionResolutionValue);
        if (mapping.isPresent()) {
          return RepositoryMappingValue.withMapping(mapping.get());
        }
      }
    }

    SkyKey externalPackageKey = PackageValue.key(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
    PackageValue externalPackageValue = (PackageValue) env.getValue(externalPackageKey);
    if (env.valuesMissing()) {
      return null;
    }

    return computeFromWorkspace(repositoryName, externalPackageValue, bazelModuleResolutionValue);
  }

  /**
   * Calculate repo mappings for a repo generated from a Bazel module. Such a repo can see all its
   * {@code bazel_dep}s, as well as any repos generated by an extension it has a {@code use_repo}
   * clause for.
   *
   * @return the repo mappings for the repo if it's generated from a Bazel module, otherwise return
   *     Optional.empty().
   */
  private Optional<RepositoryMapping> computeForBazelModuleRepo(
      RepositoryName repositoryName, BazelModuleResolutionValue bazelModuleResolutionValue) {
    ModuleKey moduleKey =
        bazelModuleResolutionValue.getCanonicalRepoNameLookup().get(repositoryName.getName());
    if (moduleKey == null) {
      return Optional.empty();
    }
    return Optional.of(bazelModuleResolutionValue.getFullRepoMapping(moduleKey));
  }

  /**
   * Calculate repo mappings for a repo generated from a module extension. Such a repo can see all
   * repos generated by the same module extension, as well as all repos that the Bazel module
   * hosting the extension can see (see above).
   *
   * @return the repo mappings for the repo if it's generated from a module extension, otherwise
   *     return Optional.empty().
   */
  private Optional<RepositoryMapping> computeForModuleExtensionRepo(
      RepositoryName repositoryName,
      BazelModuleResolutionValue bazelModuleResolutionValue,
      ModuleExtensionResolutionValue moduleExtensionResolutionValue) {
    ModuleExtensionId extensionId =
        moduleExtensionResolutionValue
            .getCanonicalRepoNameToExtensionId()
            .get(repositoryName.getName());
    if (extensionId == null) {
      return Optional.empty();
    }
    String extensionUniqueName =
        bazelModuleResolutionValue.getExtensionUniqueNames().get(extensionId);
    // Compute the "internal mappings", containing the mappings from the "internal" names to
    // canonical names of all repos generated by this extension.
    ImmutableMap<RepositoryName, RepositoryName> internalMapping =
        moduleExtensionResolutionValue.getExtensionIdToRepoInternalNames().get(extensionId).stream()
            .collect(
                toImmutableMap(
                    RepositoryName::createUnvalidated,
                    internalName ->
                        RepositoryName.createUnvalidated(
                            extensionUniqueName + "." + internalName)));
    // Find the key of the module containing this extension. This will be used to compute additional
    // mappings -- any repo generated by an extension contained in the module "foo" can additionally
    // see all repos that "foo" can see.
    ModuleKey moduleKey =
        bazelModuleResolutionValue
            .getCanonicalRepoNameLookup()
            .get(extensionId.getBzlFileLabel().getRepository().getName());
    // NOTE(wyv): This means that if "foo" has a bazel_dep with the repo name "bar", and the
    // extension generates an internal repo name "bar", then within a repo generated by the
    // extension, "bar" will refer to the latter. We should explore a way to differentiate between
    // the two to avoid any surprises.
    return Optional.of(
        RepositoryMapping.create(internalMapping, repositoryName.getName())
            .withAdditionalMappings(bazelModuleResolutionValue.getFullRepoMapping(moduleKey)));
  }

  private SkyValue computeFromWorkspace(
      RepositoryName repositoryName,
      PackageValue externalPackageValue,
      @Nullable BazelModuleResolutionValue bazelModuleResolutionValue)
      throws RepositoryMappingFunctionException {
    Package externalPackage = externalPackageValue.getPackage();
    if (externalPackage.containsErrors()) {
      throw new RepositoryMappingFunctionException();
    }
    if (bazelModuleResolutionValue == null) {
      return RepositoryMappingValue.withMapping(
          RepositoryMapping.createAllowingFallback(
              externalPackage.getRepositoryMapping(repositoryName)));
    }
    // If bzlmod is in play, we need to transform mappings to "foo" into mappings for "foo.1.3" (if
    // there is a module called "foo" in the dep graph and its version is 1.3, that is).
    ImmutableMap<String, ModuleKey> moduleNameLookup =
        bazelModuleResolutionValue.getModuleNameLookup();
    HashMap<RepositoryName, RepositoryName> mapping = new HashMap<>();
    mapping.putAll(
        Maps.transformValues(
            externalPackage.getRepositoryMapping(repositoryName),
            toRepo -> {
              if (toRepo.isMain()) {
                return toRepo;
              }
              ModuleKey moduleKey = moduleNameLookup.get(toRepo.getName());
              return moduleKey == null
                  ? toRepo
                  : RepositoryName.createUnvalidated(moduleKey.getCanonicalRepoName());
            }));
    // If there's no existing mapping to "foo", we should add a mapping from "foo" to "foo.1.3"
    // anyways.
    for (Map.Entry<String, ModuleKey> entry : moduleNameLookup.entrySet()) {
      mapping.putIfAbsent(
          RepositoryName.createUnvalidated(entry.getKey()),
          RepositoryName.createUnvalidated(entry.getValue().getCanonicalRepoName()));
    }
    return RepositoryMappingValue.withMapping(
        RepositoryMapping.createAllowingFallback(ImmutableMap.copyOf(mapping)));
  }

  private static class RepositoryMappingFunctionException extends SkyFunctionException {
    RepositoryMappingFunctionException() {
      super(
          new BuildFileContainsErrorsException(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER),
          Transience.PERSISTENT);
    }
  }
}
