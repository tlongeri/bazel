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
package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.analysis.DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY;
import static com.google.devtools.build.lib.analysis.DependencyKind.VISIBILITY_DEPENDENCY;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.AspectCollection.AspectCycleOnPathException;
import com.google.devtools.build.lib.analysis.DependencyKind.AttributeDependencyKind;
import com.google.devtools.build.lib.analysis.DependencyKind.ToolchainDependencyKind;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.analysis.config.TransitionResolver;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NullTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.AdvertisedProviderSet;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.PackageGroupsRuleVisibility;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * Resolver for dependencies between configured targets.
 *
 * <p>Includes logic to derive the right configurations depending on transition type.
 */
public abstract class DependencyResolver {

  /**
   * Returns whether or not to use the new toolchain transition. Checks the global incompatible
   * change flag and the rule's toolchain transition readiness attribute.
   */
  // TODO(#10523): Remove this when the migration period for toolchain transitions has ended.
  public static boolean shouldUseToolchainTransition(
      @Nullable BuildConfigurationValue configuration, Target target) {
    return shouldUseToolchainTransition(
        configuration, target instanceof Rule ? (Rule) target : null);
  }

  /**
   * Returns whether or not to use the new toolchain transition. Checks the global incompatible
   * change flag and the rule's toolchain transition readiness attribute.
   */
  // TODO(#10523): Remove this when the migration period for toolchain transitions has ended.
  public static boolean shouldUseToolchainTransition(
      @Nullable BuildConfigurationValue configuration, @Nullable Rule rule) {
    // Check whether the global incompatible change flag is set.
    if (configuration != null) {
      PlatformOptions platformOptions = configuration.getOptions().get(PlatformOptions.class);
      if (platformOptions != null && platformOptions.overrideToolchainTransition) {
        return true;
      }
    }

    // Check the rule definition to see if it is ready.
    if (rule != null && rule.getRuleClassObject().useToolchainTransition()) {
      return true;
    }

    // Default to false.
    return false;
  }

  /**
   * What we know about a dependency edge after factoring in the properties of the configured target
   * that the edge originates from, but not the properties of target it points to.
   */
  @AutoValue
  abstract static class PartiallyResolvedDependency {
    abstract Label getLabel();

    abstract ConfigurationTransition getTransition();

    abstract ImmutableList<Aspect> getPropagatingAspects();

    @Nullable
    abstract Label getExecutionPlatformLabel();

    /** A Builder to create instances of PartiallyResolvedDependency. */
    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setLabel(Label label);

      abstract Builder setTransition(ConfigurationTransition transition);

      abstract Builder setPropagatingAspects(List<Aspect> propagatingAspects);

      abstract Builder setExecutionPlatformLabel(@Nullable Label executionPlatformLabel);

      abstract PartiallyResolvedDependency build();
    }

    static Builder builder() {
      return new AutoValue_DependencyResolver_PartiallyResolvedDependency.Builder()
          .setPropagatingAspects(ImmutableList.of());
    }

