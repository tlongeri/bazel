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

package com.google.devtools.build.lib.analysis.constraints;

import static com.google.common.base.Predicates.notNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.IncompatiblePlatformProvider;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapBuilder;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.test.TestActionBuilder;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageSpecification;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.RuleConfiguredTargetValue;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Helpers for creating configured targets that are incompatible.
 *
 * <p>A target is considered incompatible if any of the following applies:
 *
 * <ol>
 *   <li>The target's <code>target_compatible_with</code> attribute specifies a constraint that is
 *       not present in the target platform. The target is said to be "directly incompatible".
 *   <li>One or more of the target's dependencies is incompatible. The target is said to be
 *       "indirectly incompatible."
 * </ol>
 *
 * The intent of these helpers is that they get called as early in the analysis phase as possible.
 * That's why there are two helpers instead of just one. The first helper determines direct
 * incompatibility very early in the analysis phase. If a target is not directly incompatible, the
 * dependencies need to be analysed and then we can check for indirect incompatibility. Doing these
 * checks as early as possible allows us to skip analysing unused dependencies and ignore unused
 * toolchains.
 *
 * <p>See https://bazel.build/docs/platforms#skipping-incompatible-targets for more information on
 * incompatible target skipping.
 */
public class IncompatibleTargetChecker {
  /**
   * Creates an incompatible configured target if it is "directly incompatible".
   *
   * <p>In other words, this function checks if a target is incompatible because of its
   * "target_compatible_with" attribute.
   *
   * <p>This function returns a nullable {@code Optional} of a {@link RuleConfiguredTargetValue}.
   * This provides three states of return values:
   *
   * <ul>
   *   <li>{@code null}: A skyframe restart is required.
   *   <li>{@code Optional.empty()}: The target is not directly incompatible. Analysis can continue.
   *   <li>{@code !Optional.empty()}: The target is directly incompatible. Analysis should not
   *       continue.
   * </ul>
   */
  @Nullable
  public static Optional<RuleConfiguredTargetValue> createDirectlyIncompatibleTarget(
      TargetAndConfiguration targetAndConfiguration,
      ConfigConditions configConditions,
      Environment env,
      @Nullable PlatformInfo platformInfo,
      NestedSetBuilder<Package> transitivePackages)
      throws InterruptedException {
    Target target = targetAndConfiguration.getTarget();
    Rule rule = target.getAssociatedRule();

    if (rule == null || rule.getRuleClass().equals("toolchain") || platformInfo == null) {
      return Optional.empty();
    }

    // Retrieve the label list for the target_compatible_with attribute.
    BuildConfigurationValue configuration = targetAndConfiguration.getConfiguration();
    ConfiguredAttributeMapper attrs =
        ConfiguredAttributeMapper.of(rule, configConditions.asProviders(), configuration);
    if (!attrs.has("target_compatible_with", BuildType.LABEL_LIST)) {
      return Optional.empty();
    }

    // Resolve the constraint labels.
    List<Label> labels = attrs.get("target_compatible_with", BuildType.LABEL_LIST);
    ImmutableList<ConfiguredTargetKey> constraintKeys =
        labels.stream()
            .map(
                label ->
                    Dependency.builder()
                        .setLabel(label)
                        .setConfiguration(configuration)
                        .build()
                        .getConfiguredTargetKey())
            .collect(toImmutableList());
    SkyframeLookupResult constraintValues = env.getValuesAndExceptions(constraintKeys);
    if (env.valuesMissing()) {
      return null;
    }

    // Find the constraints that don't satisfy the target platform.
    ImmutableList<ConstraintValueInfo> invalidConstraintValues =
        constraintKeys.stream()
            .map(key -> (ConfiguredTargetValue) constraintValues.get(key))
            .filter(notNull())
            .map(ctv -> PlatformProviderUtils.constraintValue(ctv.getConfiguredTarget()))
            .filter(notNull())
            .filter(cv -> !platformInfo.constraints().hasConstraintValue(cv))
            .collect(toImmutableList());
    if (invalidConstraintValues.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        createIncompatibleRuleConfiguredTarget(
            target,
            configuration,
            configConditions,
            IncompatiblePlatformProvider.incompatibleDueToConstraints(
                platformInfo.label(), invalidConstraintValues),
            rule.getRuleClass(),
            transitivePackages));
  }

