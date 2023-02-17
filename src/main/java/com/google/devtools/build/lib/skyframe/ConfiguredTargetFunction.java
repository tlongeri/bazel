// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.AnalysisRootCauseEvent;
import com.google.devtools.build.lib.analysis.AspectResolver;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment.MissingDepException;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.DependencyKey;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DependencyResolver;
import com.google.devtools.build.lib.analysis.DuplicateException;
import com.google.devtools.build.lib.analysis.EmptyConfiguredTarget;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.ExecGroupCollection.InvalidExecGroupException;
import com.google.devtools.build.lib.analysis.InconsistentAspectOrderException;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.ResolvedToolchainContext;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.ToolchainContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.ConfigurationResolver;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LoadingFailedCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.BuildViewProvider;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException;
import com.google.devtools.build.skyframe.ValueOrUntypedException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * SkyFunction for {@link ConfiguredTargetValue}s.
 *
 * <p>This class, together with {@link AspectFunction} drives the analysis phase. For more
 * information, see {@link com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory}.
 *
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 */
public final class ConfiguredTargetFunction implements SkyFunction {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * Attempt to find a {@link ConfiguredValueCreationException} in a {@link ToolchainException}, or
   * its causes.
   *
   * <p>If one cannot be found, null is returned.
   */
  @Nullable
  public static ConfiguredValueCreationException asConfiguredValueCreationException(
      ToolchainException e) {
    for (Throwable cause = e.getCause();
        cause != null && cause != cause.getCause();
        cause = cause.getCause()) {
      if (cause instanceof ConfiguredValueCreationException) {
        return (ConfiguredValueCreationException) cause;
      }
    }
    return null;
  }

  private final BuildViewProvider buildViewProvider;
  private final RuleClassProvider ruleClassProvider;
  // TODO(b/185987566): Remove this semaphore.
  private final AtomicReference<Semaphore> cpuBoundSemaphore;
  @Nullable private final ConfiguredTargetProgressReceiver configuredTargetProgress;

  /**
   * Indicates whether the set of packages transitively loaded for a given {@link
   * ConfiguredTargetValue} will be needed for package root resolution later in the build. If not,
   * they are not collected and stored.
   */
  private final boolean storeTransitivePackagesForPackageRootResolution;

  private final boolean shouldUnblockCpuWorkWhenFetchingDeps;

  ConfiguredTargetFunction(
      BuildViewProvider buildViewProvider,
      RuleClassProvider ruleClassProvider,
      AtomicReference<Semaphore> cpuBoundSemaphore,
      boolean storeTransitivePackagesForPackageRootResolution,
      boolean shouldUnblockCpuWorkWhenFetchingDeps,
      @Nullable ConfiguredTargetProgressReceiver configuredTargetProgress) {
    this.buildViewProvider = buildViewProvider;
    this.ruleClassProvider = ruleClassProvider;
    this.cpuBoundSemaphore = cpuBoundSemaphore;
    this.storeTransitivePackagesForPackageRootResolution =
        storeTransitivePackagesForPackageRootResolution;
    this.shouldUnblockCpuWorkWhenFetchingDeps = shouldUnblockCpuWorkWhenFetchingDeps;
    this.configuredTargetProgress = configuredTargetProgress;
  }

  private void maybeAcquireSemaphoreWithLogging(SkyKey key) throws InterruptedException {
    if (cpuBoundSemaphore.get() == null) {
      return;
    }
    Stopwatch stopwatch = Stopwatch.createStarted();
    cpuBoundSemaphore.get().acquire();
    long elapsedTime = stopwatch.elapsed().toMillis();
    if (elapsedTime > 5) {
      logger.atInfo().atMostEvery(10, TimeUnit.SECONDS).log(
          "Spent %s milliseconds waiting for lock acquisition for %s", elapsedTime, key);
    }
  }

  private void maybeReleaseSemaphore() {
    if (cpuBoundSemaphore.get() != null) {
      cpuBoundSemaphore.get().release();
    }
  }

