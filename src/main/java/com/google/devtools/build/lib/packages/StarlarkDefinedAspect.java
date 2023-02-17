// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Ascii;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.EventHandler;
import java.util.Objects;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkInt;

/** A Starlark value that is a result of an 'aspect(..)' function call. */
public final class StarlarkDefinedAspect implements StarlarkExportable, StarlarkAspect {
  private final StarlarkCallable implementation;
  private final ImmutableList<String> attributeAspects;
  private final ImmutableList<Attribute> attributes;
  private final ImmutableList<ImmutableSet<StarlarkProviderIdentifier>> requiredProviders;
  private final ImmutableList<ImmutableSet<StarlarkProviderIdentifier>> requiredAspectProviders;
  private final ImmutableSet<StarlarkProviderIdentifier> provides;

  /** Aspect attributes that are required to be specified by rules propagating this aspect. */
  private final ImmutableSet<String> paramAttributes;

  private final ImmutableSet<StarlarkAspect> requiredAspects;
  private final ImmutableSet<String> fragments;
  private final ConfigurationTransition hostTransition;
  private final ImmutableSet<String> hostFragments;
  private final ImmutableSet<ToolchainTypeRequirement> toolchainTypes;
  private final boolean applyToGeneratingRules;
  private final ImmutableSet<Label> execCompatibleWith;
  private final ImmutableMap<String, ExecGroup> execGroups;

  private StarlarkAspectClass aspectClass;

  private static final ImmutableSet<String> TRUE_REPS =
      ImmutableSet.of("true", "1", "yes", "t", "y");

  private static final ImmutableSet<String> FALSE_REPS =
      ImmutableSet.of("false", "0", "no", "f", "n");

  public StarlarkDefinedAspect(
      StarlarkCallable implementation,
      ImmutableList<String> attributeAspects,
      ImmutableList<Attribute> attributes,
      ImmutableList<ImmutableSet<StarlarkProviderIdentifier>> requiredProviders,
      ImmutableList<ImmutableSet<StarlarkProviderIdentifier>> requiredAspectProviders,
      ImmutableSet<StarlarkProviderIdentifier> provides,
      ImmutableSet<String> paramAttributes,
      ImmutableSet<StarlarkAspect> requiredAspects,
      ImmutableSet<String> fragments,
      // The host transition is in lib.analysis, so we can't reference it directly here.
      ConfigurationTransition hostTransition,
      ImmutableSet<String> hostFragments,
      ImmutableSet<ToolchainTypeRequirement> toolchainTypes,
      boolean applyToGeneratingRules,
      ImmutableSet<Label> execCompatibleWith,
      ImmutableMap<String, ExecGroup> execGroups) {
    this.implementation = implementation;
    this.attributeAspects = attributeAspects;
    this.attributes = attributes;
    this.requiredProviders = requiredProviders;
    this.requiredAspectProviders = requiredAspectProviders;
    this.provides = provides;
    this.paramAttributes = paramAttributes;
    this.requiredAspects = requiredAspects;
    this.fragments = fragments;
    this.hostTransition = hostTransition;
    this.hostFragments = hostFragments;
    this.toolchainTypes = toolchainTypes;
    this.applyToGeneratingRules = applyToGeneratingRules;
    this.execCompatibleWith = execCompatibleWith;
    this.execGroups = execGroups;
  }

  public StarlarkCallable getImplementation() {
    return implementation;
  }

  public ImmutableList<String> getAttributeAspects() {
    return attributeAspects;
  }

  public ImmutableList<Attribute> getAttributes() {
    return attributes;
  }

  @Override
  public boolean isImmutable() {
    return implementation.isImmutable();
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<aspect>");
  }

  @Override
  public String getName() {
    return getAspectClass().getName();
  }

  @Override
  public StarlarkAspectClass getAspectClass() {
    Preconditions.checkState(isExported());
    return aspectClass;
  }

