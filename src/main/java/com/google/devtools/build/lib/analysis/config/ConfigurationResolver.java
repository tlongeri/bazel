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

package com.google.devtools.build.lib.analysis.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.analysis.ConfigurationsCollector;
import com.google.devtools.build.lib.analysis.ConfigurationsResult;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.DependencyKey;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.config.transitions.ComposingTransition;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NullTransition;
import com.google.devtools.build.lib.analysis.config.transitions.SplitTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionUtil;
import com.google.devtools.build.lib.analysis.starlark.FunctionTransitionUtil;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.PlatformMappingValue;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.ValueOrException;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Turns configuration transition requests into actual configurations.
 *
 * <p>This involves:
 *
 * <ol>
 *   <li>Patching a source configuration's options with the transition.
 *   <li>Getting the destination configuration from Skyframe.
 * </ol>
 *
 * <p>For the work of determining the transition requests themselves, see {@link
 * TransitionResolver}.
 */
public final class ConfigurationResolver {

  /**
   * Determines the output ordering of each {@code <attribute, depLabel> -> [dep<config1>,
   * dep<config2>, ...]} collection produced by a split transition.
   */
  @VisibleForTesting
  public static final Comparator<Dependency> SPLIT_DEP_ORDERING =
      Comparator.comparing(
              Functions.compose(BuildConfigurationValue::getMnemonic, Dependency::getConfiguration))
          .thenComparing(
              Functions.compose(BuildConfigurationValue::checksum, Dependency::getConfiguration));

  private final SkyFunction.Environment env;
  private final TargetAndConfiguration ctgValue;
  private final BuildConfigurationValue hostConfiguration;
  private final ImmutableMap<Label, ConfigMatchingProvider> configConditions;

  /** The key for {@link #starlarkTransitionCache}. */
  private static class StarlarkTransitionCacheKey {
    private final ConfigurationTransition transition;
    private final BuildOptions fromOptions;
    private final int hashCode;