  @Override
  public SkyValue compute(SkyKey key, Environment env)
      throws ReportedException, UnreportedException, InterruptedException {
    if (shouldUnblockCpuWorkWhenFetchingDeps) {
      env =
          new StateInformingSkyFunctionEnvironment(
              env,
              /*preFetch=*/ this::maybeReleaseSemaphore,
              /*postFetch=*/ () -> maybeAcquireSemaphoreWithLogging(key));
    }
    SkyframeBuildView view = buildViewProvider.getSkyframeBuildView();
    NestedSetBuilder<Package> transitivePackagesForPackageRootResolution =
        storeTransitivePackagesForPackageRootResolution ? NestedSetBuilder.stableOrder() : null;
    NestedSetBuilder<Cause> transitiveRootCauses = NestedSetBuilder.stableOrder();

    ConfiguredTargetKey configuredTargetKey = (ConfiguredTargetKey) key.argument();
    Label label = configuredTargetKey.getLabel();
    BuildConfigurationValue configuration = null;
    ImmutableSet<SkyKey> packageAndMaybeConfiguration;
    SkyKey packageKey = PackageValue.key(label.getPackageIdentifier());
    SkyKey configurationKeyMaybe = configuredTargetKey.getConfigurationKey();
    if (configurationKeyMaybe == null) {
      packageAndMaybeConfiguration = ImmutableSet.of(packageKey);
    } else {
      packageAndMaybeConfiguration = ImmutableSet.of(packageKey, configurationKeyMaybe);
    }
    Map<SkyKey, SkyValue> packageAndMaybeConfigurationValues =
        env.getValues(packageAndMaybeConfiguration);
    if (env.valuesMissing()) {
      return null;
    }
    PackageValue packageValue = (PackageValue) packageAndMaybeConfigurationValues.get(packageKey);
    if (configurationKeyMaybe != null) {
      configuration =
          (BuildConfigurationValue) packageAndMaybeConfigurationValues.get(configurationKeyMaybe);
    }

    // TODO(ulfjack): This tries to match the logic in TransitiveTargetFunction /
    // TargetMarkerFunction. Maybe we can merge the two?
    Package pkg = packageValue.getPackage();
    Target target;
    try {
      target = pkg.getTarget(label.getName());
    } catch (NoSuchTargetException e) {
      if (!e.getMessage().isEmpty()) {
        env.getListener().handle(Event.error(pkg.getBuildFile().getLocation(), e.getMessage()));
      }
      throw new ReportedException(
          new ConfiguredValueCreationException(
              pkg.getBuildFile().getLocation(),
              e.getMessage(),
              label,
              configuration.getEventId(),
              null,
              e.getDetailedExitCode()));
    }
    if (pkg.containsErrors()) {
      FailureDetail failureDetail = pkg.contextualizeFailureDetailForTarget(target);
      transitiveRootCauses.add(new LoadingFailedCause(label, DetailedExitCode.of(failureDetail)));
    }
    if (transitivePackagesForPackageRootResolution != null) {
      transitivePackagesForPackageRootResolution.add(pkg);
    }
    if (target.isConfigurable() == (configuredTargetKey.getConfigurationKey() == null)) {
      // We somehow ended up in a target that requires a non-null configuration as a dependency of
      // one that requires a null configuration or the other way round. This is always an error, but
      // we need to analyze the dependencies of the latter target to realize that. Short-circuit the
      // evaluation to avoid doing useless work and running code with a null configuration that's
      // not prepared for it.
      return new NonRuleConfiguredTargetValue(
          new EmptyConfiguredTarget(target.getLabel(), configuredTargetKey.getConfigurationKey()),
          transitivePackagesForPackageRootResolution == null
              ? null
              : transitivePackagesForPackageRootResolution.build());
    }

    TargetAndConfiguration ctgValue = new TargetAndConfiguration(target, configuration);

    SkyframeDependencyResolver resolver = new SkyframeDependencyResolver(env);

    ToolchainCollection<UnloadedToolchainContext> unloadedToolchainContexts = null;
    ExecGroupCollection.Builder execGroupCollectionBuilder = null;

    // TODO(janakr): this call may tie up this thread indefinitely, reducing the parallelism of
    //  Skyframe. This is a strict improvement over the prior state of the code, in which we ran
    //  with #processors threads, but ideally we would call #tryAcquire here, and if we failed,
    //  would exit this SkyFunction and restart it when permits were available.
    maybeAcquireSemaphoreWithLogging(key);
    try {
      // Determine what toolchains are needed by this target.
      ComputedToolchainContexts result =
          computeUnloadedToolchainContexts(
              env, ruleClassProvider, ctgValue, configuredTargetKey.getExecutionPlatformLabel());
      if (env.valuesMissing()) {
        return null;
      }
      unloadedToolchainContexts = result.toolchainCollection;
      execGroupCollectionBuilder = result.execGroupCollectionBuilder;

      // Get the configuration targets that trigger this rule's configurable attributes.
      ConfigConditions configConditions =
          getConfigConditions(
              env,
              ctgValue,
              transitivePackagesForPackageRootResolution,
              unloadedToolchainContexts == null
                  ? null
                  : unloadedToolchainContexts.getTargetPlatform(),
              transitiveRootCauses);
      if (env.valuesMissing()) {
        return null;
      }
      // TODO(ulfjack): ConfiguredAttributeMapper (indirectly used from computeDependencies) isn't
      // safe to use if there are missing config conditions, so we stop here, but only if there are
      // config conditions - though note that we can't check if configConditions is non-empty - it
      // may be empty for other reasons. It would be better to continue here so that we can collect
      // more root causes during computeDependencies.
      // Note that this doesn't apply to AspectFunction, because aspects can't have configurable
      // attributes.
      if (!transitiveRootCauses.isEmpty()
          && !Objects.equals(configConditions, ConfigConditions.EMPTY)) {
        NestedSet<Cause> causes = transitiveRootCauses.build();
        env.getListener()
            .handle(Event.error(target.getLocation(), "Cannot compute config conditions"));
        throw new ReportedException(
            new ConfiguredValueCreationException(
                ctgValue,
                "Cannot compute config conditions",
                causes,
                getPrioritizedDetailedExitCode(causes)));
      }

      // Calculate the dependencies of this target.
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap =
          computeDependencies(
              env,
              resolver,
              ctgValue,
              ImmutableList.of(),
              configConditions.asProviders(),
              unloadedToolchainContexts == null
                  ? null
                  : unloadedToolchainContexts.asToolchainContexts(),
              DependencyResolver.shouldUseToolchainTransition(configuration, ctgValue.getTarget()),
              ruleClassProvider,
              view.getHostConfiguration(),
              transitivePackagesForPackageRootResolution,
              transitiveRootCauses);
      if (!transitiveRootCauses.isEmpty()) {
        NestedSet<Cause> causes = transitiveRootCauses.build();
        // TODO(bazel-team): consider reporting the error in this class vs. exporting it for
        // BuildTool to handle. Calling code needs to be untangled for that to work and pass tests.
        throw new UnreportedException(
            new ConfiguredValueCreationException(
                ctgValue, "Analysis failed", causes, getPrioritizedDetailedExitCode(causes)));
      }
      if (env.valuesMissing()) {
        return null;
      }
      Preconditions.checkNotNull(depValueMap);

      // Load the requested toolchains into the ToolchainContext, now that we have dependencies.
      ToolchainCollection<ResolvedToolchainContext> toolchainContexts = null;
      if (unloadedToolchainContexts != null) {
        String targetDescription = target.toString();
        ToolchainCollection.Builder<ResolvedToolchainContext> contextsBuilder =
            ToolchainCollection.builder();
        for (Map.Entry<String, UnloadedToolchainContext> unloadedContext :
            unloadedToolchainContexts.getContextMap().entrySet()) {
          Set<ConfiguredTargetAndData> toolchainDependencies =
              depValueMap.get(DependencyKind.forExecGroup(unloadedContext.getKey()));
          contextsBuilder.addContext(
              unloadedContext.getKey(),
              ResolvedToolchainContext.load(
                  unloadedContext.getValue(),
                  targetDescription,
                  toolchainDependencies));
        }
        toolchainContexts = contextsBuilder.build();
      }

      ConfiguredTargetValue ans =
          createConfiguredTarget(
              view,
              env,
              ctgValue,
              configuredTargetKey,
              depValueMap,
              configConditions,
              toolchainContexts,
              execGroupCollectionBuilder,
              transitivePackagesForPackageRootResolution);
      if (ans != null && configuredTargetProgress != null) {
        configuredTargetProgress.doneConfigureTarget();
      }
      return ans;
    } catch (DependencyEvaluationException e) {
      String errorMessage = e.getMessage();
      if (!e.depReportedOwnError()) {
        env.getListener().handle(Event.error(e.getLocation(), e.getMessage()));
      }

      ConfiguredValueCreationException cvce = null;
      if (e.getCause() instanceof ConfiguredValueCreationException) {
        cvce = (ConfiguredValueCreationException) e.getCause();

        // Check if this is caused by an unresolved toolchain, and report it as such.
        if (unloadedToolchainContexts != null) {
          ImmutableSet<Label> requiredToolchains =
              unloadedToolchainContexts.getResolvedToolchains();
          Set<Label> toolchainDependencyErrors =
              cvce.getRootCauses().toList().stream()
                  .map(Cause::getLabel)
                  .filter(requiredToolchains::contains)
                  .collect(ImmutableSet.toImmutableSet());

          if (!toolchainDependencyErrors.isEmpty()) {
            errorMessage = "errors encountered resolving toolchains for " + target.getLabel();
            env.getListener().handle(Event.error(target.getLocation(), errorMessage));
          }
        }
      }

      throw new ReportedException(
          cvce != null
              ? cvce
              : new ConfiguredValueCreationException(
                  ctgValue, errorMessage, null, e.getDetailedExitCode()));
    } catch (ConfiguredValueCreationException e) {
      if (!e.getMessage().isEmpty()) {
        // Report the error to the user.
        env.getListener().handle(Event.error(e.getLocation(), e.getMessage()));
      }
      throw new ReportedException(e);
    } catch (AspectCreationException e) {
      throw new ReportedException(
          new ConfiguredValueCreationException(
              ctgValue, e.getMessage(), e.getCauses(), e.getDetailedExitCode()));
    } catch (ToolchainException e) {
      String message =
          String.format(
              "While resolving toolchains for target %s: %s", target.getLabel(), e.getMessage());
      // We need to throw a ConfiguredValueCreationException, so either find one or make one.
      ConfiguredValueCreationException cvce = asConfiguredValueCreationException(e);
      if (cvce == null) {
        cvce =
            new ConfiguredValueCreationException(ctgValue, message, null, e.getDetailedExitCode());
      }
      if (!message.isEmpty()) {
        // Report the error to the user.
        env.getListener().handle(Event.error(target.getLocation(), message));
      }
      throw new ReportedException(cvce);
    } finally {
      maybeReleaseSemaphore();
    }
  }