  /**
   * Creates an incompatible target if it is "indirectly incompatible".
   *
   * <p>In other words, this function checks if a target is incompatible because of one of its
   * dependencies. If a dependency is incompatible, then this target is also incompatible.
   *
   * <p>This function returns an {@code Optional} of a {@link RuleConfiguredTargetValue}. This
   * provides two states of return values:
   *
   * <ul>
   *   <li>{@code Optional.empty()}: The target is not indirectly incompatible. Analysis can
   *       continue.
   *   <li>{@code !Optional.empty()}: The target is indirectly incompatible. Analysis should not
   *       continue.
   * </ul>
   */
  public static Optional<RuleConfiguredTargetValue> createIndirectlyIncompatibleTarget(
      TargetAndConfiguration targetAndConfiguration,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap,
      ConfigConditions configConditions,
      @Nullable PlatformInfo platformInfo,
      NestedSetBuilder<Package> transitivePackages) {
    Target target = targetAndConfiguration.getTarget();
    Rule rule = target.getAssociatedRule();

    if (rule == null || rule.getRuleClass().equals("toolchain")) {
      return Optional.empty();
    }

    // Find all the incompatible dependencies.
    ImmutableList<ConfiguredTarget> incompatibleDeps =
        depValueMap.values().stream()
            .map(ConfiguredTargetAndData::getConfiguredTarget)
            .filter(
                dep -> RuleContextConstraintSemantics.checkForIncompatibility(dep).isIncompatible())
            .collect(toImmutableList());
    if (incompatibleDeps.isEmpty()) {
      return Optional.empty();
    }

    BuildConfigurationValue configuration = targetAndConfiguration.getConfiguration();
    Label platformLabel = platformInfo != null ? platformInfo.label() : null;
    return Optional.of(
        createIncompatibleRuleConfiguredTarget(
            target,
            configuration,
            configConditions,
            IncompatiblePlatformProvider.incompatibleDueToTargets(platformLabel, incompatibleDeps),
            rule.getRuleClass(),
            transitivePackages));
  }

  /** Creates an incompatible target. */
  private static RuleConfiguredTargetValue createIncompatibleRuleConfiguredTarget(
      Target target,
      BuildConfigurationValue configuration,
      ConfigConditions configConditions,
      IncompatiblePlatformProvider incompatiblePlatformProvider,
      String ruleClassString,
      NestedSetBuilder<Package> transitivePackages) {
    // Create dummy instances of the necessary data for a configured target. None of this data will
    // actually be used because actions associated with incompatible targets must not be evaluated.
    NestedSet<Artifact> filesToBuild = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    FileProvider fileProvider = new FileProvider(filesToBuild);
    FilesToRunProvider filesToRunProvider = new FilesToRunProvider(filesToBuild, null, null);

    TransitiveInfoProviderMapBuilder providerBuilder =
        new TransitiveInfoProviderMapBuilder()
            .put(incompatiblePlatformProvider)
            .add(RunfilesProvider.simple(Runfiles.EMPTY))
            .add(fileProvider)
            .add(filesToRunProvider);
    if (configuration.hasFragment(TestConfiguration.class)) {
      // Create a dummy TestProvider instance so that other parts of the code base stay happy. Even
      // though this test will never execute, some code still expects the provider.
      TestProvider.TestParams testParams = TestActionBuilder.createEmptyTestParams();
      providerBuilder.put(TestProvider.class, new TestProvider(testParams));
    }

    RuleConfiguredTarget configuredTarget =
        new RuleConfiguredTarget(
            target.getLabel(),
            configuration.getKey(),
            convertVisibility(),
            providerBuilder.build(),
            configConditions.asProviders(),
            ruleClassString);
    return new RuleConfiguredTargetValue(
        configuredTarget, transitivePackages == null ? null : transitivePackages.build());
  }

  /**
   * Generates visibility for an incompatible target.
   *
   * <p>The intent is for this function is to match ConfiguredTargetFactory.convertVisibility().
   * Since visibility is currently validated after incompatibility is evaluated, however, it doesn't
   * matter what visibility we set here. To keep it simple, we pretend that all incompatible targets
   * are public.
   *
   * <p>TODO(#16044): Set up properly validated visibility here.
   */
  private static NestedSet<PackageGroupContents> convertVisibility() {
    return NestedSetBuilder.create(
        Order.STABLE_ORDER,
        PackageGroupContents.create(ImmutableList.of(PackageSpecification.everything())));
  }
}
