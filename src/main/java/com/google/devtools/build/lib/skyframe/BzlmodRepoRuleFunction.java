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

package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleCreator;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleHelper;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionResolutionValue;
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Package.NameConflictException;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.RuleFactory;
import com.google.devtools.build.lib.packages.RuleFactory.BuildLangTypedAttributeValuesMap;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread.CallStackEntry;
import net.starlark.java.syntax.Location;

/**
 * Looks up the {@link RepoSpec} of a given repository name and create its repository rule instance.
 */
public final class BzlmodRepoRuleFunction implements SkyFunction {

  private final PackageFactory packageFactory;
  private final RuleClassProvider ruleClassProvider;
  private final BlazeDirectories directories;
  private final BzlmodRepoRuleHelper bzlmodRepoRuleHelper;
  private static final PackageIdentifier ROOT_PACKAGE = PackageIdentifier.createInMainRepo("");

  public BzlmodRepoRuleFunction(
      PackageFactory packageFactory,
      RuleClassProvider ruleClassProvider,
      BlazeDirectories directories,
      BzlmodRepoRuleHelper bzlmodRepoRuleHelper) {
    this.packageFactory = packageFactory;
    this.ruleClassProvider = ruleClassProvider;
    this.directories = directories;
    this.bzlmodRepoRuleHelper = bzlmodRepoRuleHelper;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }

    String repositoryName = ((BzlmodRepoRuleValue.Key) skyKey).argument();

    // 1. Get repo spec for some special repos
    // Keep this in sync with {@RepositoryMappingFunction}
    // @bazel_tools is a special repo that we pull from the extracted install dir.
    if (repositoryName.equals(RepositoryName.BAZEL_TOOLS.getName())) {
      RepoSpec repoSpec =
          RepoSpec.builder()
              .setRuleClassName("local_repository")
              .setAttributes(
                  ImmutableMap.of(
                      "name",
                      "bazel_tools",
                      "path",
                      directories
                          .getEmbeddedBinariesRoot()
                          .getChild("embedded_tools")
                          .getPathString()))
              .build();
      return createRuleFromSpec(repoSpec, starlarkSemantics, env);
    }

    // @local_config_platform is current generated by the native repo rule local_config_platform
    // It has to be a special repo for now because:
    //   - It's embedded in local_config_platform.WORKSPACE and depended on by many toolchains.
    //   - It can't be starlarkified yet because we can't access the cpu value in repository_ctx.
    //   - The canonical name "local_config_platform" is hardcoded in Bazel code.
    //     See {@link PlatformOptions}
    if (repositoryName.equals(RepositoryName.LOCAL_CONFIG_PLATFORM.getName())) {
      RepoSpec repoSpec =
          RepoSpec.builder()
              .setRuleClassName("local_config_platform")
              .setAttributes(ImmutableMap.of("name", "local_config_platform"))
              .build();
      return createRuleFromSpec(repoSpec, starlarkSemantics, env);
    }

    // 2. Look for the repo from Bazel module generated repos
    try {
      Optional<RepoSpec> result = bzlmodRepoRuleHelper.getRepoSpec(env, repositoryName);
      if (env.valuesMissing()) {
        return null;
      }
      if (result.isPresent()) {
        return createRuleFromSpec(result.get(), starlarkSemantics, env);
      }
    } catch (IOException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    }

    // 3. Otherwise, look for the repo from module extension evaluation results.
    ModuleExtensionResolutionValue extensionResolution =
        (ModuleExtensionResolutionValue) env.getValue(ModuleExtensionResolutionValue.KEY);
    if (extensionResolution == null) {
      return null;
    }
    Package pkg = extensionResolution.getCanonicalRepoNameToPackage().get(repositoryName);
    if (pkg != null) {
      return new BzlmodRepoRuleValue(pkg, repositoryName);
    }