  /**
   * Simple wrapper to allow returning two variables from {@link #computeUnloadedToolchainContexts}.
   */
  @VisibleForTesting
  public static class ComputedToolchainContexts {
    @Nullable public ToolchainCollection<UnloadedToolchainContext> toolchainCollection = null;
    public ExecGroupCollection.Builder execGroupCollectionBuilder =
        ExecGroupCollection.emptyBuilder();
  }

  /**
   * Returns the toolchain context and exec group collection for this target. The toolchain context
   * may be {@code null} if the target doesn't use toolchains.
   *
   * <p>This involves Skyframe evaluation: callers should check {@link Environment#valuesMissing()
   * to check the result is valid.
   */
  @VisibleForTesting
  @Nullable
  public static ComputedToolchainContexts computeUnloadedToolchainContexts(
      Environment env,
      RuleClassProvider ruleClassProvider,
      TargetAndConfiguration targetAndConfig,
      @Nullable Label parentExecutionPlatformLabel)
      throws InterruptedException, ToolchainException {
    if (!(targetAndConfig.getTarget() instanceof Rule)) {
      return new ComputedToolchainContexts();
    }
    Rule rule = ((Rule) targetAndConfig.getTarget());
    BuildConfigurationValue configuration = targetAndConfig.getConfiguration();

    ImmutableSet<Label> requiredDefaultToolchains =
        rule.getRuleClassObject().getRequiredToolchains();
    // Collect local (target, rule) constraints for filtering out execution platforms.
    ImmutableSet<Label> defaultExecConstraintLabels =
        getExecutionPlatformConstraints(
            rule, configuration.getFragment(PlatformConfiguration.class));

    // Create a merged version of the exec groups that handles exec group inheritance properly.
    ExecGroup defaultExecGroup =
        ExecGroup.create(requiredDefaultToolchains, defaultExecConstraintLabels);
    ExecGroupCollection.Builder execGroupCollectionBuilder =
        ExecGroupCollection.builder(defaultExecGroup, rule.getRuleClassObject().getExecGroups());

    // Short circuit and end now if this target doesn't require toolchain resolution.
    if (!rule.useToolchainResolution()) {
      ComputedToolchainContexts result = new ComputedToolchainContexts();
      result.execGroupCollectionBuilder = execGroupCollectionBuilder;
      return result;
    }

    // The toolchain context's options are the parent rule's options with manual trimming
    // auto-applied. This means toolchains don't inherit feature flags. This helps build
    // performance: if the toolchain context had the exact same configuration of its parent and that
    // included feature flags, all the toolchain's dependencies would apply this transition
    // individually. That creates a lot more potentially expensive applications of that transition
    // (especially since manual trimming applies to every configured target in the build).
    //
    // In other words: without this modification:
    // parent rule -> toolchain context -> toolchain
    //     -> toolchain dep 1 # applies manual trimming to remove feature flags
    //     -> toolchain dep 2 # applies manual trimming to remove feature flags
    //     ...
    //
    // With this modification:
    // parent rule -> toolchain context # applies manual trimming to remove feature flags
    //     -> toolchain
    //         -> toolchain dep 1
    //         -> toolchain dep 2
    //         ...
    //
    // None of this has any effect on rules that don't utilize manual trimming.
    PatchTransition toolchainTaggedTrimmingTransition =
        ((ConfiguredRuleClassProvider) ruleClassProvider).getToolchainTaggedTrimmingTransition();
    BuildOptions toolchainOptions =
        toolchainTaggedTrimmingTransition.patch(
            new BuildOptionsView(
                configuration.getOptions(),
                toolchainTaggedTrimmingTransition.requiresOptionFragments()),
            env.getListener());

    BuildConfigurationKey toolchainConfig =
        BuildConfigurationKey.withoutPlatformMapping(toolchainOptions);

    Map<String, ToolchainContextKey> toolchainContextKeys = new HashMap<>();
    String targetUnloadedToolchainContext = "target-unloaded-toolchain-context";

    // Check if this specific target should be debugged for toolchain resolution.
    boolean debugTarget =
        configuration
            .getFragment(PlatformConfiguration.class)
            .debugToolchainResolution(targetAndConfig.getLabel());

    ToolchainContextKey.Builder toolchainContextKeyBuilder =
        ToolchainContextKey.key()
            .configurationKey(toolchainConfig)
            .requiredToolchainTypeLabels(requiredDefaultToolchains)
            .execConstraintLabels(defaultExecConstraintLabels)
            .debugTarget(debugTarget);

    if (parentExecutionPlatformLabel != null) {
      // Find out what execution platform the parent used, and force that.
      // This should only be set for direct toolchain dependencies.
      toolchainContextKeyBuilder.forceExecutionPlatform(parentExecutionPlatformLabel);
    }

    ToolchainContextKey toolchainContextKey = toolchainContextKeyBuilder.build();
    toolchainContextKeys.put(targetUnloadedToolchainContext, toolchainContextKey);
    for (String name : execGroupCollectionBuilder.getExecGroupNames()) {
      ExecGroup execGroup = execGroupCollectionBuilder.getExecGroup(name);
      toolchainContextKeys.put(
          name,
          ToolchainContextKey.key()
              .configurationKey(toolchainConfig)
              .requiredToolchainTypeLabels(execGroup.requiredToolchains())
              .execConstraintLabels(execGroup.execCompatibleWith())
              .debugTarget(debugTarget)
              .build());
    }

    Map<SkyKey, ValueOrException<ToolchainException>> values =
        env.getValuesOrThrow(toolchainContextKeys.values(), ToolchainException.class);

    boolean valuesMissing = env.valuesMissing();

    ToolchainCollection.Builder<UnloadedToolchainContext> toolchainContexts =
        valuesMissing ? null : ToolchainCollection.builder();
    for (Map.Entry<String, ToolchainContextKey> unloadedToolchainContextKey :
        toolchainContextKeys.entrySet()) {
      UnloadedToolchainContext unloadedToolchainContext =
          (UnloadedToolchainContext) values.get(unloadedToolchainContextKey.getValue()).get();
      if (!valuesMissing) {
        String execGroup = unloadedToolchainContextKey.getKey();
        if (execGroup.equals(targetUnloadedToolchainContext)) {
          toolchainContexts.addDefaultContext(unloadedToolchainContext);
        } else {
          toolchainContexts.addContext(execGroup, unloadedToolchainContext);
        }
      }
    }

    ComputedToolchainContexts result = new ComputedToolchainContexts();
    result.toolchainCollection = valuesMissing ? null : toolchainContexts.build();
    result.execGroupCollectionBuilder = execGroupCollectionBuilder;
    return result;
  }

