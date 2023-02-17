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

package com.google.devtools.build.lib.rules;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Injected;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link LabelBuildSettings} rules. */
@RunWith(JUnit4.class)
public class LabelBuildSettingTest extends BuildViewTestCase {
  private FakeRegistry registry;

  @Override
  protected boolean enableBzlmod() {
    return true;
  }

  @Override
  protected ImmutableList<Injected> extraPrecomputedValues() {
    try {
      registry =
          FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(scratch.dir("modules").getPathString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return ImmutableList.of(
        PrecomputedValue.injected(
            ModuleFileFunction.REGISTRIES, ImmutableList.of(registry.getUrl())),
        PrecomputedValue.injected(ModuleFileFunction.IGNORE_DEV_DEPS, false),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES, CheckDirectDepsMode.WARNING));
  }

  @Before
  public void setUpForBzlmod() throws Exception {
    scratch.file("MODULE.bazel");
  }

  private void writeRulesBzl(String type) throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def _my_rule_impl(ctx):",
        "    return struct(value = ctx.attr._label_setting[SimpleRuleInfo].value)",
        "",
        "my_rule = rule(",
        "    implementation = _my_rule_impl,",
        "    attrs = {",
        "        '_label_setting': attr.label(default = Label('//test:my_label_" + type + "')),",
        "    },",
        ")",
        "",
        "SimpleRuleInfo = provider(fields = ['value'])",
        "",
        "def _simple_rule_impl(ctx):",
        "    return [SimpleRuleInfo(value = ctx.attr.value)]",
        "",
        "simple_rule = rule(",
        "    implementation = _simple_rule_impl,",
        "    attrs = {",
        "        'value':attr.string(),",
        "    },",
        ")");
  }

  @Test
  public void testLabelSetting() throws Exception {
    String testing = "setting";
    writeRulesBzl(testing);
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule', 'simple_rule')",
        "",
        "my_rule(name = 'my_rule')",
        "simple_rule(name = 'default', value = 'default_value')",
        "simple_rule(name = 'command_line', value = 'command_line_value')",
        "label_setting(name = 'my_label_" + testing + "', build_setting_default = ':default')");

    scratch.file("a/BUILD", "cc_library(name='a', srcs=['a.cc'])", "alias(name='b', actual='a')");

    ConfiguredTarget b = getConfiguredTarget("//test:my_rule");
    assertThat(b.get("value")).isEqualTo("default_value");
  }

  @Test
  public void testLabelFlag_default() throws Exception {
    String testing = "flag";
    writeRulesBzl(testing);
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule', 'simple_rule')",
        "",
        "my_rule(name = 'my_rule')",
        "simple_rule(name = 'default', value = 'default_value')",
        "simple_rule(name = 'command_line', value = 'command_line_value')",
        "label_flag(name = 'my_label_" + testing + "', build_setting_default = ':default')");

    scratch.file("a/BUILD", "cc_library(name='a', srcs=['a.cc'])", "alias(name='b', actual='a')");

    ConfiguredTarget b = getConfiguredTarget("//test:my_rule");
    assertThat(b.get("value")).isEqualTo("default_value");
  }

  @Test
  public void testLabelFlag_set() throws Exception {
    String testing = "flag";
    writeRulesBzl(testing);
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule', 'simple_rule')",
        "",
        "my_rule(name = 'my_rule')",
        "simple_rule(name = 'default', value = 'default_value')",
        "simple_rule(name = 'command_line', value = 'command_line_value')",
        "label_flag(name = 'my_label_" + testing + "', build_setting_default = ':default')");

    scratch.file("a/BUILD", "cc_library(name='a', srcs=['a.cc'])", "alias(name='b', actual='a')");

    useConfiguration(
        ImmutableMap.of(
            "//test:my_label_flag", Label.parseAbsoluteUnchecked("//test:command_line")));

    ConfiguredTarget b = getConfiguredTarget("//test:my_rule");
    assertThat(b.get("value")).isEqualTo("command_line_value");
  }

  @Test
  public void withSelect() throws Exception {
    writeRulesBzl("flag");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule', 'simple_rule')",
        "simple_rule(name = 'default', value = 'default_value')",
        "simple_rule(name = 'command_line', value = 'command_line_value')",
        "label_flag(name = 'my_label_flag', build_setting_default = ':default')",
        "config_setting(",
        "    name = 'is_default_label',",
        "    flag_values = {':my_label_flag': '//test:default'}",
        ")",
        "simple_rule(name = 'selector', value = select({':is_default_label': 'valid'}))");

    useConfiguration();
    getConfiguredTarget("//test:selector");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    useConfiguration(
        ImmutableMap.of(
            "//test:my_label_flag", Label.parseAbsoluteUnchecked("//test:command_line")));
    getConfiguredTarget("//test:selector");
    assertContainsEvent(
        "configurable attribute \"value\" in //test:selector doesn't match this configuration");
  }

  @Test
  public void selectWithRelativeLabel() throws Exception {
    writeRulesBzl("flag");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule', 'simple_rule')",
        "simple_rule(name = 'default', value = 'default_value')",
        "simple_rule(name = 'command_line', value = 'command_line_value')",
        "label_flag(name = 'my_label_flag', build_setting_default = ':default')",
        "config_setting(",
        "    name = 'is_default_label',",
        "    flag_values = {':my_label_flag': ':default'}",
        ")",
        "simple_rule(name = 'selector', value = select({':is_default_label': 'valid'}))");

    useConfiguration();
    getConfiguredTarget("//test:selector");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    useConfiguration(
        ImmutableMap.of(
            "//test:my_label_flag", Label.parseAbsoluteUnchecked("//test:command_line")));
    getConfiguredTarget("//test:selector");
    assertContainsEvent(
        "configurable attribute \"value\" in //test:selector doesn't match this configuration");
  }

  @Test
  public void selectOnInvalidLabel() throws Exception {
    writeRulesBzl("flag");
    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule', 'simple_rule')",
        "simple_rule(name = 'default', value = 'default_value')",
        "simple_rule(name = 'command_line', value = 'command_line_value')",
        "label_flag(name = 'my_label_flag', build_setting_default = ':default')",
        "config_setting(",
        "    name = 'is_default_label',",
        "    flag_values = {':my_label_flag': ':@not_a_valid_label/'}",
        ")",
        "simple_rule(name = 'selector', value = select({':is_default_label': 'valid'}))");

    reporter.removeHandler(failFastHandler);
    useConfiguration();
    getConfiguredTarget("//test:selector");
    assertContainsEvent(
        "':@not_a_valid_label/' cannot be converted to //test:my_label_flag type label");
  }

  @Test
  public void transitionOutput_samePackage() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        "package_group(",
        "    name = 'function_transition_allowlist',",
        "    packages = [",
        "        '//test/...',",
        "    ],",
        ")");

    scratch.file(
        "test/rules.bzl",
        "def _transition_impl(settings, attr):",
        "    return {",
        "        '//test:my_flag1': Label('//test:other_rule'),",
        "        '//test:my_flag2': '//test:other_rule',",
        "        '//test:my_flag3': ':other_rule',",
        "    }",
        "_my_transition = transition(",
        "    implementation = _transition_impl,",
        "    inputs = [],",
        "    outputs = ['//test:my_flag1', '//test:my_flag2', '//test:my_flag3'],",
        ")",
        "def _rule_impl(ctx):",
        "    target = Label('//test:other_rule')",
        "    if target != ctx.attr._flag1.label: fail('flag1 is ' + str(ctx.attr._flag1.label))",
        "    if target != ctx.attr._flag2.label: fail('flag2 is ' + str(ctx.attr._flag2.label))",
        "    if target != ctx.attr._flag3.label: fail('flag3 is ' + str(ctx.attr._flag3.label))",
        "rule_with_transition = rule(",
        "    implementation = _rule_impl,",
        "    cfg = _my_transition,",
        "    attrs = {",
        "        '_allowlist_function_transition': attr.label(",
        "            default = '//tools/allowlists/function_transition_allowlist',",
        "        ),",
        "        '_flag1': attr.label(default=':my_flag1'),",
        "        '_flag2': attr.label(default=':my_flag2'),",
        "        '_flag3': attr.label(default=':my_flag3'),",
        "    }",
        ")");

    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'rule_with_transition')",
        "label_flag(name = 'my_flag1', build_setting_default = ':first_rule')",
        "label_flag(name = 'my_flag2', build_setting_default = ':first_rule')",
        "label_flag(name = 'my_flag3', build_setting_default = ':first_rule')",
        "filegroup(name = 'first_rule')",
        "filegroup(name = 'other_rule')",
        "rule_with_transition(name = 'buildme')");
    assertThat(getConfiguredTarget("//test:buildme")).isNotNull();
    assertNoEvents();
  }

  @Test
  public void transitionOutput_otherRepo() throws Exception {
    scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='foo',version='1.0')");
    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo', version='1.0')");
    scratch.file("modules/@foo~1.0/WORKSPACE");
    scratch.file("modules/@foo~1.0/BUILD", "filegroup(name='other_rule')");

    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        "package_group(",
        "    name = 'function_transition_allowlist',",
        "    packages = [",
        "        '//test/...',",
        "    ],",
        ")");

    scratch.file(
        "test/rules.bzl",
        "def _transition_impl(settings, attr):",
        "    return {",
        "        '//test:my_flag1': Label('@foo//:other_rule'),",
        "        '//test:my_flag2': '@foo//:other_rule',",
        "    }",
        "_my_transition = transition(",
        "    implementation = _transition_impl,",
        "    inputs = [],",
        "    outputs = ['//test:my_flag1', '//test:my_flag2'],",
        ")",
        "def _rule_impl(ctx):",
        "    target = Label('@foo//:other_rule')",
        "    if target != ctx.attr._flag1.label: fail('flag1 is ' + str(ctx.attr._flag1.label))",
        "    if target != ctx.attr._flag2.label: fail('flag2 is ' + str(ctx.attr._flag2.label))",
        "rule_with_transition = rule(",
        "    implementation = _rule_impl,",
        "    cfg = _my_transition,",
        "    attrs = {",
        "        '_allowlist_function_transition': attr.label(",
        "            default = '//tools/allowlists/function_transition_allowlist',",
        "        ),",
        "        '_flag1': attr.label(default=':my_flag1'),",
        "        '_flag2': attr.label(default=':my_flag2'),",
        "    }",
        ")");

    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'rule_with_transition')",
        "label_flag(name = 'my_flag1', build_setting_default = ':first_rule')",
        "label_flag(name = 'my_flag2', build_setting_default = ':first_rule')",
        "label_flag(name = 'my_flag3', build_setting_default = ':first_rule')",
        "filegroup(name = 'first_rule')",
        "rule_with_transition(name = 'buildme')");
    assertThat(getConfiguredTarget("//test:buildme")).isNotNull();
    assertNoEvents();
  }
}