  @Override
  public ImmutableSet<String> getParamAttributes() {
    return paramAttributes;
  }

  @Override
  public void export(EventHandler handler, Label extensionLabel, String name) {
    Preconditions.checkArgument(!isExported());
    this.aspectClass = new StarlarkAspectClass(extensionLabel, name);
  }

  private static final ImmutableList<String> ALL_ATTR_ASPECTS = ImmutableList.of("*");

  /**
   * The <code>AspectDefinition</code> is a function of the aspect's parameters, so we can cache
   * that.
   *
   * <p>Parameters of Starlark aspects are combinatorially limited (only bool, int and enum types).
   * Using strong keys possibly results in a small memory leak. Weak keys don't work because
   * reference equality is used and AspectParameters are created per target.
   */
  private transient LoadingCache<AspectParameters, AspectDefinition> definitionCache =
      Caffeine.newBuilder().build(this::buildDefinition);

  public AspectDefinition getDefinition(AspectParameters aspectParams) {
    if (definitionCache == null) {
      definitionCache = Caffeine.newBuilder().build(this::buildDefinition);
    }
    return definitionCache.get(aspectParams);
  }

  private AspectDefinition buildDefinition(AspectParameters aspectParams) {
    AspectDefinition.Builder builder = new AspectDefinition.Builder(aspectClass);
    if (ALL_ATTR_ASPECTS.equals(attributeAspects)) {
      builder.propagateAlongAllAttributes();
    } else {
      for (String attributeAspect : attributeAspects) {
        builder.propagateAlongAttribute(attributeAspect);
      }
    }

    for (Attribute attribute : attributes) {
      Attribute attr = attribute; // Might be reassigned.
      if (!aspectParams.getAttribute(attr.getName()).isEmpty()) {
        Type<?> attrType = attr.getType();
        String attrName = attr.getName();
        String attrValue = aspectParams.getOnlyValueOfAttribute(attrName);
        Preconditions.checkState(!Attribute.isImplicit(attrName));
        Preconditions.checkState(
            attrType == Type.STRING || attrType == Type.INTEGER || attrType == Type.BOOLEAN);
        Preconditions.checkArgument(
            aspectParams.getAttribute(attrName).size() == 1,
            "Aspect %s parameter %s has %s values (must have exactly 1).",
            getName(),
            attrName,
            aspectParams.getAttribute(attrName).size());

        attr = addAttrValue(attr, attrValue);
      }
      builder.add(attr);
    }
    builder.requireStarlarkProviderSets(requiredProviders);
    builder.requireAspectsWithProviders(requiredAspectProviders);
    ImmutableList.Builder<StarlarkProviderIdentifier> advertisedStarlarkProviders =
        ImmutableList.builder();
    for (StarlarkProviderIdentifier provider : provides) {
      advertisedStarlarkProviders.add(provider);
    }
    builder.advertiseProvider(advertisedStarlarkProviders.build());
    builder.requiresConfigurationFragmentsByStarlarkBuiltinName(fragments);
    builder.requiresConfigurationFragmentsByStarlarkBuiltinName(hostTransition, hostFragments);
    builder.addToolchainTypes(toolchainTypes);
    builder.applyToGeneratingRules(applyToGeneratingRules);
    ImmutableSet.Builder<AspectClass> requiredAspectsClasses = ImmutableSet.builder();
    for (StarlarkAspect requiredAspect : requiredAspects) {
      requiredAspectsClasses.add(requiredAspect.getAspectClass());
    }
    builder.requiredAspectClasses(requiredAspectsClasses.build());
    builder.execCompatibleWith(execCompatibleWith);
    builder.execGroups(execGroups);
    return builder.build();
  }