  /**
   * Returns the target-specific execution platform constraints, based on the rule definition and
   * any constraints added by the target, including those added for the target on the command line.
   */
  public static ImmutableSet<Label> getExecutionPlatformConstraints(
      Rule rule, PlatformConfiguration platformConfiguration) {
    NonconfigurableAttributeMapper mapper = NonconfigurableAttributeMapper.of(rule);
    ImmutableSet.Builder<Label> execConstraintLabels = new ImmutableSet.Builder<>();

    execConstraintLabels.addAll(rule.getRuleClassObject().getExecutionPlatformConstraints());
    if (rule.getRuleClassObject()
        .hasAttr(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)) {
      execConstraintLabels.addAll(
          mapper.get(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST));
    }

    execConstraintLabels.addAll(
        platformConfiguration.getAdditionalExecutionConstraintsFor(rule.getLabel()));

    return execConstraintLabels.build();
  }

  /**
   * Computes the direct dependencies of a node in the configured target graph (a configured target
   * or an aspects).
   *
   * <p>Returns null if Skyframe hasn't evaluated the required dependencies yet. In this case, the
   * caller should also return null to Skyframe.
   *
   * @param env the Skyframe environment
   * @param resolver the dependency resolver
   * @param ctgValue the label and the configuration of the node
   * @param configConditions the configuration conditions for evaluating the attributes of the node
   * @param toolchainContexts the toolchain context for this target
   * @param ruleClassProvider rule class provider for determining the right configuration fragments
   *     to apply to deps
   * @param hostConfiguration the host configuration. There's a noticeable performance hit from
   *     instantiating this on demand for every dependency that wants it, so it's best to compute
   *     the host configuration as early as possible and pass this reference to all consumers
   */
  @Nullable
  static OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> computeDependencies(
      Environment env,
      SkyframeDependencyResolver resolver,
      TargetAndConfiguration ctgValue,
      Iterable<Aspect> aspects,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
      boolean useToolchainTransition,
      RuleClassProvider ruleClassProvider,
      BuildConfigurationValue hostConfiguration,
      @Nullable NestedSetBuilder<Package> transitivePackagesForPackageRootResolution,
      NestedSetBuilder<Cause> transitiveRootCauses)
      throws DependencyEvaluationException, ConfiguredValueCreationException,
          AspectCreationException, InterruptedException {
    // Create the map from attributes to set of (target, transition) pairs.
    OrderedSetMultimap<DependencyKind, DependencyKey> initialDependencies;
    BuildConfigurationValue configuration = ctgValue.getConfiguration();
    Label label = ctgValue.getLabel();
    try {
      initialDependencies =
          resolver.dependentNodeMap(
              ctgValue,
              hostConfiguration,
              aspects,
              configConditions,
              toolchainContexts,
              useToolchainTransition,
              transitiveRootCauses,
              ((ConfiguredRuleClassProvider) ruleClassProvider).getTrimmingTransitionFactory());
    } catch (DependencyResolver.Failure e) {
      env.getListener().post(new AnalysisRootCauseEvent(configuration, label, e.getMessage()));
      throw new DependencyEvaluationException(
          new ConfiguredValueCreationException(
              e.getLocation(), e.getMessage(), label, configuration.getEventId(), null, null),
          // These errors occur within DependencyResolver, which is attached to the current target.
          // i.e. no dependent ConfiguredTargetFunction call happens to report its own error.
          /*depReportedOwnError=*/ false);
    } catch (InconsistentAspectOrderException e) {
      throw new DependencyEvaluationException(e);
    }
    // Trim each dep's configuration so it only includes the fragments needed by its transitive
    // closure.
    ConfigurationResolver configResolver =
        new ConfigurationResolver(env, ctgValue, hostConfiguration, configConditions);
    OrderedSetMultimap<DependencyKind, Dependency> depValueNames =
        configResolver.resolveConfigurations(initialDependencies);

    // Return early in case packages were not loaded yet. In theory, we could start configuring
    // dependent targets in loaded packages. However, that creates an artificial sync boundary
    // between loading all dependent packages (fast) and configuring some dependent targets (can
    // have a long tail).
    if (env.valuesMissing()) {
      return null;
    }

    // Resolve configured target dependencies and handle errors.
    Map<SkyKey, ConfiguredTargetAndData> depValues =
        resolveConfiguredTargetDependencies(
            env,
            ctgValue,
            depValueNames.values(),
            transitivePackagesForPackageRootResolution,
            transitiveRootCauses);
    if (depValues == null) {
      return null;
    }

    // Resolve required aspects.
    OrderedSetMultimap<Dependency, ConfiguredAspect> depAspects =
        AspectResolver.resolveAspectDependencies(
            env, depValues, depValueNames.values(), transitivePackagesForPackageRootResolution);
    if (depAspects == null) {
      return null;
    }

    // Merge the dependent configured targets and aspects into a single map.
    try {
      return AspectResolver.mergeAspects(depValueNames, depValues, depAspects);
    } catch (DuplicateException e) {
      throw new DependencyEvaluationException(
          new ConfiguredValueCreationException(ctgValue, e.getMessage()),
          /*depReportedOwnError=*/ false);
    }
  }