    return BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE;
  }

  private BzlmodRepoRuleValue createRuleFromSpec(
      RepoSpec repoSpec, StarlarkSemantics starlarkSemantics, Environment env)
      throws BzlmodRepoRuleFunctionException, InterruptedException {
    if (repoSpec.isNativeRepoRule()) {
      return createNativeRepoRule(repoSpec, starlarkSemantics, env);
    }
    return createStarlarkRepoRule(repoSpec, starlarkSemantics, env);
  }

  private BzlmodRepoRuleValue createNativeRepoRule(
      RepoSpec repoSpec, StarlarkSemantics semantics, Environment env)
      throws InterruptedException, BzlmodRepoRuleFunctionException {
    if (!ruleClassProvider.getRuleClassMap().containsKey(repoSpec.ruleClassName())) {
      InvalidRuleException e =
          new InvalidRuleException(
              "Unrecognized native repository rule: " + repoSpec.getRuleClass());
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    }
    RuleClass ruleClass = ruleClassProvider.getRuleClassMap().get(repoSpec.ruleClassName());
    BuildLangTypedAttributeValuesMap attributeValues =
        new BuildLangTypedAttributeValuesMap(repoSpec.attributes());
    ImmutableList.Builder<CallStackEntry> callStack = ImmutableList.builder();
    callStack.add(new CallStackEntry("BzlmodRepoRuleFunction.getNativeRepoRule", Location.BUILTIN));
    // TODO(bazel-team): Don't use the {@link Rule} class for repository rule.
    // Currently, the repository rule is represented with the {@link Rule} class that's designed
    // for build rules. Therefore, we have to create a package instance for it, which doesn't make
    // sense. We should migrate away from this implementation so that we don't refer to any build
    // rule specific things in repository rule.
    Rule rule;
    Package.Builder pkg = createExternalPackageBuilder(semantics);
    try {
      rule =
          RuleFactory.createAndAddRule(
              pkg, ruleClass, attributeValues, env.getListener(), semantics, callStack.build());
      return new BzlmodRepoRuleValue(pkg.build(), rule.getName());
    } catch (InvalidRuleException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    } catch (NoSuchPackageException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    } catch (NameConflictException e) {
      // This literally cannot happen -- we just created the package!
      throw new IllegalStateException(e);
    }
  }

  /** Loads modules from the given bzl file. */
  private ImmutableMap<String, Module> loadBzlModules(Environment env, String bzlFile)
      throws InterruptedException, BzlmodRepoRuleFunctionException {
    ImmutableList<Pair<String, Location>> programLoads =
        ImmutableList.of(Pair.of(bzlFile, Location.BUILTIN));

    ImmutableList<Label> loadLabels =
        BzlLoadFunction.getLoadLabels(
            env.getListener(),
            programLoads,
            ROOT_PACKAGE,
            /*repoMapping=*/ RepositoryMapping.ALWAYS_FALLBACK);
    if (loadLabels == null) {
      NoSuchPackageException e =
          PackageFunction.PackageFunctionException.builder()
              .setType(PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
              .setPackageIdentifier(ROOT_PACKAGE)
              .setMessage("malformed load statements")
              .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
              .buildCause();
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    }

    Preconditions.checkArgument(loadLabels.size() == 1);
    ImmutableList<BzlLoadValue.Key> keys =
        ImmutableList.of(BzlLoadValue.keyForBzlmod(loadLabels.get(0)));

    // Load the .bzl module.
    try {
      return PackageFunction.loadBzlModules(env, ROOT_PACKAGE, programLoads, keys, null);
    } catch (NoSuchPackageException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    }
  }

  private BzlmodRepoRuleValue createStarlarkRepoRule(
      RepoSpec repoSpec, StarlarkSemantics semantics, Environment env)
      throws InterruptedException, BzlmodRepoRuleFunctionException {

    ImmutableMap<String, Module> loadedModules = loadBzlModules(env, repoSpec.bzlFile().get());

    if (env.valuesMissing()) {
      return null;
    }

    BzlmodRepoRuleCreator repoRuleCreator = getRepoRuleCreator(repoSpec, loadedModules);

    // TODO(bazel-team): Don't use the {@link Rule} class for repository rule.
    // Currently, the repository rule is represented with the {@link Rule} class that's designed
    // for build rules. Therefore, we have to create a package instance for it, which doesn't make
    // sense. We should migrate away from this implementation so that we don't refer to any build
    // rule specific things in repository rule.
    Rule rule;
    Package.Builder pkg = createExternalPackageBuilder(semantics);
    StoredEventHandler eventHandler = new StoredEventHandler();
    try {
      rule = repoRuleCreator.createAndAddRule(pkg, semantics, repoSpec.attributes(), eventHandler);
      return new BzlmodRepoRuleValue(pkg.build(), rule.getName());
    } catch (InvalidRuleException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    } catch (NoSuchPackageException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    } catch (NameConflictException e) {
      // This literally cannot happen -- we just created the package!
      throw new IllegalStateException(e);
    }
  }

  private BzlmodRepoRuleCreator getRepoRuleCreator(
      RepoSpec repoSpec, ImmutableMap<String, Module> loadedModules)
      throws BzlmodRepoRuleFunctionException {
    Object object = loadedModules.get(repoSpec.bzlFile().get()).getGlobal(repoSpec.ruleClassName());
    if (object instanceof BzlmodRepoRuleCreator) {
      return (BzlmodRepoRuleCreator) object;
    } else {
      InvalidRuleException e =
          new InvalidRuleException("Invalid repository rule: " + repoSpec.getRuleClass());
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    }
  }

  /**
   * Create the external package builder, which is only for the convenience of creating repository
   * rules.
   */
  private Package.Builder createExternalPackageBuilder(StarlarkSemantics semantics) {
    RootedPath bzlmodFile =
        RootedPath.toRootedPath(
            Root.fromPath(directories.getWorkspace()), LabelConstants.MODULE_DOT_BAZEL_FILE_NAME);

    return packageFactory.newExternalPackageBuilder(
        bzlmodFile, ruleClassProvider.getRunfilesPrefix(), semantics);
  }

  private static final class BzlmodRepoRuleFunctionException extends SkyFunctionException {

    BzlmodRepoRuleFunctionException(InvalidRuleException e, Transience transience) {
      super(e, transience);
    }

    BzlmodRepoRuleFunctionException(NoSuchPackageException e, Transience transience) {
      super(e, transience);
    }

    BzlmodRepoRuleFunctionException(IOException e, Transience transience) {
      super(e, transience);
    }
  }
}