    StarlarkTransitionCacheKey(ConfigurationTransition transition, BuildOptions fromOptions) {
      // For rule self-transitions, the transition instance encapsulates both the transition logic
      // and attributes of the target it's attached to. This is important: the same transition in
      // the same configuration applied to distinct targets may produce different outputs. See
      // StarlarkRuleTransitionProvider.FunctionPatchTransition for details.
      // TODO(bazel-team): the transition code (i.e. StarlarkTransitionFunction) hashes on identity.
      // Check that unnecessary copies of the transition function don't dilute this cache. Quick
      // experimentation shows the # of such instances is very small. But it's unclear how strong
      // of an interning contract there is.
      this.transition = transition;
      this.fromOptions = fromOptions;
      this.hashCode = Objects.hash(transition, fromOptions);
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (!(other instanceof StarlarkTransitionCacheKey)) {
        return false;
      }
      return (this.transition.equals(((StarlarkTransitionCacheKey) other).transition)
          && this.fromOptions.equals(((StarlarkTransitionCacheKey) other).fromOptions));
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  /** The result of a {@link #starlarkTransitionCache} lookup. */
  private static class StarlarkTransitionCacheValue {
    final Map<String, BuildOptions> result;
    /**
     * Stores events for successful transitions. Transitions that fail aren't added to the cache.
     * This is meant for non-error events like Starlark {@code print()} output. See {@link
     * StarlarkIntegrationTest#testPrintFromTransitionImpl} for a test that covers this.
     *
     * <p>This is null if the transition lacks non-error events.
     */
    @Nullable final StoredEventHandler nonErrorEvents;

    StarlarkTransitionCacheValue(
        Map<String, BuildOptions> result, @Nullable StoredEventHandler nonErrorEvents) {
      this.result = result;
      this.nonErrorEvents = nonErrorEvents;
    }
  }

  /**
   * Caches the application of transitions that use Starlark.
   *
   * <p>This trivially includes {@link StarlarkTransition}s. But it also includes transitions that
   * delegate to {@link StarlarkTransition}s, like some {@link ComposingTransition}s.
   *
   * <p>This cache was added to keep builds that heavily rely on Starlark transitions performant.
   * The inspiring build is a large Apple binary that heavily relies on {@code objc_library.bzl},
   * which applies a self-transition. The build applies this transition ~600,000 times. Each
   * application has a cost, mostly from setup in translating Java objects to Starlark objects in
   * {@link FunctionTransitionUtil#applyAndValidate}. This cache saves most of that work, reducing
   * analysis phase CPU time by 17%.
   */
  private static final Cache<StarlarkTransitionCacheKey, StarlarkTransitionCacheValue>
      starlarkTransitionCache = Caffeine.newBuilder().softValues().build();

  public ConfigurationResolver(
      SkyFunction.Environment env,
      TargetAndConfiguration ctgValue,
      BuildConfigurationValue hostConfiguration,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions) {
    this.env = env;
    this.ctgValue = ctgValue;
    this.hostConfiguration = hostConfiguration;
    this.configConditions = configConditions;
  }

  private BuildConfigurationValue getCurrentConfiguration() {
    return ctgValue.getConfiguration();
  }

  /**
   * Translates a set of {@link DependencyKey} objects with configuration transition requests to the
   * same objects with resolved configurations.
   *
   * <p>This method must preserve the original label ordering of each attribute. For example, if
   * {@code dependencyKeys.get("data")} is {@code [":a", ":b"]}, the resolved variant must also be
   * {@code [":a", ":b"]} in the same order.
   *
   * <p>For split transitions, {@code dependencyKeys.get("data") = [":a", ":b"]} can produce the
   * output {@code [":a"<config1>, ":a"<config2>, ..., ":b"<config1>, ":b"<config2>, ...]}. All
   * instances of ":a" still appear before all instances of ":b". But the {@code [":a"<config1>,
   * ":a"<config2>"]} subset may be in any (deterministic) order. In particular, this may not be the
   * same order as {@link SplitTransition#split}. If needed, this code can be modified to use that
   * order, but that involves more runtime in performance-critical code, so we won't make that
   * change without a clear need.
   *
   * <p>These configurations unconditionally include all fragments.
   *
   * <p>This method is heavily performance-optimized. Because {@link
   * com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction} calls it over every edge in
   * the configured target graph, small inefficiencies can have observable impact on analysis time.
   * Keep this in mind when making modifications and performance-test any changes you make.
   *
   * @param dependencyKeys the transition requests for each dep and each dependency kind
   * @return a mapping from each dependency kind in the source target to the {@link
   *     BuildConfigurationValue}s and {@link Label}s for the deps under that dependency kind .
   *     Returns null if not all Skyframe dependencies are available.
   */
  @Nullable
  public OrderedSetMultimap<DependencyKind, Dependency> resolveConfigurations(
      OrderedSetMultimap<DependencyKind, DependencyKey> dependencyKeys)
      throws ConfiguredValueCreationException, InterruptedException {
    OrderedSetMultimap<DependencyKind, Dependency> resolvedDeps = OrderedSetMultimap.create();
    boolean needConfigsFromSkyframe = false;
    for (Map.Entry<DependencyKind, DependencyKey> entry : dependencyKeys.entries()) {
      DependencyKind dependencyKind = entry.getKey();
      DependencyKey dependencyKey = entry.getValue();
      ImmutableList<Dependency> depConfig = resolveConfiguration(dependencyKind, dependencyKey);
      if (depConfig == null) {
        // Instead of returning immediately, give the loop a chance to queue up every missing
        // dependency, then return all at once. That prevents re-executing this code an unnecessary
        // number of times. i.e. this is equivalent to calling env.getValues() once over all deps.
        needConfigsFromSkyframe = true;
      } else {
        resolvedDeps.putAll(dependencyKind, depConfig);
      }
    }
    return needConfigsFromSkyframe ? null : resolvedDeps;
  }

  /**
   * Translates a {@link DependencyKey} with configuration transition to the same objects with
   * resolved configurations.
   *
   * <p>This is the single-argument version of {@link #resolveConfigurations}, whose documentation
   * has more details.
   */
  @Nullable
  public ImmutableList<Dependency> resolveConfiguration(
      DependencyKind dependencyKind, DependencyKey dependencyKey)
      throws ConfiguredValueCreationException, InterruptedException {

    Dependency.Builder dependencyBuilder = dependencyKey.getDependencyBuilder();

    ConfigurationTransition transition = dependencyKey.getTransition();

    if (transition == NullTransition.INSTANCE) {
      Dependency resolvedDep = resolveNullTransition(dependencyBuilder, dependencyKind);
      if (resolvedDep == null) {
        return null; // Need Skyframe deps.
      }
      return ImmutableList.of(resolvedDep);
    } else if (transition.isHostTransition()) {
      return ImmutableList.of(resolveHostTransition(dependencyBuilder, dependencyKey));
    }

    return resolveGenericTransition(dependencyBuilder, dependencyKey);
  }

  @Nullable
  private Dependency resolveNullTransition(
      Dependency.Builder dependencyBuilder, DependencyKind dependencyKind)
      throws ConfiguredValueCreationException, InterruptedException {
    // The null configuration can be trivially computed (it's, well, null), so special-case that
    // transition here and skip the rest of the logic. A *lot* of targets have null deps, so
    // this produces real savings. Profiling tests over a simple cc_binary show this saves ~1% of
    // total analysis phase time.
    if (dependencyKind.getAttribute() != null) {
      ImmutableList<String> transitionKeys = collectTransitionKeys(dependencyKind.getAttribute());
      if (transitionKeys == null) {
        return null; // Need Skyframe deps.
      }
      dependencyBuilder.setTransitionKeys(transitionKeys);
    }

    return dependencyBuilder.withNullConfiguration().build();
  }

  private Dependency resolveHostTransition(
      Dependency.Builder dependencyBuilder, DependencyKey dependencyKey) {
    return dependencyBuilder
        .setConfiguration(hostConfiguration)
        .setAspects(dependencyKey.getAspects())
        .build();
  }

  @Nullable
  private ImmutableList<Dependency> resolveGenericTransition(
      Dependency.Builder dependencyBuilder,
      DependencyKey dependencyKey)
      throws ConfiguredValueCreationException, InterruptedException {
    Map<String, BuildOptions> toOptions;
    try {
      toOptions =
          applyTransitionWithSkyframe(
              getCurrentConfiguration().getOptions(),
              dependencyKey.getTransition(),
              env,
              env.getListener());
      if (toOptions == null) {
        return null; // Need more Skyframe deps for a Starlark transition.
      }
    } catch (TransitionException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    }

    if (SplitTransition.equals(getCurrentConfiguration().getOptions(), toOptions.values())) {
      // The dep uses the same exact configuration. Let's re-use the current configuration and
      // skip adding a Skyframe dependency edge on it.
      return ImmutableList.of(
          dependencyBuilder
              .setConfiguration(getCurrentConfiguration())
              .setAspects(dependencyKey.getAspects())
              // Explicitly do not set the transition key, since there is only one configuration
              // and it matches the current one. This ignores the transition key set if this
              // was a split transition.
              .build());
    }

    PathFragment platformMappingPath =
        getCurrentConfiguration().getOptions().get(PlatformOptions.class).platformMappings;
    PlatformMappingValue platformMappingValue =
        (PlatformMappingValue) env.getValue(PlatformMappingValue.Key.create(platformMappingPath));
    if (platformMappingValue == null) {
      return null; // Need platform mappings from Skyframe.
    }

    Map<String, BuildConfigurationKey> configurationKeys = new HashMap<>();
    try {
      for (Map.Entry<String, BuildOptions> optionsEntry : toOptions.entrySet()) {
        String transitionKey = optionsEntry.getKey();
        BuildConfigurationKey buildConfigurationKey =
            BuildConfigurationKey.withPlatformMapping(
                platformMappingValue, optionsEntry.getValue());
        configurationKeys.put(transitionKey, buildConfigurationKey);
      }
    } catch (OptionsParsingException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    }

    Map<SkyKey, ValueOrException<InvalidConfigurationException>> depConfigValues =
        env.getValuesOrThrow(configurationKeys.values(), InvalidConfigurationException.class);
    List<Dependency> dependencies = new ArrayList<>();
    try {
      for (Map.Entry<String, BuildConfigurationKey> entry : configurationKeys.entrySet()) {
        String transitionKey = entry.getKey();
        ValueOrException<InvalidConfigurationException> valueOrException =
            depConfigValues.get(entry.getValue());
        if (valueOrException.get() == null) {
          continue;
        }
        // TODO(blaze-configurability-team): Should be able to just use BuildConfigurationKey
        BuildConfigurationValue configuration = (BuildConfigurationValue) valueOrException.get();
        if (configuration != null) {
          Dependency resolvedDep =
              dependencyBuilder
                  // Copy the builder so we don't overwrite the other dependencies.
                  .copy()
                  .setConfiguration(configuration)
                  .setAspects(dependencyKey.getAspects())
                  .setTransitionKey(transitionKey)
                  .build();
          dependencies.add(resolvedDep);
        }
      }
      if (env.valuesMissing()) {
        return null; // Need dependency configurations.
      }
    } catch (InvalidConfigurationException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    }

    return ImmutableList.sortedCopyOf(SPLIT_DEP_ORDERING, dependencies);
  }

  @Nullable
  private ImmutableList<String> collectTransitionKeys(Attribute attribute)
      throws ConfiguredValueCreationException, InterruptedException {
    TransitionFactory<AttributeTransitionData> transitionFactory = attribute.getTransitionFactory();
    if (transitionFactory.isSplit()) {
      AttributeTransitionData transitionData =
          AttributeTransitionData.builder()
              .attributes(
                  ConfiguredAttributeMapper.of(
                      ctgValue.getTarget().getAssociatedRule(),
                      configConditions,
                      ctgValue.getConfiguration()))
              .build();
      ConfigurationTransition baseTransition = transitionFactory.create(transitionData);
      Map<String, BuildOptions> toOptions;
      try {
        toOptions =
            applyTransitionWithSkyframe(
                getCurrentConfiguration().getOptions(), baseTransition, env, env.getListener());
        if (toOptions == null) {
          return null; // Need more Skyframe deps for a Starlark transition.
        }
      } catch (TransitionException e) {
        throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
      }
      if (!SplitTransition.equals(getCurrentConfiguration().getOptions(), toOptions.values())) {
        return ImmutableList.copyOf(toOptions.keySet());
      }
    }

    return ImmutableList.of();
  }

  /**
   * Applies a configuration transition over a set of build options.
   *
   * <p>This is only for callers that can't use {@link #applyTransitionWithSkyframe}. The difference
   * is {@link #applyTransitionWithSkyframe} internally computes {@code buildSettingPackages} with
   * Skyframe, while this version requires it as a precomputed input.
   *
   * <p>prework - load all default values for reading build settings in Starlark transitions (by
   * design, {@link BuildOptions} never holds default values of build settings)
   *
   * <p>postwork - replay events/throw errors from transition implementation function and validate
   * the outputs of the transition. This only applies to Starlark transitions.
   *
   * @return the build options for the transitioned configuration.
   */
  public static Map<String, BuildOptions> applyTransitionWithoutSkyframe(
      BuildOptions fromOptions,
      ConfigurationTransition transition,
      Map<PackageValue.Key, PackageValue> buildSettingPackages,
      ExtendedEventHandler eventHandler)
      throws TransitionException, InterruptedException {
    if (StarlarkTransition.doesStarlarkTransition(transition)) {
      return applyStarlarkTransition(fromOptions, transition, buildSettingPackages, eventHandler);
    }
    return transition.apply(TransitionUtil.restrict(transition, fromOptions), eventHandler);
  }

  /**
   * Applies a configuration transition over a set of build options.
   *
   * <p>Callers should use this over {@link #applyTransitionWithoutSkyframe}. Unlike that variation,
   * this would may return null if it needs more Skyframe deps.
   *
   * <p>postwork - replay events/throw errors from transition implementation function and validate
   * the outputs of the transition. This only applies to Starlark transitions.
   *
   * @return the build options for the transitioned configuration, or null if Skyframe dependencies
   *     for build_setting default values for Starlark transitions. These can be read from their
   *     respective packages.
   */
  @Nullable
  public static Map<String, BuildOptions> applyTransitionWithSkyframe(
      BuildOptions fromOptions,
      ConfigurationTransition transition,
      SkyFunction.Environment env,
      ExtendedEventHandler eventHandler)
      throws TransitionException, InterruptedException {
    if (StarlarkTransition.doesStarlarkTransition(transition)) {
      // TODO(blaze-team): find a way to dedupe this with SkyframeExecutor.getBuildSettingPackages.
      Map<PackageValue.Key, PackageValue> buildSettingPackages =
          StarlarkTransition.getBuildSettingPackages(env, transition);
      return buildSettingPackages == null
          ? null
          : applyStarlarkTransition(fromOptions, transition, buildSettingPackages, eventHandler);
    }
    return transition.apply(TransitionUtil.restrict(transition, fromOptions), eventHandler);
  }

  /**
   * Applies a Starlark transition.
   *
   * @param fromOptions source options before the transition
   * @param transition the transition itself
   * @param buildSettingPackages packages for build_settings read by the transition. This is used to
   *     read default values for build_settings that aren't explicitly set on the build.
   * @param eventHandler handler for errors evaluating the transition.
   * @return transition output
   */
  private static Map<String, BuildOptions> applyStarlarkTransition(
      BuildOptions fromOptions,
      ConfigurationTransition transition,
      Map<PackageValue.Key, PackageValue> buildSettingPackages,
      ExtendedEventHandler eventHandler)
      throws TransitionException, InterruptedException {
    StarlarkTransitionCacheKey cacheKey = new StarlarkTransitionCacheKey(transition, fromOptions);
    StarlarkTransitionCacheValue cachedResult = starlarkTransitionCache.getIfPresent(cacheKey);
    if (cachedResult != null) {
      if (cachedResult.nonErrorEvents != null) {
        cachedResult.nonErrorEvents.replayOn(eventHandler);
      }
      return cachedResult.result;
    }
    BuildOptions adjustedOptions =
        StarlarkTransition.addDefaultStarlarkOptions(fromOptions, transition, buildSettingPackages);
    // TODO(bazel-team): Add safety-check that this never mutates fromOptions.
    StoredEventHandler handlerWithErrorStatus = new StoredEventHandler();
    Map<String, BuildOptions> result =
        transition.apply(
            TransitionUtil.restrict(transition, adjustedOptions), handlerWithErrorStatus);

    // We use a temporary StoredEventHandler instead of the caller's event handler because
    // StarlarkTransition.validate assumes no errors occurred. We need a StoredEventHandler to be
    // able to check that, and fail out early if there are errors.
    //
    // TODO(bazel-team): harden StarlarkTransition.validate so we can eliminate this step.
    // StarlarkRuleTransitionProviderTest#testAliasedBuildSetting_outputReturnMismatch shows the
    // effect.
    handlerWithErrorStatus.replayOn(eventHandler);
    if (handlerWithErrorStatus.hasErrors()) {
      throw new TransitionException("Errors encountered while applying Starlark transition");
    }
    result = StarlarkTransition.validate(transition, buildSettingPackages, result);
    // If the transition errored (like bad Starlark code), this method already exited with an
    // exception so the results won't go into the cache. We still want to collect non-error events
    // like print() output.
    StoredEventHandler nonErrorEvents =
        !handlerWithErrorStatus.isEmpty() ? handlerWithErrorStatus : null;
    starlarkTransitionCache.put(cacheKey, new StarlarkTransitionCacheValue(result, nonErrorEvents));
    return result;
  }

  /**
   * This method allows resolution of configurations outside of a skyfunction call.
   *
   * <p>Unlike {@link #resolveConfigurations}, this doesn't expect the current context to be
   * evaluating dependencies of a parent target. So this method is also suitable for top-level
   * targets.
   *
   * <p>Resolution consists of applying the per-target transitions specified in {@code
   * targetsToEvaluate}. This can be used, e.g., to apply {@link
   * com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory}s over global
   * top-level configurations.
   *
   * <p>Preserves the original input order (but merges duplicate nodes that might occur due to
   * top-level configuration transitions) . Uses original (untrimmed, pre-transition) configurations
   * for targets that can't be evaluated (e.g. due to loading phase errors).
   *
   * <p>This is suitable for feeding {@link
   * com.google.devtools.build.lib.analysis.ConfiguredTargetValue} keys: as general principle {@link
   * com.google.devtools.build.lib.analysis.ConfiguredTarget}s should have exactly as much
   * information in their configurations as they need to evaluate and no more (e.g. there's no need
   * for Android settings in a C++ configured target).
   *
   * @param defaultContext the original targets and starting configurations before applying rule
   *     transitions and trimming. When actual configurations can't be evaluated, these values are
   *     returned as defaults. See TODO below.
   * @param targetsToEvaluate the inputs repackaged as dependencies, including rule-specific
   *     transitions
   * @param eventHandler the error event handler
   * @param configurationsCollector the collector which finds configurations for dependencies
   */
  // TODO(bazel-team): error out early for targets that fail - failed configuration evaluations
  //   should never make it through analysis (and especially not seed ConfiguredTargetValues)
  // TODO(gregce): merge this more with resolveConfigurations? One crucial difference is
  //   resolveConfigurations can null-return on missing deps since it executes inside Skyfunctions.
  // Keep this in sync with {@link PrepareAnalysisPhaseFunction#resolveConfigurations}.
  public static TopLevelTargetsAndConfigsResult getConfigurationsFromExecutor(
      Iterable<TargetAndConfiguration> defaultContext,
      Multimap<BuildConfigurationValue, DependencyKey> targetsToEvaluate,
      ExtendedEventHandler eventHandler,
      ConfigurationsCollector configurationsCollector)
      throws InvalidConfigurationException, InterruptedException {

    Map<Label, Target> labelsToTargets = new HashMap<>();
    for (TargetAndConfiguration targetAndConfig : defaultContext) {
      labelsToTargets.put(targetAndConfig.getLabel(), targetAndConfig.getTarget());
    }

    // Maps <target, originalConfig> pairs to <target, finalConfig> pairs for targets that
    // could be successfully Skyframe-evaluated.
    Map<TargetAndConfiguration, TargetAndConfiguration> successfullyEvaluatedTargets =
        new LinkedHashMap<>();
    boolean hasError = false;
    if (!targetsToEvaluate.isEmpty()) {
      for (BuildConfigurationValue fromConfig : targetsToEvaluate.keySet()) {
        ConfigurationsResult configurationsResult =
            configurationsCollector.getConfigurations(
                eventHandler, fromConfig.getOptions(), targetsToEvaluate.get(fromConfig));
        hasError |= configurationsResult.hasError();
        for (Map.Entry<DependencyKey, BuildConfigurationValue> evaluatedTarget :
            configurationsResult.getConfigurationMap().entries()) {
          Target target = labelsToTargets.get(evaluatedTarget.getKey().getLabel());
          successfullyEvaluatedTargets.put(
              new TargetAndConfiguration(target, fromConfig),
              new TargetAndConfiguration(target, evaluatedTarget.getValue()));
        }
      }
    }

    LinkedHashSet<TargetAndConfiguration> result = new LinkedHashSet<>();
    for (TargetAndConfiguration originalInput : defaultContext) {
      // If the configuration couldn't be determined (e.g. loading phase error), use the original.
      result.add(successfullyEvaluatedTargets.getOrDefault(originalInput, originalInput));
    }
    return new TopLevelTargetsAndConfigsResult(result, hasError);
  }

  /**
   * The result of {@link #getConfigurationsFromExecutor} which also registers if an error was
   * recorded.
   */
  public static class TopLevelTargetsAndConfigsResult {
    private final Collection<TargetAndConfiguration> configurations;
    private final boolean hasError;

    public TopLevelTargetsAndConfigsResult(
        Collection<TargetAndConfiguration> configurations, boolean hasError) {
      this.configurations = configurations;
      this.hasError = hasError;
    }

    public boolean hasError() {
      return hasError;
    }

    public Collection<TargetAndConfiguration> getTargetsAndConfigs() {
      return configurations;
    }
  }
}