  /**
   * Returns the targets that key the configurable attributes used by this rule.
   *
   * <p>>If the configured targets supplying those providers aren't yet resolved by the dependency
   * resolver, returns null.
   */
  @Nullable
  static ConfigConditions getConfigConditions(
      Environment env,
      TargetAndConfiguration ctgValue,
      @Nullable NestedSetBuilder<Package> transitivePackagesForPackageRootResolution,
      @Nullable PlatformInfo platformInfo,
      NestedSetBuilder<Cause> transitiveRootCauses)
      throws ConfiguredValueCreationException, InterruptedException {
    Target target = ctgValue.getTarget();
    if (!(target instanceof Rule)) {
      return ConfigConditions.EMPTY;
    }
    RawAttributeMapper attrs = RawAttributeMapper.of(((Rule) target));
    if (!attrs.has(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE)) {
      return ConfigConditions.EMPTY;
    }

    // Collect the labels of the configured targets we need to resolve.
    List<Label> configLabels =
        attrs.get(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE, BuildType.LABEL_LIST);
    if (configLabels.isEmpty()) {
      return ConfigConditions.EMPTY;
    }

    // Collect the actual deps without a configuration transition (since by definition config
    // conditions evaluate over the current target's configuration). If the dependency is
    // (erroneously) something that needs the null configuration, its analysis will be
    // short-circuited. That error will be reported later.
    ImmutableList.Builder<Dependency> depsBuilder = ImmutableList.builder();
    for (Label configurabilityLabel : configLabels) {
      Dependency configurabilityDependency =
          Dependency.builder()
              .setLabel(configurabilityLabel)
              .setConfiguration(ctgValue.getConfiguration())
              .build();
      depsBuilder.add(configurabilityDependency);
    }

    ImmutableList<Dependency> configConditionDeps = depsBuilder.build();

    Map<SkyKey, ConfiguredTargetAndData> configValues;
    try {
      configValues =
          resolveConfiguredTargetDependencies(
              env,
              ctgValue,
              configConditionDeps,
              transitivePackagesForPackageRootResolution,
              transitiveRootCauses);
      if (configValues == null) {
        return null;
      }
    } catch (DependencyEvaluationException e) {
      // One of the config dependencies doesn't exist, and we need to report that. Unfortunately,
      // there's not enough information to know which configurable attribute has the problem.
      throw new ConfiguredValueCreationException(
          // The precise error is reported by the dependency that failed to load.
          // TODO(gregce): beautify this error: https://github.com/bazelbuild/bazel/issues/11984.
          ctgValue, "errors encountered resolving select() keys for " + target.getLabel());
    }

    ImmutableMap.Builder<Label, ConfiguredTargetAndData> asConfiguredTargets =
        ImmutableMap.builder();
    ImmutableMap.Builder<Label, ConfigMatchingProvider> asConfigConditions = ImmutableMap.builder();

    // Get the configured targets as ConfigMatchingProvider interfaces.
    for (Dependency entry : configConditionDeps) {
      SkyKey baseKey = entry.getConfiguredTargetKey();
      // The code above guarantees that selectKeyTarget is non-null here.
      ConfiguredTargetAndData selectKeyTarget = configValues.get(baseKey);
      asConfiguredTargets.put(entry.getLabel(), selectKeyTarget);
      try {
        asConfigConditions.put(
            entry.getLabel(), ConfigConditions.fromConfiguredTarget(selectKeyTarget, platformInfo));
      } catch (ConfigConditions.InvalidConditionException e) {
        String message =
            String.format(
                    "%s is not a valid select() condition for %s.\n",
                    selectKeyTarget.getTarget().getLabel(), target.getLabel())
                + String.format(
                    "To inspect the select(), run: bazel query --output=build %s.\n",
                    target.getLabel())
                + "For more help, see https://docs.bazel.build/be/functions.html#select.\n\n";
        throw new ConfiguredValueCreationException(ctgValue, message);
      }
    }

    return ConfigConditions.create(asConfiguredTargets.build(), asConfigConditions.build());
  }