  private static Attribute addAttrValue(Attribute attr, String attrValue) {
    Attribute.Builder<?> attrBuilder;
    Type<?> attrType = attr.getType();
    Object castedValue = attrValue;

    if (attrType == Type.INTEGER) {
      castedValue = StarlarkInt.parse(attrValue, /*base=*/ 0);
      attrBuilder = attr.cloneBuilder(Type.INTEGER).value((StarlarkInt) castedValue);
    } else if (attrType == Type.BOOLEAN) {
      castedValue = Boolean.parseBoolean(attrValue);
      attrBuilder = attr.cloneBuilder(Type.BOOLEAN).value((Boolean) castedValue);
    } else {
      attrBuilder = attr.cloneBuilder(Type.STRING).value((String) castedValue);
    }

    if (!attr.checkAllowedValues()) {
      // The aspect attribute can have no allowed values constraint if the aspect is used from
      // command-line. However, AspectDefinition.Builder$add requires the existence of allowed
      // values in all aspects string attributes for both native and starlark aspects.
      // Therefore, allowedValues list is added here with only the current value of the attribute.
      return attrBuilder
          .allowedValues(new Attribute.AllowedValueSet(attrType.cast(castedValue)))
          .build(attr.getName());
    } else {
      return attrBuilder.build(attr.getName());
    }
  }

  @Override
  public boolean isExported() {
    return aspectClass != null;
  }

  @Override
  public Function<Rule, AspectParameters> getDefaultParametersExtractor() {
    // This is serialized along with AspectsListBuilder.StarlarkAspectDetail and should not be
    // turned into a lambda.
    return new Function<Rule, AspectParameters>() {
      @Override
      public AspectParameters apply(Rule rule) {
        AttributeMap ruleAttrs = RawAttributeMapper.of(rule);
        AspectParameters.Builder builder = new AspectParameters.Builder();
        for (Attribute aspectAttr : attributes) {
          String param = aspectAttr.getName();
          if (Attribute.isImplicit(param) || Attribute.isLateBound(param)) {
            // These attributes are the private matters of the aspect
            continue;
          }

          Attribute ruleAttr = ruleAttrs.getAttributeDefinition(param);
          if (paramAttributes.contains(aspectAttr.getName())) {
            // These are preconditions because if they are false, RuleFunction.call() should
            // already have generated an error.
            Preconditions.checkArgument(
                ruleAttr != null,
                "Cannot apply aspect %s to %s that does not define attribute '%s'.",
                getName(),
                rule.getTargetKind(),
                param);
            Preconditions.checkArgument(
                ruleAttr.getType() == Type.STRING
                    || ruleAttr.getType() == Type.INTEGER
                    || ruleAttr.getType() == Type.BOOLEAN,
                "Cannot apply aspect %s to %s since attribute '%s' is not boolean, integer, nor"
                    + " string.",
                getName(),
                rule.getTargetKind(),
                param);
          }

          if (ruleAttr != null && ruleAttr.getType() == aspectAttr.getType()) {
            // If the attribute has a select() (which aspect attributes don't yet support), the
            // error gets reported in RuleClass.checkAspectAllowedValues.
            if (!ruleAttrs.isConfigurable(param)) {
              builder.addAttribute(param, ruleAttrs.get(param, ruleAttr.getType()).toString());
            }
          }
        }
        return builder.build();
      }
    };
  }

