// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionValue;
import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException;
import com.google.devtools.build.lib.bazel.bzlmod.Module;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.SignedTargetPattern;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.TargetPatternUtil.InvalidTargetPatternException;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * {@link SkyFunction} that returns all registered toolchains available for toolchain resolution.
 */
public class RegisteredToolchainsFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {

    BuildConfigurationValue configuration =
        (BuildConfigurationValue)
            env.getValue(((RegisteredToolchainsValue.Key) skyKey).getConfigurationKey());
    RepositoryMappingValue mainRepoMapping =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
    if (env.valuesMissing()) {
      return null;
    }

    TargetPattern.Parser mainRepoParser =
        new TargetPattern.Parser(
            PathFragment.EMPTY_FRAGMENT,
            RepositoryName.MAIN,
            mainRepoMapping.getRepositoryMapping());
    ImmutableList.Builder<SignedTargetPattern> targetPatternBuilder = new ImmutableList.Builder<>();

    // Get the toolchains from the configuration.
    PlatformConfiguration platformConfiguration =
        configuration.getFragment(PlatformConfiguration.class);
    try {
      targetPatternBuilder.addAll(
          TargetPatternUtil.parseAllSigned(
              platformConfiguration.getExtraToolchains(), mainRepoParser));
    } catch (InvalidTargetPatternException e) {
      throw new RegisteredToolchainsFunctionException(
          new InvalidToolchainLabelException(e), Transience.PERSISTENT);
    }

    // Get registered toolchains from bzlmod.
    ImmutableList<TargetPattern> bzlmodToolchains = getBzlmodToolchains(env);
    if (bzlmodToolchains == null) {
      return null;
    }
    targetPatternBuilder.addAll(TargetPatternUtil.toSigned(bzlmodToolchains));

    // Get the registered toolchains from the WORKSPACE.
    ImmutableList<TargetPattern> workspaceToolchains = getWorkspaceToolchains(env);
    if (workspaceToolchains == null) {
      return null;
    }
    targetPatternBuilder.addAll(TargetPatternUtil.toSigned(workspaceToolchains));

    // Expand target patterns.
    ImmutableList<Label> toolchainLabels;
    try {
      toolchainLabels =
          TargetPatternUtil.expandTargetPatterns(
              env, targetPatternBuilder.build(), FilteringPolicies.ruleTypeExplicit("toolchain"));
      if (env.valuesMissing()) {
        return null;
      }
    } catch (TargetPatternUtil.InvalidTargetPatternException e) {
      throw new RegisteredToolchainsFunctionException(
          new InvalidToolchainLabelException(e), Transience.PERSISTENT);
    }

    // Load the configured target for each, and get the declared toolchain providers.
    ImmutableList<DeclaredToolchainInfo> registeredToolchains =
        configureRegisteredToolchains(env, configuration, toolchainLabels);
    if (env.valuesMissing()) {
      return null;
    }