  /**
   * Resolves the targets referenced in depValueNames and returns their {@link
   * ConfiguredTargetAndData} instances.
   *
   * <p>Returns null if not all instances are available yet.
   */
  @Nullable
  private static Map<SkyKey, ConfiguredTargetAndData> resolveConfiguredTargetDependencies(
      Environment env,
      TargetAndConfiguration ctgValue,
      Collection<Dependency> deps,
      @Nullable NestedSetBuilder<Package> transitivePackagesForPackageRootResolution,
      NestedSetBuilder<Cause> transitiveRootCauses)
      throws DependencyEvaluationException, InterruptedException {
    boolean missedValues = env.valuesMissing();
    ConfiguredValueCreationException rootError = null;
    DetailedExitCode detailedExitCode = null;
    // Naively we would like to just fetch all requested ConfiguredTargets, together with their
    // Packages. However, some ConfiguredTargets are AliasConfiguredTargets, which means that their
    // associated Targets (and therefore associated Packages) don't correspond to their own Labels.
    // We don't know the associated Package until we fetch the ConfiguredTarget. Therefore, we have
    // to do a potential second pass, in which we fetch all the Packages for AliasConfiguredTargets.
    Iterable<SkyKey> depKeys =
        Iterables.concat(
            Iterables.transform(deps, Dependency::getConfiguredTargetKey),
            Iterables.transform(
                deps, input -> PackageValue.key(input.getLabel().getPackageIdentifier())));
    Map<SkyKey, ValueOrException<ConfiguredValueCreationException>> depValuesOrExceptions =
        env.getValuesOrThrow(depKeys, ConfiguredValueCreationException.class);
    Map<SkyKey, ConfiguredTargetAndData> result = Maps.newHashMapWithExpectedSize(deps.size());
    Set<SkyKey> aliasPackagesToFetch = new HashSet<>();
    List<Dependency> aliasDepsToRedo = new ArrayList<>();
    Map<SkyKey, SkyValue> aliasPackageValues = null;
    Collection<Dependency> depsToProcess = deps;
    for (int i = 0; i < 2; i++) {
      for (Dependency dep : depsToProcess) {
        SkyKey key = dep.getConfiguredTargetKey();
        ConfiguredTargetValue depValue;
        try {
          depValue = (ConfiguredTargetValue) depValuesOrExceptions.get(key).get();
        } catch (ConfiguredValueCreationException e) {
          transitiveRootCauses.addTransitive(e.getRootCauses());
          detailedExitCode =
              DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                  e.getDetailedExitCode(), detailedExitCode);
          if (e.getDetailedExitCode().equals(detailedExitCode)) {
            rootError = e;
          }
          continue;
        }
        if (depValue == null) {
          missedValues = true;
          continue;
        }

        ConfiguredTarget depCt = depValue.getConfiguredTarget();
        Label depLabel = depCt.getLabel();
        SkyKey packageKey = PackageValue.key(depLabel.getPackageIdentifier());
        PackageValue pkgValue;
        if (i == 0) {
          ValueOrUntypedException packageResult = depValuesOrExceptions.get(packageKey);
          if (packageResult == null) {
            aliasPackagesToFetch.add(packageKey);
            aliasDepsToRedo.add(dep);
            continue;
          } else {
            pkgValue = (PackageValue) packageResult.getUnchecked();
            if (pkgValue == null) {
              // In a race, the getValuesOrThrow call above may have retrieved the package before it
              // was done but the configured target after it was done. Since SkyFunctionEnvironment
              // may cache absent values, re-requesting it on this evaluation may be useless, just
              // treat it as missing.
              missedValues = true;
              continue;
            }
          }
        } else {
          // We were doing AliasConfiguredTarget mop-up.
          pkgValue = (PackageValue) aliasPackageValues.get(packageKey);
          if (pkgValue == null) {
            // This is unexpected: on the second iteration, all packages should be present, since
            // the configured targets that depend on them are present. But since that is not a
            // guarantee Skyframe makes, we tolerate their absence.
            missedValues = true;
            continue;
          }
        }

        try {
          BuildConfigurationValue depConfiguration = dep.getConfiguration();
          BuildConfigurationKey depKey = depValue.getConfiguredTarget().getConfigurationKey();
          if (depKey != null && !depKey.equals(depConfiguration.getKey())) {
            depConfiguration = (BuildConfigurationValue) env.getValue(depKey);
          }
          result.put(
              key,
              new ConfiguredTargetAndData(
                  depValue.getConfiguredTarget(),
                  pkgValue.getPackage().getTarget(depLabel.getName()),
                  depConfiguration,
                  dep.getTransitionKeys()));
        } catch (NoSuchTargetException e) {
          throw new IllegalStateException("Target already verified for " + dep, e);
        }
        if (transitivePackagesForPackageRootResolution != null) {
          transitivePackagesForPackageRootResolution.addTransitive(
              depValue.getTransitivePackagesForPackageRootResolution());
        }
      }

      if (aliasDepsToRedo.isEmpty()) {
        break;
      }
      aliasPackageValues = env.getValues(aliasPackagesToFetch);
      depsToProcess = aliasDepsToRedo;
    }