  public AspectParameters extractTopLevelParameters(ImmutableMap<String, String> parametersValues)
      throws EvalException {
    AspectParameters.Builder builder = new AspectParameters.Builder();
    for (Attribute aspectParameter : attributes) {
      String parameterName = aspectParameter.getName();
      Type<?> parameterType = aspectParameter.getType();

      if (Attribute.isImplicit(parameterName) || Attribute.isLateBound(parameterName)) {
        // These attributes are the private matters of the aspect
        continue;
      }

      Preconditions.checkArgument(
          parameterType == Type.STRING
              || parameterType == Type.INTEGER
              || parameterType == Type.BOOLEAN,
          "Aspect %s: Cannot pass value of attribute '%s' of type %s, only 'boolean', 'int' and"
              + " 'string' attributes are allowed.",
          getName(),
          parameterName,
          parameterType);

      String parameterValue =
          parametersValues.getOrDefault(
              parameterName, parameterType.cast(aspectParameter.getDefaultValue(null)).toString());

      Object castedParameterValue = parameterValue;
      // Validate integer and boolean parameters values
      if (parameterType == Type.INTEGER) {
        castedParameterValue = parseIntParameter(parameterName, parameterValue);
      } else if (parameterType == Type.BOOLEAN) {
        castedParameterValue = parseBooleanParameter(parameterName, parameterValue);
      }

      if (aspectParameter.checkAllowedValues()) {
        PredicateWithMessage<Object> allowedValues = aspectParameter.getAllowedValues();
        if (!allowedValues.apply(castedParameterValue)) {
          throw Starlark.errorf(
              "%s: invalid value in '%s' attribute: %s",
              getName(), parameterName, allowedValues.getErrorReason(castedParameterValue));
        }
      }
      builder.addAttribute(parameterName, castedParameterValue.toString());
    }
    return builder.build();
  }

  private StarlarkInt parseIntParameter(String name, String value) throws EvalException {
    try {
      return StarlarkInt.parse(value, /*base=*/ 0);
    } catch (NumberFormatException e) {
      throw new EvalException(
          String.format(
              "%s: expected value of type 'int' for attribute '%s' but got '%s'",
              getName(), name, value),
          e);
    }
  }

  private Boolean parseBooleanParameter(String name, String value) throws EvalException {
    value = Ascii.toLowerCase(value);
    if (TRUE_REPS.contains(value)) {
      return true;
    }
    if (FALSE_REPS.contains(value)) {
      return false;
    }
    throw Starlark.errorf(
        "%s: expected value of type 'bool' for attribute '%s' but got '%s'",
        getName(), name, value);
  }

  public ImmutableSet<ToolchainTypeRequirement> getToolchainTypes() {
    return toolchainTypes;
  }

  @Override
  public void attachToAspectsList(String baseAspectName, AspectsListBuilder aspectsList)
      throws EvalException {

    if (!this.isExported()) {
      throw Starlark.errorf(
          "Aspects should be top-level values in extension files that define them.");
    }

    for (StarlarkAspect requiredAspect : requiredAspects) {
      requiredAspect.attachToAspectsList(this.getName(), aspectsList);
    }

    aspectsList.addAspect(this, baseAspectName);
  }

  public ImmutableSet<StarlarkAspect> getRequiredAspects() {
    return requiredAspects;
  }

  public ImmutableList<ImmutableSet<StarlarkProviderIdentifier>> getRequiredProviders() {
    return requiredProviders;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StarlarkDefinedAspect that = (StarlarkDefinedAspect) o;
    return Objects.equals(implementation, that.implementation)
        && Objects.equals(attributeAspects, that.attributeAspects)
        && Objects.equals(attributes, that.attributes)
        && Objects.equals(requiredProviders, that.requiredProviders)
        && Objects.equals(requiredAspectProviders, that.requiredAspectProviders)
        && Objects.equals(provides, that.provides)
        && Objects.equals(paramAttributes, that.paramAttributes)
        && Objects.equals(requiredAspects, that.requiredAspects)
        && Objects.equals(fragments, that.fragments)
        && Objects.equals(hostTransition, that.hostTransition)
        && Objects.equals(hostFragments, that.hostFragments)
        && Objects.equals(toolchainTypes, that.toolchainTypes)
        && Objects.equals(aspectClass, that.aspectClass);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        implementation,
        attributeAspects,
        attributes,
        requiredProviders,
        requiredAspectProviders,
        provides,
        paramAttributes,
        requiredAspects,
        fragments,
        hostTransition,
        hostFragments,
        toolchainTypes,
        aspectClass);
  }
}
