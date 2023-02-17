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
package com.google.devtools.build.lib.rules;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.VisibilityProvider;
import com.google.devtools.build.lib.analysis.VisibilityProviderImpl;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skyframe.BuildConfigurationKey;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.Structure;

/**
 * A {@link ConfiguredTarget} that pretends to be whatever type of target {@link #getActual} is,
 * mirroring its label and transitive info providers.
 *
 * <p>Transitive info providers may also be overridden. At a minimum, {@link #getProvider} provides
 * {@link AliasProvider} and an explicit {@link VisibilityProvider} which takes precedent over the
 * actual target's visibility.
 */
@Immutable
public final class AliasConfiguredTarget implements ConfiguredTarget, Structure {

  public static AliasConfiguredTarget create(
      RuleContext ruleContext,
      ConfiguredTarget actual,
      NestedSet<PackageGroupContents> visibility) {
    return createWithOverrides(
        ruleContext, actual, visibility, /*overrides=*/ ImmutableClassToInstanceMap.of());
  }

  public static AliasConfiguredTarget createWithOverrides(
      RuleContext ruleContext,
      ConfiguredTarget actual,
      NestedSet<PackageGroupContents> visibility,
      ImmutableClassToInstanceMap<TransitiveInfoProvider> overrides) {
    return new AliasConfiguredTarget(
        ruleContext.getLabel(),
        ruleContext.getConfigurationKey(),
        actual,
        ImmutableClassToInstanceMap.<TransitiveInfoProvider>builder()
            .put(AliasProvider.class, AliasProvider.fromAliasRule(ruleContext.getRule(), actual))
            .put(VisibilityProvider.class, new VisibilityProviderImpl(visibility))
            .putAll(overrides)
            .build(),
        ruleContext.getConfigConditions());
  }

  private final Label label;
  private final BuildConfigurationKey configurationKey;
  private final ConfiguredTarget actual;
  private final ImmutableClassToInstanceMap<TransitiveInfoProvider> overrides;
  private final ImmutableMap<Label, ConfigMatchingProvider> configConditions;

  private AliasConfiguredTarget(
      Label label,
      BuildConfigurationKey configurationKey,
      ConfiguredTarget actual,
      ImmutableClassToInstanceMap<TransitiveInfoProvider> overrides,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions) {
    this.label = checkNotNull(label);
    this.configurationKey = checkNotNull(configurationKey);
    this.actual = checkNotNull(actual);
    this.overrides = checkNotNull(overrides);
    this.configConditions = checkNotNull(configConditions);
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  @Override
  public ImmutableMap<Label, ConfigMatchingProvider> getConfigConditions() {
    return configConditions;
  }

  @Override
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> provider) {
    P p = overrides.getInstance(provider);
    return p != null ? p : actual.getProvider(provider);
  }

  @Override
  public Label getLabel() {
    return actual.getLabel();
  }

  @Override
  public Object get(String providerKey) {
    return actual.get(providerKey);
  }

  @Nullable
  @Override
  public Info get(Provider.Key providerKey) {
    return actual.get(providerKey);
  }

  @Override
  public Object getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
    return actual.getIndex(semantics, key);
  }

  @Override
  public boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException {
    return actual.containsKey(semantics, key);
  }

  @Override
  public BuildConfigurationKey getConfigurationKey() {
    // This does not return actual.getConfigurationKey() because actual might be an input file, in
    // which case its configurationKey is null and we don't want to have rules that have a null
    // configurationKey.
    return configurationKey;
  }

  /* Structure methods */

  @Override
  public Object getValue(String name) {
    if (name.equals(LABEL_FIELD)) {
      return getLabel();
    } else if (name.equals(FILES_FIELD)) {
      // A shortcut for files to build in Starlark. FileConfiguredTarget and RuleConfiguredTarget
      // always has FileProvider and Error- and PackageGroupConfiguredTarget-s shouldn't be
      // accessible in Starlark.
      return Depset.of(Artifact.TYPE, getProvider(FileProvider.class).getFilesToBuild());
    }
    return actual.getValue(name);
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return actual.getFieldNames();
  }

  @Override
  public String getErrorMessageForUnknownField(String name) {
    // Use the default error message.
    return null;
  }

  @Override
  public ConfiguredTarget getActual() {
    // This will either dereference an alias chain, or return the final ConfiguredTarget.
    return actual.getActual();
  }

  @Override
  public Label getOriginalLabel() {
    return label;
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<alias target " + label + " of " + actual.getLabel() + ">");
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("label", label)
        .add("configurationKey", configurationKey)
        .add("actual", actual)
        .add("overrides", overrides)
        .add("configConditions", configConditions)
        .toString();
  }
}