    if (rootError != null) {
      throw new DependencyEvaluationException(
          new ConfiguredValueCreationException(
              ctgValue, rootError.getMessage(), transitiveRootCauses.build(), detailedExitCode),
          /*depReportedOwnError=*/ true);
    }
    return missedValues ? null : result;
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(((ConfiguredTargetKey) skyKey.argument()).getLabel());
  }

  @Nullable
  private static ConfiguredTargetValue createConfiguredTarget(
      SkyframeBuildView view,
      Environment env,
      TargetAndConfiguration ctgValue,
      ConfiguredTargetKey configuredTargetKey,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap,
      ConfigConditions configConditions,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      ExecGroupCollection.Builder execGroupCollectionBuilder,
      @Nullable NestedSetBuilder<Package> transitivePackagesForPackageRootResolution)
      throws ConfiguredValueCreationException, InterruptedException {
    Target target = ctgValue.getTarget();
    BuildConfigurationValue configuration = ctgValue.getConfiguration();

    // Should be successfully evaluated and cached from the loading phase.
    StarlarkBuiltinsValue starlarkBuiltinsValue =
        (StarlarkBuiltinsValue) env.getValue(StarlarkBuiltinsValue.key());
    if (starlarkBuiltinsValue == null) {
      return null;
    }

    StoredEventHandler events = new StoredEventHandler();
    CachingAnalysisEnvironment analysisEnvironment =
        view.createAnalysisEnvironment(
            configuredTargetKey, events, env, configuration, starlarkBuiltinsValue);

    Preconditions.checkNotNull(depValueMap);
    ConfiguredTarget configuredTarget;
    try {
      configuredTarget =
          view.createConfiguredTarget(
              target,
              configuration,
              analysisEnvironment,
              configuredTargetKey,
              depValueMap,
              configConditions,
              toolchainContexts,
              execGroupCollectionBuilder);
    } catch (MissingDepException e) {
      Preconditions.checkState(env.valuesMissing(), e.getMessage());
      return null;
    } catch (ActionConflictException | InvalidExecGroupException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    }

    events.replayOn(env.getListener());
    if (events.hasErrors()) {
      analysisEnvironment.disable(target);
      NestedSet<Cause> rootCauses =
          NestedSetBuilder.wrap(
              Order.STABLE_ORDER,
              events.getEvents().stream()
                  .filter((event) -> event.getKind() == EventKind.ERROR)
                  .map(
                      (event) ->
                          new AnalysisFailedCause(
                              target.getLabel(),
                              configuration == null
                                  ? null
                                  : configuration.getEventId().getConfiguration(),
                              createDetailedExitCode(event.getMessage())))
                  .collect(Collectors.toList()));
      throw new ConfiguredValueCreationException(
          ctgValue, "Analysis of target '" + target.getLabel() + "' failed", rootCauses, null);
    }
    Preconditions.checkState(!analysisEnvironment.hasErrors(),
        "Analysis environment hasError() but no errors reported");
    if (env.valuesMissing()) {
      return null;
    }

    analysisEnvironment.disable(target);
    Preconditions.checkNotNull(configuredTarget, target);

    if (configuredTarget instanceof RuleConfiguredTarget) {
      RuleConfiguredTarget ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
      return new RuleConfiguredTargetValue(
          ruleConfiguredTarget,
          transitivePackagesForPackageRootResolution == null
              ? null
              : transitivePackagesForPackageRootResolution.build());
    } else {
      Preconditions.checkState(
          analysisEnvironment.getRegisteredActions().isEmpty(),
          "Non-rule can't have actions: %s %s %s %s",
          configuredTargetKey,
          analysisEnvironment.getRegisteredActions(),
          configuredTarget);
      return new NonRuleConfiguredTargetValue(
          configuredTarget,
          transitivePackagesForPackageRootResolution == null
              ? null
              : transitivePackagesForPackageRootResolution.build());
    }
  }

  private static DetailedExitCode createDetailedExitCode(String message) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setAnalysis(Analysis.newBuilder().setCode(Code.CONFIGURED_VALUE_CREATION_FAILED))
            .build());
  }

  static DetailedExitCode getPrioritizedDetailedExitCode(NestedSet<Cause> causes) {
    DetailedExitCode prioritizedDetailedExitCode = null;
    for (Cause c : causes.toList()) {
      prioritizedDetailedExitCode =
          DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
              prioritizedDetailedExitCode, c.getDetailedExitCode());
    }
    return prioritizedDetailedExitCode;
  }

  /**
   * {@link ConfiguredTargetFunction#compute} exception that has already had its error reported to
   * the user. Callers (like {@link BuildTool}) won't also report the error.
   */
  private static class ReportedException extends SkyFunctionException {
    private ReportedException(ConfiguredValueCreationException e) {
      super(withoutMessage(e), Transience.PERSISTENT);
    }

    /** Clones a {@link ConfiguredValueCreationException} with its {@code message} field removed. */
    private static ConfiguredValueCreationException withoutMessage(
        ConfiguredValueCreationException orig) {
      return new ConfiguredValueCreationException(
          orig.getLocation(),
          "",
          /*label=*/ null,
          orig.getConfiguration(),
          orig.getRootCauses(),
          orig.getDetailedExitCode());
    }
  }

  /**
   * {@link ConfiguredTargetFunction#compute} exception that has not had its error reported to the
   * user. Callers (like {@link BuildTool}) are responsible for reporting the error.
   */
  private static class UnreportedException extends SkyFunctionException {
    private UnreportedException(ConfiguredValueCreationException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