    public DependencyKey.Builder getDependencyKeyBuilder() {
      return DependencyKey.builder()
          .setLabel(getLabel())
          .setExecutionPlatformLabel(getExecutionPlatformLabel());
    }
  }

  /**
   * Returns ids for dependent nodes of a given node, sorted by attribute. Note that some
   * dependencies do not have a corresponding attribute here, and we use the null attribute to
   * represent those edges.
   *
   * <p>If {@code aspect} is null, returns the dependent nodes of the configured target node
   * representing the given target and configuration, otherwise that of the aspect node accompanying
   * the aforementioned configured target node for the specified aspect.
   *
   * <p>The values are not simply labels because this also implements the first step of applying
   * configuration transitions, namely, split transitions. This needs to be done before the labels
   * are resolved because late bound attributes depend on the configuration. A good example for this
   * is {@code :cc_toolchain}.
   *
   * <p>The long-term goal is that most configuration transitions be applied here. However, in order
   * to do that, we first have to eliminate transitions that depend on the rule class of the
   * dependency.
   *
   * @param node the target/configuration being evaluated
   * @param hostConfig the configuration this target would use if it was evaluated as a host tool.
   *     This is needed to support {@link LateBoundDefault#useHostConfiguration()}.
   * @param aspect the aspect applied to this target (if any)
   * @param configConditions resolver for config_setting labels
   * @param toolchainContexts the toolchain contexts for this target
   * @param trimmingTransitionFactory the transition factory used to trim rules (note: this is a
   *     temporary feature; see the corresponding methods in ConfiguredRuleClassProvider)
   * @return a mapping of each attribute in this rule or aspects to its dependent nodes
   */
  public final OrderedSetMultimap<DependencyKind, DependencyKey> dependentNodeMap(
      TargetAndConfiguration node,
      BuildConfigurationValue hostConfig,
      @Nullable Aspect aspect,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
      boolean useToolchainTransition,
      @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory)
      throws Failure, InterruptedException, InconsistentAspectOrderException {
    NestedSetBuilder<Cause> rootCauses = NestedSetBuilder.stableOrder();
    OrderedSetMultimap<DependencyKind, DependencyKey> outgoingEdges =
        dependentNodeMap(
            node,
            hostConfig,
            aspect != null ? ImmutableList.of(aspect) : ImmutableList.of(),
            configConditions,
            toolchainContexts,
            useToolchainTransition,
            rootCauses,
            trimmingTransitionFactory);
    if (!rootCauses.isEmpty()) {
      throw new IllegalStateException(rootCauses.build().toList().iterator().next().toString());
    }
    return outgoingEdges;
  }

  /**
   * Returns ids for dependent nodes of a given node, sorted by attribute. Note that some
   * dependencies do not have a corresponding attribute here, and we use the null attribute to
   * represent those edges.
   *
   * <p>If {@code aspects} is empty, returns the dependent nodes of the configured target node
   * representing the given target and configuration.
   *
   * <p>Otherwise {@code aspects} represents an aspect path. The function returns dependent nodes of
   * the entire path applied to given target and configuration. These are the dependent nodes of the
   * last aspect in the path.
   *
   * <p>This also implements the first step of applying configuration transitions, namely, split
   * transitions. This needs to be done before the labels are resolved because late bound attributes
   * depend on the configuration. A good example for this is {@code :cc_toolchain}.
   *
   * <p>The long-term goal is that most configuration transitions be applied here. However, in order
   * to do that, we first have to eliminate transitions that depend on the rule class of the
   * dependency.
   *
   * @param node the target/configuration being evaluated
   * @param hostConfig the configuration this target would use if it was evaluated as a host tool.
   *     This is needed to support {@link LateBoundDefault#useHostConfiguration()}.
   * @param aspects the aspects applied to this target (if any)
   * @param configConditions resolver for config_setting labels
   * @param toolchainContexts the toolchain contexts for this target
   * @param trimmingTransitionFactory the transition factory used to trim rules (note: this is a
   *     temporary feature; see the corresponding methods in ConfiguredRuleClassProvider)
   * @param rootCauses collector for dep labels that can't be (loading phase) loaded
   * @return a mapping of each attribute in this rule or aspects to its dependent nodes
   */
  public final OrderedSetMultimap<DependencyKind, DependencyKey> dependentNodeMap(
      TargetAndConfiguration node,
      BuildConfigurationValue hostConfig,
      Iterable<Aspect> aspects,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
      boolean useToolchainTransition,
      NestedSetBuilder<Cause> rootCauses,
      @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory)
      throws Failure, InterruptedException, InconsistentAspectOrderException {
    Target target = node.getTarget();
    BuildConfigurationValue config = node.getConfiguration();
    OrderedSetMultimap<DependencyKind, Label> outgoingLabels = OrderedSetMultimap.create();

    // TODO(bazel-team): Figure out a way to implement the below (and partiallyResolveDependencies)
    // using LabelVisitationUtils.
    Rule fromRule = null;
    ConfiguredAttributeMapper attributeMap = null;
    if (target instanceof OutputFile) {
      Preconditions.checkNotNull(config);
      visitTargetVisibility(node, outgoingLabels);
      Rule rule = ((OutputFile) target).getGeneratingRule();
      outgoingLabels.put(OUTPUT_FILE_RULE_DEPENDENCY, rule.getLabel());
      if (Iterables.any(aspects, a -> a.getDefinition().applyToFiles())) {
        attributeMap =
            ConfiguredAttributeMapper.of(rule, configConditions, node.getConfiguration());
        resolveAttributes(
            getAspectAttributes(aspects),
            outgoingLabels,
            rule,
            attributeMap,
            node.getConfiguration(),
            hostConfig);
      }
    } else if (target instanceof InputFile) {
      visitTargetVisibility(node, outgoingLabels);
    } else if (target instanceof EnvironmentGroup) {
      visitTargetVisibility(node, outgoingLabels);
    } else if (target instanceof Rule) {
      fromRule = (Rule) target;
      attributeMap =
          ConfiguredAttributeMapper.of(fromRule, configConditions, node.getConfiguration());
      visitRule(node, hostConfig, aspects, attributeMap, toolchainContexts, outgoingLabels);
    } else if (target instanceof PackageGroup) {
      outgoingLabels.putAll(VISIBILITY_DEPENDENCY, ((PackageGroup) target).getIncludes());
    } else {
      throw new IllegalStateException(target.getLabel().toString());
    }

    Map<Label, Target> targetMap = getTargets(outgoingLabels, node, rootCauses);
    if (targetMap == null) {
      // Dependencies could not be resolved. Try again when they are loaded by Skyframe.
      return OrderedSetMultimap.create();
    }

    // This check makes sure that visibility labels actually refer to package groups.
    if (fromRule != null) {
      checkPackageGroupVisibility(fromRule, targetMap);
    }

    OrderedSetMultimap<DependencyKind, PartiallyResolvedDependency> partiallyResolvedDeps =
        partiallyResolveDependencies(
            config,
            outgoingLabels,
            fromRule,
            attributeMap,
            toolchainContexts,
            useToolchainTransition,
            aspects);

    OrderedSetMultimap<DependencyKind, DependencyKey> outgoingEdges =
        fullyResolveDependencies(
            partiallyResolvedDeps, targetMap, node.getConfiguration(), trimmingTransitionFactory);

    return outgoingEdges;
  }

  /**
   * Factor in the properties of the current rule into the dependency edge calculation.
   *
   * <p>The target of the dependency edges depends on two things: the rule that depends on them and
   * the type of target they depend on. This function takes the rule into account. Accordingly, it
   * should <b>NOT</b> get the {@link Target} instances representing the targets of the dependency
   * edges as an argument.
   */
  private OrderedSetMultimap<DependencyKind, PartiallyResolvedDependency>
      partiallyResolveDependencies(
          BuildConfigurationValue config,
          OrderedSetMultimap<DependencyKind, Label> outgoingLabels,
          @Nullable Rule fromRule,
          @Nullable ConfiguredAttributeMapper attributeMap,
          @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
          boolean useToolchainTransition,
          Iterable<Aspect> aspects)
          throws Failure {
    OrderedSetMultimap<DependencyKind, PartiallyResolvedDependency> partiallyResolvedDeps =
        OrderedSetMultimap.create();

    for (Map.Entry<DependencyKind, Label> entry : outgoingLabels.entries()) {
      Label toLabel = entry.getValue();

      if (DependencyKind.isToolchain(entry.getKey())) {
        // This dependency is a toolchain. Its package has not been loaded and therefore we can't
        // determine which aspects and which rule configuration transition we should use, so just
        // use sensible defaults. Not depending on their package makes the error message reporting
        // a missing toolchain a bit better.
        // TODO(lberki): This special-casing is weird. Find a better way to depend on toolchains.
        // TODO(#10523): Remove check when this is fully released.
        // This logic needs to stay in sync with the dep finding logic in
        // //third_party/bazel/src/main/java/com/google/devtools/build/lib/analysis/Util.java#findImplicitDeps.
        if (useToolchainTransition) {
          ToolchainDependencyKind tdk = (ToolchainDependencyKind) entry.getKey();
          ToolchainContext toolchainContext =
              toolchainContexts.getToolchainContext(tdk.getExecGroupName());
          partiallyResolvedDeps.put(
              entry.getKey(),
              PartiallyResolvedDependency.builder()
                  .setLabel(toLabel)
                  .setTransition(NoTransition.INSTANCE)
                  .setExecutionPlatformLabel(toolchainContext.executionPlatform().label())
                  .build());
        } else {
          // Legacy approach: use a HostTransition.
          partiallyResolvedDeps.put(
              entry.getKey(),
              PartiallyResolvedDependency.builder()
                  .setLabel(toLabel)
                  .setTransition(HostTransition.INSTANCE)
                  .build());
        }
        continue;
      }

      if (entry.getKey() == VISIBILITY_DEPENDENCY) {
        partiallyResolvedDeps.put(
            VISIBILITY_DEPENDENCY,
            PartiallyResolvedDependency.builder()
                .setLabel(toLabel)
                .setTransition(NullTransition.INSTANCE)
                .setPropagatingAspects(ImmutableList.of())
                .build());
        continue;
      }

      if (entry.getKey() == OUTPUT_FILE_RULE_DEPENDENCY) {
        partiallyResolvedDeps.put(
            OUTPUT_FILE_RULE_DEPENDENCY,
            PartiallyResolvedDependency.builder()
                .setLabel(toLabel)
                .setTransition(NoTransition.INSTANCE)
                .setPropagatingAspects(ImmutableList.of())
                .build());
        continue;
      }

      // Compute the set of aspects that could be applied to a dependency. This is composed of two
      // parts:
      //
      // 1. The aspects are visible to this aspect being evaluated, if any (if another aspect is
      //    visible on the configured target for this one, it should also be visible on the
      //    dependencies for consistency). This is the argument "aspects".
      // 2. The aspects propagated by the attributes of this configured target / aspect. This is
      //    computed by collectPropagatingAspects().
      //
      // The presence of an aspect here does not necessarily mean that it will be available on a
      // dependency: it can still be filtered out because it requires a provider that the configured
      // target it should be attached to it doesn't advertise. This is taken into account in
      // computeAspectCollections() once the Target instances for the dependencies are known.
      Attribute attribute = entry.getKey().getAttribute();
      ImmutableList.Builder<Aspect> propagatingAspects = ImmutableList.builder();
      propagatingAspects.addAll(attribute.getAspects(fromRule));
      collectPropagatingAspects(
          ImmutableList.copyOf(aspects),
          attribute.getName(),
          config,
          entry.getKey().getOwningAspect(),
          propagatingAspects);

      Label executionPlatformLabel = null;
      // TODO(jcater): refactor this nested if structure into something simpler.
      if (toolchainContexts != null) {
        if (attribute.getTransitionFactory() instanceof ExecutionTransitionFactory) {
          String execGroup =
              ((ExecutionTransitionFactory) attribute.getTransitionFactory()).getExecGroup();
          if (!toolchainContexts.hasToolchainContext(execGroup)) {
            throw new Failure(
                fromRule != null ? fromRule.getLocation() : null,
                String.format(
                    "Attr '%s' declares a transition for non-existent exec group '%s'",
                    attribute.getName(), execGroup));
          }
          if (toolchainContexts.getToolchainContext(execGroup).executionPlatform() != null) {
            executionPlatformLabel =
                toolchainContexts.getToolchainContext(execGroup).executionPlatform().label();
          }
        } else {
          executionPlatformLabel =
              toolchainContexts
                  .getToolchainContext(ExecGroup.DEFAULT_EXEC_GROUP_NAME)
                  .executionPlatform()
                  .label();
        }
      }

      AttributeTransitionData attributeTransitionData =
          AttributeTransitionData.builder()
              .attributes(attributeMap)
              .executionPlatform(executionPlatformLabel)
              .build();
      ConfigurationTransition attributeTransition =
          attribute.getTransitionFactory().create(attributeTransitionData);
      partiallyResolvedDeps.put(
          entry.getKey(),
          PartiallyResolvedDependency.builder()
              .setLabel(toLabel)
              .setTransition(attributeTransition)
              .setPropagatingAspects(propagatingAspects.build())
              .build());
    }
    return partiallyResolvedDeps;
  }

  /**
   * Factor in the properties of the target where the dependency points to in the dependency edge
   * calculation.
   *
   * <p>The target of the dependency edges depends on two things: the rule that depends on them and
   * the type of target they depend on. This function takes the rule into account. Accordingly, it
   * should <b>NOT</b> get the {@link Rule} instance representing the rule whose dependencies are
   * being calculated as an argument or its attributes and it should <b>NOT</b> do anything with the
   * keys of {@code partiallyResolvedDeps} other than passing them on to the output map.
   */
  private OrderedSetMultimap<DependencyKind, DependencyKey> fullyResolveDependencies(
      OrderedSetMultimap<DependencyKind, PartiallyResolvedDependency> partiallyResolvedDeps,
      Map<Label, Target> targetMap,
      BuildConfigurationValue originalConfiguration,
      @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory)
      throws InconsistentAspectOrderException {
    OrderedSetMultimap<DependencyKind, DependencyKey> outgoingEdges = OrderedSetMultimap.create();

    for (Map.Entry<DependencyKind, PartiallyResolvedDependency> entry :
        partiallyResolvedDeps.entries()) {
      PartiallyResolvedDependency partiallyResolvedDependency = entry.getValue();

      Target toTarget = targetMap.get(partiallyResolvedDependency.getLabel());
      if (toTarget == null) {
        // Dependency pointing to non-existent target. This error was reported in getTargets(), so
        // we can just ignore this dependency.
        continue;
      }

      ConfigurationTransition transition =
          TransitionResolver.evaluateTransition(
              originalConfiguration,
              partiallyResolvedDependency.getTransition(),
              toTarget,
              trimmingTransitionFactory);

      AspectCollection requiredAspects =
          computeAspectCollections(partiallyResolvedDependency.getPropagatingAspects(), toTarget);

      DependencyKey.Builder dependencyKeyBuilder =
          partiallyResolvedDependency.getDependencyKeyBuilder();
      outgoingEdges.put(
          entry.getKey(),
          dependencyKeyBuilder.setTransition(transition).setAspects(requiredAspects).build());
    }
    return outgoingEdges;
  }

  /** A DependencyResolver.Failure indicates a failure during dependency resolution. */
  public static class Failure extends Exception {
    @Nullable private final Location location;

    private Failure(Location location, String message) {
      super(message);
      this.location = location;
    }

    /** Returns the location of the error, if known. */
    @Nullable
    public Location getLocation() {
      return location;
    }
  }

  private void visitRule(
      TargetAndConfiguration node,
      BuildConfigurationValue hostConfig,
      Iterable<Aspect> aspects,
      ConfiguredAttributeMapper attributeMap,
      @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels)
      throws Failure {
    Preconditions.checkArgument(node.getTarget() instanceof Rule, node);
    BuildConfigurationValue ruleConfig = Preconditions.checkNotNull(node.getConfiguration(), node);
    Rule rule = (Rule) node.getTarget();

    try {
      attributeMap.validateAttributes();
    } catch (ConfiguredAttributeMapper.ValidationException ex) {
      throw new Failure(rule.getLocation(), ex.getMessage());
    }

    visitTargetVisibility(node, outgoingLabels);
    resolveAttributes(
        getAttributes(rule, aspects), outgoingLabels, rule, attributeMap, ruleConfig, hostConfig);

    // Add the rule's visibility labels (which may come from the rule or from package defaults).
    addExplicitDeps(outgoingLabels, rule, "visibility", rule.getVisibility().getDependencyLabels());

    // Add package default constraints when the rule doesn't explicitly declare them.
    //
    // Note that this can have subtle implications for constraint semantics. For example: say that
    // package defaults declare compatibility with ':foo' and rule R declares compatibility with
    // ':bar'. Does that mean that R is compatible with [':foo', ':bar'] or just [':bar']? In other
    // words, did R's author intend to add additional compatibility to the package defaults or to
    // override them? More severely, what if package defaults "restrict" support to just [':baz']?
    // Should R's declaration signify [':baz'] + ['bar'], [ORIGINAL_DEFAULTS] + ['bar'], or
    // something else?
    //
    // Rather than try to answer these questions with possibly confusing logic, we take the
    // simple approach of assigning the rule's "restriction" attribute to the rule-declared value if
    // it exists, else the package defaults value (and likewise for "compatibility"). This may not
    // always provide what users want, but it makes it easy for them to understand how rule
    // declarations and package defaults intermix (and how to refactor them to get what they want).
    //
    // An alternative model would be to apply the "rule declaration" / "rule class defaults"
    // relationship, i.e. the rule class' "compatibility" and "restriction" declarations are merged
    // to generate a set of default environments, then the rule's declarations are independently
    // processed on top of that. This protects against obscure coupling behavior between
    // declarations from wildly different places (e.g. it offers clear answers to the examples posed
    // above). But within the scope of a single package it seems better to keep the model simple and
    // make the user responsible for resolving ambiguities.
    if (!rule.isAttributeValueExplicitlySpecified(RuleClass.COMPATIBLE_ENVIRONMENT_ATTR)) {
      addExplicitDeps(
          outgoingLabels,
          rule,
          RuleClass.COMPATIBLE_ENVIRONMENT_ATTR,
          rule.getPackage().getDefaultCompatibleWith());
    }
    if (!rule.isAttributeValueExplicitlySpecified(RuleClass.RESTRICTED_ENVIRONMENT_ATTR)) {
      addExplicitDeps(
          outgoingLabels,
          rule,
          RuleClass.RESTRICTED_ENVIRONMENT_ATTR,
          rule.getPackage().getDefaultRestrictedTo());
    }

    if (toolchainContexts != null) {
      for (Map.Entry<String, ToolchainContext> entry :
          toolchainContexts.getContextMap().entrySet()) {
        outgoingLabels.putAll(
            DependencyKind.forExecGroup(entry.getKey()),
            entry.getValue().resolvedToolchainLabels());
      }
    }
  }

  private void resolveAttributes(
      Iterable<AttributeDependencyKind> attributeDependencyKinds,
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels,
      Rule rule,
      ConfiguredAttributeMapper attributeMap,
      BuildConfigurationValue ruleConfig,
      BuildConfigurationValue hostConfig) {
    for (AttributeDependencyKind dependencyKind : attributeDependencyKinds) {
      Attribute attribute = dependencyKind.getAttribute();
      if (!attribute.getCondition().apply(attributeMap)
          // Not only is resolving CONFIG_SETTING_DEPS_ATTRIBUTE deps here wasteful, since the only
          // place they're used is in ConfiguredTargetFunction.getConfigConditions, but it actually
          // breaks trimming as shown by
          // FeatureFlagManualTrimmingTest#featureFlagInUnusedSelectBranchButNotInTransitiveConfigs_DoesNotError
          // because it resolves a dep that trimming (correctly) doesn't account for because it's
          // part of an unchosen select() branch.
          || attribute.getName().equals(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE)) {
        continue;
      }

      Type<?> type = attribute.getType();
      if (type == BuildType.OUTPUT
          || type == BuildType.OUTPUT_LIST
          || type == BuildType.NODEP_LABEL
          || type == BuildType.NODEP_LABEL_LIST) {
        // These types invoke visitLabels() so that they are reported in "bazel query" but do not
        // create a dependency. Maybe it's better to remove that, but then the labels() query
        // function would need to be rethought.
        continue;
      }

      resolveAttribute(
          attribute,
          type,
          dependencyKind,
          outgoingLabels,
          rule,
          attributeMap,
          ruleConfig,
          hostConfig);
    }
  }

  private <T> void resolveAttribute(
      Attribute attribute,
      Type<T> type,
      AttributeDependencyKind dependencyKind,
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels,
      Rule rule,
      ConfiguredAttributeMapper attributeMap,
      BuildConfigurationValue ruleConfig,
      BuildConfigurationValue hostConfig) {
    T attributeValue = null;
    if (attribute.isImplicit()) {
      // Since the attributes that come from aspects do not appear in attributeMap, we have to get
      // their values from somewhere else. This incidentally means that aspects attributes are not
      // configurable. It would be nice if that wasn't the case, but we'd have to revamp how
      // attribute mapping works, which is a large chunk of work.
      if (dependencyKind.getOwningAspect() == null) {
        attributeValue = attributeMap.get(attribute.getName(), type);
      } else {
        Object defaultValue = attribute.getDefaultValue(rule);
        attributeValue =
            type.cast(
                defaultValue instanceof ComputedDefault
                    ? ((ComputedDefault) defaultValue).getDefault(attributeMap)
                    : defaultValue);
      }
    } else if (attribute.isLateBound()) {
      attributeValue =
          type.cast(resolveLateBoundDefault(rule, attributeMap, attribute, ruleConfig, hostConfig));
    } else if (attributeMap.has(attribute.getName())) {
      // This condition is false for aspect attributes that do not give rise to dependencies because
      // attributes that come from aspects do not appear in attributeMap (see the comment in the
      // case that handles implicit attributes).
      attributeValue = attributeMap.get(attribute.getName(), type);
    }

    if (attributeValue == null) {
      return;
    }

    type.visitLabels(
        (depLabel, ctx) -> outgoingLabels.put(dependencyKind, depLabel),
        attributeValue,
        /*context=*/ null);
  }

  @VisibleForTesting(/* used to test LateBoundDefaults' default values */ )
  public static <FragmentT> Object resolveLateBoundDefault(
      Rule rule,
      AttributeMap attributeMap,
      Attribute attribute,
      BuildConfigurationValue ruleConfig,
      BuildConfigurationValue hostConfig) {
    Preconditions.checkState(!attribute.getTransitionFactory().isSplit());
    @SuppressWarnings("unchecked")
    LateBoundDefault<FragmentT, ?> lateBoundDefault =
        (LateBoundDefault<FragmentT, ?>) attribute.getLateBoundDefault();
    BuildConfigurationValue attributeConfig =
        lateBoundDefault.useHostConfiguration() ? hostConfig : ruleConfig;

    Class<FragmentT> fragmentClass = lateBoundDefault.getFragmentClass();
    // TODO(b/65746853): remove this when nothing uses it anymore
    if (BuildConfigurationValue.class.equals(fragmentClass)) {
      return lateBoundDefault.resolve(rule, attributeMap, fragmentClass.cast(attributeConfig));
    }
    if (Void.class.equals(fragmentClass)) {
      return lateBoundDefault.resolve(rule, attributeMap, null);
    }
    @SuppressWarnings("unchecked")
    FragmentT fragment =
        fragmentClass.cast(attributeConfig.getFragment((Class<? extends Fragment>) fragmentClass));
    if (fragment == null) {
      return null;
    }
    return lateBoundDefault.resolve(rule, attributeMap, fragment);
  }

  /**
   * Adds new dependencies to the given rule under the given attribute name
   *
   * @param attrName the name of the attribute to add dependency labels to
   * @param labels the dependencies to add
   */
  private void addExplicitDeps(
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels,
      Rule rule,
      String attrName,
      Collection<Label> labels) {
    if (!rule.isAttrDefined(attrName, BuildType.LABEL_LIST)
        && !rule.isAttrDefined(attrName, BuildType.NODEP_LABEL_LIST)) {
      return;
    }
    Attribute attribute = rule.getRuleClassObject().getAttributeByName(attrName);
    outgoingLabels.putAll(AttributeDependencyKind.forRule(attribute), labels);
  }

  /**
   * Collects the aspects from {@code aspectsPath} that need to be propagated along the attribute
   * {@code attributeName}.
   *
   * <p>It can happen that some of the aspects cannot be propagated if the dependency doesn't have a
   * provider that's required by them. These will be filtered out after the rule class of the
   * dependency is known.
   */
  private static void collectPropagatingAspects(
      ImmutableList<Aspect> aspectsPath,
      String attributeName,
      BuildConfigurationValue config,
      @Nullable AspectClass aspectOwningAttribute,
      ImmutableList.Builder<Aspect> allFilteredAspects) {
    int aspectsNum = aspectsPath.size();
    ArrayList<Aspect> filteredAspectsPath = new ArrayList<>();

    for (int i = aspectsNum - 1; i >= 0; i--) {
      Aspect aspect = aspectsPath.get(i);
      if (!aspect.getDefinition().propagateViaAttribute().test(config, attributeName)) {
        // This condition is only included to support the migration to platform-based Android
        // toolchain selection. See DexArchiveAspect for details. One that migration is complete,
        // this logic should be removed on the principle of unnecessary complexity.
        continue;
      }
      if (aspect.getAspectClass().equals(aspectOwningAttribute)) {
        // Do not propagate over the aspect's own attributes.
        continue;
      }

      if (aspect.getDefinition().propagateAlong(attributeName)
          || isAspectRequired(aspect, filteredAspectsPath)) {
        // Add the aspect if it can propagate over this {@code attributeName} based on its
        // attr_aspects or it is required by an aspect already in the {@code filteredAspectsPath}.
        filteredAspectsPath.add(aspect);
      }
    }
    // Reverse filteredAspectsPath to return it to the same order as the input aspectsPath.
    Collections.reverse(filteredAspectsPath);
    allFilteredAspects.addAll(filteredAspectsPath);
  }

  /** Checks if {@code aspect} is required by any aspect in the {@code aspectsPath}. */
  private static boolean isAspectRequired(Aspect aspect, ArrayList<Aspect> aspectsPath) {
    for (Aspect existingAspect : aspectsPath) {
      if (existingAspect.getDefinition().requires(aspect)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the attributes that should be visited for this rule/aspect combination. */
  private ImmutableList<AttributeDependencyKind> getAttributes(
      Rule rule, Iterable<Aspect> aspects) {
    ImmutableList.Builder<AttributeDependencyKind> result = ImmutableList.builder();
    // If processing aspects, aspect attribute names may conflict with the attribute names of
    // rules they attach to. If this occurs, the highest-level aspect attribute takes precedence.
    HashSet<String> aspectProcessedAttributes = new HashSet<>();

    addAspectAttributes(aspects, aspectProcessedAttributes, result);
    List<Attribute> ruleDefs = rule.getRuleClassObject().getAttributes();
    for (Attribute attribute : ruleDefs) {
      if (!aspectProcessedAttributes.contains(attribute.getName())) {
        result.add(AttributeDependencyKind.forRule(attribute));
      }
    }
    return result.build();
  }

  private ImmutableList<AttributeDependencyKind> getAspectAttributes(Iterable<Aspect> aspects) {
    ImmutableList.Builder<AttributeDependencyKind> result = ImmutableList.builder();
    addAspectAttributes(aspects, new HashSet<>(), result);
    return result.build();
  }

  private void addAspectAttributes(
      Iterable<Aspect> aspects,
      Set<String> aspectProcessedAttributes,
      ImmutableList.Builder<AttributeDependencyKind> attributes) {
    for (Aspect aspect : aspects) {
      for (Attribute attribute : aspect.getDefinition().getAttributes().values()) {
        if (aspectProcessedAttributes.add(attribute.getName())) {
          attributes.add(AttributeDependencyKind.forAspect(attribute, aspect.getAspectClass()));
        }
      }
    }
  }
  /**
   * Compute the way aspects should be computed for the direct dependencies.
   *
   * <p>This is done by filtering the aspects that can be propagated on any attribute according to
   * the providers advertised by direct dependencies and by creating the {@link AspectCollection}
   * that tells how to compute the final set of providers based on the interdependencies between the
   * propagating aspects.
   */
  private static AspectCollection computeAspectCollections(
      ImmutableList<Aspect> aspects, Target toTarget) throws InconsistentAspectOrderException {
    if (toTarget instanceof OutputFile) {
      aspects =
          aspects.stream()
              .filter(aspect -> aspect.getDefinition().applyToGeneratingRules())
              .collect(ImmutableList.toImmutableList());
      toTarget = ((OutputFile) toTarget).getGeneratingRule();
    }

    if (!(toTarget instanceof Rule) || aspects.isEmpty()) {
      return AspectCollection.EMPTY;
    }

    Rule toRule = (Rule) toTarget;
    ArrayList<Aspect> filteredAspectPath = new ArrayList<>();
    AdvertisedProviderSet advertisedProviders =
        toRule.getRuleClassObject().getAdvertisedProviders();

    int aspectsNum = aspects.size();
    for (int i = aspectsNum - 1; i >= 0; i--) {
      Aspect aspect = aspects.get(i);
      if (aspect.getDefinition().getRequiredProviders().isSatisfiedBy(advertisedProviders)
          || isAspectRequired(aspect, filteredAspectPath)) {
        // Add the aspect if {@code advertisedProviders} satisfy its required providers or it is
        // required by an aspect already in the {@code filteredAspectPath}.
        filteredAspectPath.add(aspect);
      }
    }

    Collections.reverse(filteredAspectPath);
    try {
      return AspectCollection.create(filteredAspectPath);
    } catch (AspectCycleOnPathException e) {
      throw new InconsistentAspectOrderException(toTarget, e);
    }
  }

  private void visitTargetVisibility(
      TargetAndConfiguration node, OrderedSetMultimap<DependencyKind, Label> outgoingLabels) {
    Target target = node.getTarget();
    outgoingLabels.putAll(VISIBILITY_DEPENDENCY, target.getVisibility().getDependencyLabels());
  }

  private void checkPackageGroupVisibility(Rule fromRule, Map<Label, Target> targetMap)
      throws Failure {
    if (!(fromRule.getVisibility() instanceof PackageGroupsRuleVisibility)) {
      return;
    }

    for (Label label : fromRule.getVisibility().getDependencyLabels()) {
      if (targetMap.get(label) != null
          && !targetMap.get(label).getTargetKind().equals(PackageGroup.targetKind())) {
        throw new Failure(
            fromRule.getLocation(),
            String.format("Label '%s' does not refer to a package group.", label));
      }
    }
  }

  /**
   * Returns the targets for the given labels.
   *
   * <p>Returns null if any targets are not ready to be returned at this moment because of missing
   * Skyframe dependencies. If getTargets returns null once or more during a {@link
   * #dependentNodeMap} call, the results of that call will be incomplete. As is usual in these
   * situation, the caller must return control to Skyframe and wait for the SkyFunction to be
   * restarted, at which point the requested dependencies will be available.
   */
  protected abstract Map<Label, Target> getTargets(
      OrderedSetMultimap<DependencyKind, Label> labelMap,
      TargetAndConfiguration fromNode,
      NestedSetBuilder<Cause> rootCauses)
      throws InterruptedException;
}