    return RegisteredToolchainsValue.create(registeredToolchains);
  }

  /**
   * Loads the external package and then returns the registered toolchains.
   *
   * @param env the environment to use for lookups
   */
  @Nullable
  @VisibleForTesting
  public static ImmutableList<TargetPattern> getWorkspaceToolchains(Environment env)
      throws InterruptedException {
    PackageValue externalPackageValue =
        (PackageValue) env.getValue(PackageValue.key(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER));
    if (externalPackageValue == null) {
      return null;
    }

    Package externalPackage = externalPackageValue.getPackage();
    return externalPackage.getRegisteredToolchains();
  }

  @Nullable
  private static ImmutableList<TargetPattern> getBzlmodToolchains(Environment env)
      throws InterruptedException, RegisteredToolchainsFunctionException {
    if (!RepositoryDelegatorFunction.ENABLE_BZLMOD.get(env)) {
      return ImmutableList.of();
    }
    BazelModuleResolutionValue bazelModuleResolutionValue =
        (BazelModuleResolutionValue) env.getValue(BazelModuleResolutionValue.KEY);
    if (bazelModuleResolutionValue == null) {
      return null;
    }
    ImmutableList.Builder<TargetPattern> toolchains = ImmutableList.builder();
    for (Module module : bazelModuleResolutionValue.getDepGraph().values()) {
      TargetPattern.Parser parser =
          new TargetPattern.Parser(
              PathFragment.EMPTY_FRAGMENT,
              RepositoryName.createUnvalidated(module.getCanonicalRepoName()),
              bazelModuleResolutionValue.getFullRepoMapping(module.getKey()));
      for (String pattern : module.getToolchainsToRegister()) {
        try {
          toolchains.add(parser.parse(pattern));
        } catch (TargetParsingException e) {
          throw new RegisteredToolchainsFunctionException(
              new InvalidToolchainLabelException(pattern, e), Transience.PERSISTENT);
        }
      }
    }
    return toolchains.build();
  }

  private static ImmutableList<DeclaredToolchainInfo> configureRegisteredToolchains(
      Environment env, BuildConfigurationValue configuration, List<Label> labels)
      throws InterruptedException, RegisteredToolchainsFunctionException {
    ImmutableList<SkyKey> keys =
        labels.stream()
            .map(
                label ->
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfiguration(configuration)
                        .build())
            .collect(toImmutableList());

    Map<SkyKey, ValueOrException<ConfiguredValueCreationException>> values =
        env.getValuesOrThrow(keys, ConfiguredValueCreationException.class);
    ImmutableList.Builder<DeclaredToolchainInfo> toolchains = new ImmutableList.Builder<>();
    boolean valuesMissing = false;
    for (SkyKey key : keys) {
      ConfiguredTargetKey configuredTargetKey = (ConfiguredTargetKey) key.argument();
      Label toolchainLabel = configuredTargetKey.getLabel();
      try {
        ValueOrException<ConfiguredValueCreationException> valueOrException = values.get(key);
        if (valueOrException.get() == null) {
          valuesMissing = true;
          continue;
        }

        ConfiguredTarget target =
            ((ConfiguredTargetValue) valueOrException.get()).getConfiguredTarget();
        DeclaredToolchainInfo toolchainInfo = PlatformProviderUtils.declaredToolchainInfo(target);
        if (toolchainInfo == null) {
          throw new RegisteredToolchainsFunctionException(
              new InvalidToolchainLabelException(toolchainLabel), Transience.PERSISTENT);
        }
        toolchains.add(toolchainInfo);
      } catch (ConfiguredValueCreationException e) {
        throw new RegisteredToolchainsFunctionException(
            new InvalidToolchainLabelException(toolchainLabel, e), Transience.PERSISTENT);
      }
    }

    if (valuesMissing) {
      return null;
    }
    return toolchains.build();
  }

  /**
   * Used to indicate that the given {@link Label} represents a {@link ConfiguredTarget} which is
   * not a valid {@link DeclaredToolchainInfo} provider.
   */
  public static final class InvalidToolchainLabelException extends ToolchainException {

    public InvalidToolchainLabelException(Label invalidLabel) {
      super(
          formatMessage(
              invalidLabel.getCanonicalForm(),
              "target does not provide the DeclaredToolchainInfo provider"));
    }

    public InvalidToolchainLabelException(Label invalidLabel, String reason) {
      super(formatMessage(invalidLabel.getCanonicalForm(), reason));
    }

    public InvalidToolchainLabelException(TargetPatternUtil.InvalidTargetPatternException e) {
      this(e.getInvalidPattern(), e.getTpe());
    }

    public InvalidToolchainLabelException(String invalidPattern, TargetParsingException e) {
      super(formatMessage(invalidPattern, e.getMessage()), e);
    }

    public InvalidToolchainLabelException(Label invalidLabel, ConfiguredValueCreationException e) {
      super(formatMessage(invalidLabel.getCanonicalForm(), e.getMessage()), e);
    }

    @Override
    protected Code getDetailedCode() {
      return Code.INVALID_TOOLCHAIN;
    }

    private static String formatMessage(String invalidPattern, String reason) {
      return String.format("invalid registered toolchain '%s': %s", invalidPattern, reason);
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by {@link
   * #compute}.
   */
  public static class RegisteredToolchainsFunctionException extends SkyFunctionException {

    public RegisteredToolchainsFunctionException(
        InvalidToolchainLabelException cause, Transience transience) {
      super(cause, transience);
    }

    public RegisteredToolchainsFunctionException(
        ExternalDepsException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
