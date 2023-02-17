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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for StarlarkRuleTransitionProvider. */
@RunWith(TestParameterInjector.class)
public final class StarlarkRuleTransitionProviderTest extends BuildViewTestCase {

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestFragment.class);
    return builder.build();
  }

  private void writeAllowlistFile() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        "package_group(",
        "    name = 'function_transition_allowlist',",
        "    packages = [",
        "        '//test/...',",
        "    ],",
        ")");
  }

  @Test
  public void testBadReturnTypeFromTransition() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return 'cpu=k8'",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("transition function returned string, want dict or list of dicts");
  }

  @Test
  public void testOutputOnlyTransition() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:foo': 'post-transition'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  @Test
  public void testInputAndOutputTransition() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:foo': ",
        "    settings['//command_line_option:foo']+'->post-transition'}",
        "my_transition = transition(",
        "  implementation = _impl,",
        "  inputs = ['//command_line_option:foo'],",
        "  outputs = ['//command_line_option:foo'],",
        ")");

    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");

    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("pre-transition->post-transition");
  }

  @Test
  public void testBuildSettingCannotTransition() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:foo': 'post-transition'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  build_setting = config.string(),",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "Build setting rules cannot use the `cfg` param to apply transitions to themselves");
  }

  @Test
  public void testBadCfgInput() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/rules.bzl",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = 'my_transition',",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "`cfg` must be set to a transition object initialized by the transition() function.");
  }

  @Test
  public void testMultipleReturnConfigs() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return [",
        "      {'//command_line_option:foo': 'split_one'},",
        "      {'//command_line_option:foo': 'split_two'},",
        "  ]",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "Rule transition only allowed to return a single transitioned configuration.");
  }

  @Test
  public void testCanDoBadStuffWithParameterizedTransitionsAndSelects() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  if (attr.my_configurable_attr):",
        "    return {'//command_line_option:foo': 'true'}",
        "  else:",
        "    return {'//command_line_option:foo': 'false'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    'my_configurable_attr': attr.bool(default = False),",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'test',",
        "  my_configurable_attr = select({",
        "    '//conditions:default': False,",
        "    ':true-config': True,",
        "  })",
        ")",
        "config_setting(",
        "  name = 'true-config',",
        "  values = {'foo': 'true'},",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "No attribute 'my_configurable_attr'. "
            + "Either this attribute does not exist for this rule or is set by a select. "
            + "Starlark rule transitions currently cannot read attributes behind selects.");
  }

  @Test
  public void testLabelTypedAttrReturnsLabelNotDep() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  if attr.dict_attr[Label('//test:key')] == 'value':",
        "    return {'//command_line_option:foo': 'post-transition'}",
        "  else:",
        "    return {'//command_line_option:foo': 'uh-oh'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    'dict_attr': attr.label_keyed_string_dict(),",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")",
        "simple_rule = rule(_impl)");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule', 'simple_rule')",
        "my_rule(",
        "  name = 'test',",
        "  dict_attr = {':key': 'value'},",
        ")",
        "simple_rule(name = 'key')");

    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  private static final String CUTE_ANIMAL_DEFAULT =
      "cows produce more milk when they listen to soothing music";

  private void writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");

    scratch.file(
        "test/build_settings.bzl",
        "def _impl(ctx):",
        "  return []",
        "string_flag = rule(implementation = _impl, build_setting = config.string(flag=True))");

    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");
  }

  @Test
  public void testTransitionOnBuildSetting_fromDefault() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': 'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseAbsoluteUnchecked("//test:cute-animal-fact")))
        .isEqualTo("puffins mate for life");
  }

  @Test
  public void testTransitionOnBuildSetting_fromCommandLine() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': 'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration(ImmutableMap.of("//test:cute-animal-fact", "cats can't taste sugar"));

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseAbsoluteUnchecked("//test:cute-animal-fact")))
        .isEqualTo("puffins mate for life");
  }

  @Test
  public void testTransitionOnBuildSetting_badValue() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': 24}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration(ImmutableMap.of("//test:cute-animal-fact", "cats can't taste sugar"));

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "expected value of type 'string' for " + "//test:cute-animal-fact, but got 24 (int)");
  }

  @Test
  public void testTransitionOnBuildSetting_noSuchTarget() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:i-am-not-real': 'imaginary-friend'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:i-am-not-real']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "no such target '//test:i-am-not-real': target "
            + "'i-am-not-real' not declared in package 'test'");
  }

  @Test
  public void testTransitionOnBuildSetting_noSuchPackage() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//i-am-not-real': 'imaginary-friend'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//i-am-not-real']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("no such package 'i-am-not-real': Unable to find build setting package");
  }

  @Test
  public void testTransitionOnBuildSetting_notABuildSetting() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': 'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file(
        "test/build_settings.bzl",
        "def _impl(ctx):",
        "  return []",
        "non_build_setting = rule(implementation = _impl)");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'non_build_setting')",
        "my_rule(name = 'test')",
        "non_build_setting(name = 'cute-animal-fact')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "attempting to transition on '//test:cute-animal-fact' which is not a build setting");
  }

  @Test
  public void testTransitionOnBuildSetting_dontStoreDefault() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': '" + CUTE_ANIMAL_DEFAULT + "'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration(ImmutableMap.of("//test:cute-animal-fact", "cats can't taste sugar"));

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().getStarlarkOptions())
        .doesNotContainKey(Label.parseAbsoluteUnchecked("//test:cute-animal-fact"));
  }

  @Test
  public void testTransitionReadsBuildSetting_fromDefault() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': settings['//test:cute-animal-fact']+' <- TRUE'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = ['//test:cute-animal-fact'],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseAbsoluteUnchecked("//test:cute-animal-fact")))
        .isEqualTo("cows produce more milk when they listen to soothing music <- TRUE");
  }

  @Test
  public void testTransitionReadsBuildSetting_fromCommandLine() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': settings['//test:cute-animal-fact']+' <- TRUE'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = ['//test:cute-animal-fact'],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration(ImmutableMap.of("//test:cute-animal-fact", "rats are ticklish"));

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseAbsoluteUnchecked("//test:cute-animal-fact")))
        .isEqualTo("rats are ticklish <- TRUE");
  }

  @Test
  public void testTransitionReadsBuildSetting_notABuildSetting() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': 'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = ['//test:cute-animal-fact'],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file(
        "test/build_settings.bzl",
        "def _impl(ctx):",
        "  return []",
        "non_build_setting = rule(implementation = _impl)");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'non_build_setting')",
        "my_rule(name = 'test')",
        "non_build_setting(name = 'cute-animal-fact')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "attempting to transition on '//test:cute-animal-fact' which is not a build setting");
  }

  @Test
  public void testTransitionReadsBuildSetting_noSuchTarget() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': settings['//test:cute-animal-fact']+' <- TRUE'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = ['//test:i-am-not-real'],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "no such target '//test:i-am-not-real': target "
            + "'i-am-not-real' not declared in package 'test'");
  }

  @Test
  public void testAliasedBuildSetting() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:fact':  'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'fact', actual = ':cute-animal-fact')",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    useConfiguration(ImmutableMap.of("//test:cute-animal-fact", "rats are ticklish"));

    ImmutableMap<Label, Object> starlarkOptions =
        getConfiguration(getConfiguredTarget("//test")).getOptions().getStarlarkOptions();
    assertThat(starlarkOptions.get(Label.parseAbsoluteUnchecked("//test:cute-animal-fact")))
        .isEqualTo("puffins mate for life");
    assertThat(starlarkOptions).doesNotContainKey(Label.parseAbsoluteUnchecked("//test:fact"));
    assertThat(starlarkOptions.keySet())
        .containsExactly(Label.parseAbsoluteUnchecked("//test:cute-animal-fact"));
  }

  @Test
  public void testAliasedBuildSetting_chainedAliases() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:fact':  'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'fact', actual = ':alias2')",
        "alias(name = 'alias2', actual = ':cute-animal-fact')",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    useConfiguration(ImmutableMap.of("//test:cute-animal-fact", "rats are ticklish"));

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseAbsoluteUnchecked("//test:cute-animal-fact")))
        .isEqualTo("puffins mate for life");
  }

  @Test
  public void testAliasedBuildSetting_configuredActualValue() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:fact':  'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(",
        "  name = 'fact',",
        "  actual = select({",
        "    '//conditions:default': ':cute-animal-fact',",
        "    ':true-config': 'other-cute-animal-fact',",
        "  })",
        ")",
        "config_setting(",
        "  name = 'true-config',",
        "  values = {'foo': 'true'},",
        ")",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")",
        "string_flag(",
        "  name = 'other-cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "attempting to transition on aliased build setting '//test:fact', the actual value of"
            + " which uses select().");
  }

  @Test
  public void testAliasedBuildSetting_cyclicalAliases() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:alias1':  'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:alias1']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'alias1', actual = ':alias2')",
        "alias(name = 'alias2', actual = ':alias1')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("Error with aliased build settings related to '//test:alias1'.");
  }

  @Test
  public void testAliasedBuildSetting_setAliasAndActual() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {",
        "    '//test:alias':  'puffins mate for life',",
        "    '//test:actual':  'cats cannot taste sugar',",
        "}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = [",
        "    '//test:alias',",
        "    '//test:actual',",
        "  ]",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'alias', actual = ':actual')",
        "string_flag(",
        "  name = 'actual',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("Error with aliased build settings related to '//test:actual'.");
  }

  @Test
  public void testAliasedBuildSetting_outputReturnMismatch() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {",
        "    '//test:actual':  'cats cannot taste sugar',",
        "}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = [",
        "    '//test:alias',",
        "  ]",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'alias', actual = ':actual')",
        "string_flag(",
        "  name = 'actual',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("transition function returned undeclared output '//test:actual'");
  }

  @Test
  public void testOneParamTransitionFunctionApiFails() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings):",
        "  return {'//command_line_option:foo': 'post-transition'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("_impl() accepts no more than 1 positional argument but got 2");
  }

  @Test
  public void testCannotTransitionOnExperimentalFlag() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:experimental_something_something': True}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:experimental_something_something'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("Cannot transition on --experimental_* or --incompatible_* options");
  }

  @Test
  public void testCannotTransitionWithoutAllowlist() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        "package_group(",
        "    name = 'function_transition_allowlist',",
        "    packages = [],",
        ")");
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:foo': 'post-transition'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        ")");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    useConfiguration("--foo=pre-transition");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("Use of Starlark transition without allowlist");
  }

  @Test
  public void testNoNullOptionValues() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  if settings['//command_line_option:nullable_option'] == None:",
        "    return {'//command_line_option:foo': 'post-transition'}",
        "  else:",
        "    return {'//command_line_option:foo': settings['//command_line_option:foo']}",
        "my_transition = transition(implementation = _impl,",
        "  inputs = [",
        "    '//command_line_option:foo',",
        "    '//command_line_option:nullable_option'",
        "  ],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    useConfiguration("--nullable_option=", "--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  @Test
  public void testAllowlistOnRuleNotTargets() throws Exception {
    // allowlists //test/...
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:foo': 'post-transition'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "neverland/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");
    scratch.file("test/BUILD");
    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration =
        getConfiguration(getConfiguredTarget("//neverland:test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  // TODO(blaze-configurability): We probably want to eventually turn this off. Flip this test when
  // this isn't allowed anymore.
  @Test
  public void testAllowlistOnTargetsStillWorks() throws Exception {
    // allowlists //test/...
    writeAllowlistFile();
    scratch.file(
        "neverland/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:foo': 'post-transition'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "neverland/rules.bzl",
        "load('//neverland:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "test/BUILD", "load('//neverland:rules.bzl', 'my_rule')", "my_rule(name = 'test')");
    scratch.file("neverland/BUILD");
    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  @Test
  @TestParameters({
    "{"
        + "returnLine: 'return []',"
        + "returnLine: 'return {}',"
        + "returnLine: 'return None',"
        + "returnLine: 'pass',"
        + "}"
  })
  public void noopReturnValues(String returnLine) throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  " + returnLine,
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");
    // --trim_test_configuration means only the top-level configuration has TestOptions.
    assertConfigurationsEqual(
        getConfiguration(getConfiguredTarget("//test")),
        targetConfig,
        ImmutableSet.of(TestOptions.class));
  }

  @Test
  public void composingTransitionReportsAllStarlarkErrors() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/build_settings.bzl",
        "def _impl(ctx):",
        "  return []",
        "string_flag = rule(implementation = _impl, build_setting = config.string(flag=True))");
    scratch.file(
        "test/transitions.bzl",
        "def _attr_impl(settings, attr):",
        "  return {'//test:attr_transition_output_flag1': 'not default'}",
        "attr_transition = transition(implementation = _attr_impl, inputs = [],",
        "  outputs = [",
        "      '//test:attr_transition_output_flag1',",
        "      '//test:attr_transition_output_flag2',",
        "  ])",
        "def _self_impl(settings, attr):",
        "  return {'//test:self_transition_output_flag1': 'not default'}",
        "self_transition = transition(implementation = _self_impl, inputs = [],",
        "  outputs = [",
        "      '//test:self_transition_output_flag1',",
        "      '//test:self_transition_output_flag2',",
        "  ])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'attr_transition', 'self_transition')",
        "def _impl(ctx):",
        "  return []",
        "rule_with_attr_transition = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist'),",
        "    'deps': attr.label_list(cfg = attr_transition),",
        "  })",
        "rule_with_self_transition = rule(",
        "  implementation = _impl,",
        "  cfg = self_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist'),",
        "  })");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'rule_with_attr_transition', 'rule_with_self_transition')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "string_flag(name = 'attr_transition_output_flag1', build_setting_default='')",
        "string_flag(name = 'attr_transition_output_flag2', build_setting_default='')",
        "string_flag(name = 'self_transition_output_flag1', build_setting_default='')",
        "string_flag(name = 'self_transition_output_flag2', build_setting_default='')",
        "rule_with_attr_transition(name = 'buildme', deps = [':adep'])",
        "rule_with_self_transition(name = 'adep')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:buildme");
    assertContainsEvent(
        "transition outputs [//test:attr_transition_output_flag2] were not defined by transition "
            + "function");
    assertContainsEvent(
        "transition outputs [//test:self_transition_output_flag2] were not defined by transition "
            + "function");
  }

  @Test
  public void testTransitionOnDefine() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:define': 'chonky=true'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:define'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("Starlark transition on --define not supported - try using build settings");
  }

  @Test
  public void successfulTypeConversionOfNativeListOption() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:platforms': ['//test:my_platform']}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:platforms'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "platform(name = 'my_platform')",
        "my_rule(name = 'test')");

    getConfiguredTarget("//test");
    assertNoEvents();
  }

  // Regression test for b/170729565
  @Test
  public void testSetBooleanNativeOptionWithStarlarkBoolean() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:bool': True}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:bool'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");
    useConfiguration("--bool=false");
    ConfiguredTarget ct = getConfiguredTarget("//test");
    assertNoEvents();
    assertThat(getConfiguration(ct).getOptions().get(DummyTestOptions.class).bool).isTrue();
  }

  // Regression test for b/170729565
  @Test
  public void testSetBooleanNativeOptionWithItself() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:bool': settings['//command_line_option:bool']}",
        "my_transition = transition(implementation = _impl,",
        "  inputs = ['//command_line_option:bool'],",
        "  outputs = ['//command_line_option:bool'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");
    useConfiguration("--bool=false");
    ConfiguredTarget ct = getConfiguredTarget("//test");
    assertNoEvents();
    assertThat(getConfiguration(ct).getOptions().get(DummyTestOptions.class).bool).isFalse();
  }

  @Test
  public void failedTypeConversionOfNativeListOption() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:platforms': ['this is not a valid label::']}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:platforms'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "platform(name = 'my_platform')",
        "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("invalid target name ':': target names may not contain ':'");
  }

  @Test
  public void successfulTypeConversionOfNativeListOptionEmptyList() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:fission': []}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:fission'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "platform(name = 'my_platform')",
        "my_rule(name = 'test')");

    ConfiguredTarget ct = getConfiguredTarget("//test");
    assertNoEvents();
    assertThat(getConfiguration(ct).getOptions().get(CppOptions.class).fissionModes).isEmpty();
  }

  @Test
  public void failedTypeConversionOfNativeListOptionNone() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:copt': None}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:copt'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "'None' value not allowed for List-type option 'copt'. Please use '[]' instead if trying"
            + " to set option to empty value.");
  }

  @Test
  public void starlarkPatchTransitionRequiredFragments() throws Exception {
    // All Starlark rule transitions are patch transitions, while all Starlark attribute transitions
    // are split transitions.
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:copt': []}", // --copt is a C++ option.
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:copt'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "platform(name = 'my_platform')",
        "my_rule(name = 'test')");

    ConfiguredTargetAndData ct = getConfiguredTargetAndData("//test");
    assertNoEvents();
    Rule testTarget = (Rule) ct.getTarget();
    ConfigurationTransition ruleTransition =
        testTarget
            .getRuleClassObject()
            .getTransitionFactory()
            .create(RuleTransitionData.create(testTarget));
    RequiredConfigFragmentsProvider.Builder requiredFragments =
        RequiredConfigFragmentsProvider.builder();
    ruleTransition.addRequiredFragments(
        requiredFragments, ct.getConfiguration().getBuildOptionDetails());
    assertThat(requiredFragments.build().getOptionsClasses()).containsExactly(CppOptions.class);
  }

  /**
   * Unit test for an invalid output directory from a mnemonic via a dep transition. Integration
   * test for top-level transition in //src/test/shell/integration:starlark_configurations_test#
   * test_invalid_mnemonic_from_transition_top_level. Has to be an integration test because the
   * error is emitted in BuildTool.
   */
  @Test
  public void invalidMnemonicFromDepTransition() throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  return {'//command_line_option:cpu': '//bad:cpu'}",
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:cpu'])");
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "my_rule(name = 'bottom')",
        "genrule(name = 'test', srcs = [':bottom'], outs = ['out'], cmd = 'touch $@')");
    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//test:test")).isNull();
    assertContainsEvent("CPU name '//bad:cpu' is invalid as part of a path: must not contain /");
  }

  @Test
  public void testTransitionOnAllowMultiplesBuildSettingRequiresList() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': 'puffins mate for life'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeAllowlistFile();
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file(
        "test/build_settings.bzl",
        "def _impl(ctx):",
        "  return []",
        "string_flag = rule(implementation = _impl, build_setting = config.string(flag=True,"
            + " allow_multiple=True))");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = \"cats can't taste sugar\",",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "'//test:cute-animal-fact' allows multiple values and must be set in transition using a"
            + " starlark list instead of single value");
  }

  @Test
  public void testTransitionOnAllowMultiplesBuildSetting() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': ['puffins mate for life']}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeAllowlistFile();
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file(
        "test/build_settings.bzl",
        "def _impl(ctx):",
        "  return []",
        "string_flag = rule(implementation = _impl, build_setting = config.string(flag=True,"
            + " allow_multiple=True))");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = \"cats can't taste sugar\",",
        ")");

    Map<Label, Object> starlarkOptions =
        getConfiguration(getConfiguredTarget("//test")).getOptions().getStarlarkOptions();
    assertNoEvents();
    assertThat(
            (List<?>) starlarkOptions.get(Label.parseAbsoluteUnchecked("//test:cute-animal-fact")))
        .containsExactly("puffins mate for life");
  }

  /**
   * Changing --cpu implicitly changes the target platform. Test that the old value of --platforms
   * gets cleared out (platform mappings can then kick in to set --platforms correctly).
   */
  @Test
  public void testImplicitPlatformsChange() throws Exception {
    scratch.file("platforms/BUILD", "platform(name = 'my_platform', constraint_values = [])");
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//command_line_option:cpu': 'ppc'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//command_line_option:cpu']",
        ")");
    writeAllowlistFile();
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    useConfiguration("--platforms=//platforms:my_platform");
    // When --platforms is empty and no platform mapping triggers, PlatformMappingValue sets
    // --platforms to PlatformOptions.computeTargetPlatform(), which defaults to the host.
    assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions.class)
                .platforms)
        .containsExactly(
            Label.parseAbsoluteUnchecked(TestConstants.PLATFORM_PACKAGE_ROOT + ":default_host"));
  }

  @Test
  public void testExplicitPlatformsChange() throws Exception {
    scratch.file(
        "platforms/BUILD",
        "platform(name = 'my_platform', constraint_values = [])",
        "platform(name = 'my_other_platform', constraint_values = [])");
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {",
        "    '//command_line_option:cpu': 'ppc',",
        "    '//command_line_option:platforms': ['//platforms:my_other_platform']",
        "  }",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = [",
        "    '//command_line_option:cpu',",
        "    '//command_line_option:platforms'",
        "  ]",
        ")");
    writeAllowlistFile();
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    useConfiguration("--platforms=//platforms:my_platform");
    assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions.class)
                .platforms)
        .containsExactly(Label.parseAbsoluteUnchecked("//platforms:my_other_platform"));
  }

  /* If the transition doesn't change --cpu, it doesn't constitute a platform change. */
  @Test
  public void testNoPlatformChange() throws Exception {
    scratch.file("platforms/BUILD", "platform(name = 'my_platform', constraint_values = [])");
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {",
        "    '//command_line_option:foo': 'blah',",
        "  }",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = [",
        "    '//command_line_option:foo',",
        "  ]",
        ")");
    writeAllowlistFile();
    scratch.file(
        "test/rules.bzl",
        "load('//test:transitions.bzl', 'my_transition')",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    scratch.file("test/BUILD", "load('//test:rules.bzl', 'my_rule')", "my_rule(name = 'test')");

    useConfiguration("--platforms=//platforms:my_platform");
    assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions.class)
                .platforms)
        .containsExactly(Label.parseAbsoluteUnchecked("//platforms:my_platform"));
  }

  @Test
  public void testTransitionsStillTriggerWhenOnlyRuleAttributesChange() throws Exception {
    scratch.file(
        "test/defs.bzl",
        "def _transition_impl(settings, attr):",
        "  return {",
        "    '//command_line_option:foo': attr.my_attr,",
        "  }",
        "_my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = [",
        "    '//command_line_option:foo',",
        "  ]",
        ")",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = _my_transition,",
        "  attrs = {",
        "    'my_attr': attr.string(),",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  },",
        ")");
    writeAllowlistFile();

    scratch.file(
        "test/BUILD",
        "load('//test:defs.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'buildme',",
        "  my_attr = 'first build',",
        ")");
    assertThat(
            getConfiguration(getConfiguredTarget("//test:buildme"))
                .getOptions()
                .get(DummyTestOptions.class)
                .foo)
        .isEqualTo("first build");

    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:defs.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'buildme',",
        "  my_attr = 'second build',",
        ")");
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter,
        ModifiedFileSet.builder().modify(PathFragment.create("test/BUILD")).build(),
        Root.fromPath(rootDirectory));

    assertThat(
            getConfiguration(getConfiguredTarget("//test:buildme"))
                .getOptions()
                .get(DummyTestOptions.class)
                .foo)
        .isEqualTo("second build");
  }
}
