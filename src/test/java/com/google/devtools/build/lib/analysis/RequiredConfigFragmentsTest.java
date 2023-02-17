// Copyright 2020 The Bazel Authors. All rights reserved.
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
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;

import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.config.CoreOptions.IncludeConfigFragmentsEnum;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.rules.java.JavaOptions;
import com.google.devtools.build.lib.rules.python.PythonConfiguration;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link RequiredConfigFragmentsProvider}. */
@RunWith(TestParameterInjector.class)
public final class RequiredConfigFragmentsTest extends BuildViewTestCase {

  @Test
  public void provideTransitiveRequiredFragmentsMode() throws Exception {
    useConfiguration("--include_config_fragments_provider=transitive");
    scratch.file(
        "a/BUILD",
        "config_setting(name = 'config', values = {'start_end_lib': '1'})",
        "py_library(name = 'pylib', srcs = ['pylib.py'])",
        "cc_library(name = 'a', srcs = ['A.cc'], data = [':pylib'])");

    RequiredConfigFragmentsProvider ccLibTransitiveFragments =
        getConfiguredTarget("//a:a").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(ccLibTransitiveFragments.getFragmentClasses())
        .containsAtLeast(CppConfiguration.class, PythonConfiguration.class);

    RequiredConfigFragmentsProvider configSettingTransitiveFragments =
        getConfiguredTarget("//a:config").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(configSettingTransitiveFragments.getOptionsClasses()).contains(CppOptions.class);
  }

  @Test
  public void provideDirectRequiredFragmentsMode() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct");
    scratch.file(
        "a/BUILD",
        "config_setting(name = 'config', values = {'start_end_lib': '1'})",
        "py_library(name = 'pylib', srcs = ['pylib.py'])",
        "cc_library(name = 'a', srcs = ['A.cc'], data = [':pylib'])");

    RequiredConfigFragmentsProvider ccLibDirectFragments =
        getConfiguredTarget("//a:a").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(ccLibDirectFragments.getFragmentClasses()).contains(CppConfiguration.class);
    assertThat(ccLibDirectFragments.getFragmentClasses()).doesNotContain(PythonConfiguration.class);

    RequiredConfigFragmentsProvider configSettingDirectFragments =
        getConfiguredTarget("//a:config").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(configSettingDirectFragments.getOptionsClasses()).contains(CppOptions.class);
  }

  @Test
  public void requiresMakeVariablesSuppliedByDefine() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct", "--define", "myvar=myval");
    scratch.file(
        "a/BUILD",
        "genrule(",
        "    name = 'myrule',",
        "    srcs = [],",
        "    outs = ['myrule.out'],",
        "    cmd = 'echo $(myvar) $(COMPILATION_MODE) > $@')");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:myrule").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.getDefines()).containsExactly("myvar");
  }

  @Test
  public void starlarkExpandMakeVariables() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct", "--define=myvar=myval");
    scratch.file(
        "a/defs.bzl",
        "def _impl(ctx):",
        "  print(ctx.expand_make_variables('dummy attribute', 'string with $(myvar)!', {}))",
        "",
        "simple_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {}",
        ")");
    scratch.file("a/BUILD", "load('//a:defs.bzl', 'simple_rule')", "simple_rule(name = 'simple')");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:simple").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.getDefines()).containsExactly("myvar");
  }

  @Test
  public void starlarkCtxVar() throws Exception {
    useConfiguration(
        "--include_config_fragments_provider=direct", "--define=required_var=1,irrelevant_var=1");
    scratch.file(
        "a/defs.bzl",
        "def _impl(ctx):",
        // Defined, so reported as required.
        "  if 'required_var' not in ctx.var:",
        "    fail('Missing required_var')",
        // Not defined, so not reported as required.
        "  if 'prohibited_var' in ctx.var:",
        "    fail('Not allowed to set prohibited_var')",
        // Present but not a define variable, so not reported as required.
        "  if 'COMPILATION_MODE' not in ctx.var:",
        "    fail('Missing COMPILATION_MODE')",
        "",
        "simple_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {}",
        ")");
    scratch.file("a/BUILD", "load('//a:defs.bzl', 'simple_rule')", "simple_rule(name = 'simple')");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:simple").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.getDefines()).containsExactly("required_var");
  }

  /**
   * Aspect that requires fragments both in its definition and through {@link
   * #addAspectImplSpecificRequiredConfigFragments}.
   */
  private static final class AspectWithConfigFragmentRequirements extends NativeAspectClass
      implements ConfiguredAspectFactory {
    private static final Class<JavaConfiguration> REQUIRED_FRAGMENT = JavaConfiguration.class;
    private static final String REQUIRED_DEFINE = "myvar";

    @Override
    public AspectDefinition getDefinition(AspectParameters params) {
      return new AspectDefinition.Builder(this)
          .requiresConfigurationFragments(REQUIRED_FRAGMENT)
          .build();
    }

    @Override
    public ConfiguredAspect create(
        ConfiguredTargetAndData ctadBase,
        RuleContext ruleContext,
        AspectParameters params,
        RepositoryName toolsRepository)
        throws ActionConflictException, InterruptedException {
      return new ConfiguredAspect.Builder(ruleContext).build();
    }

    @Override
    public void addAspectImplSpecificRequiredConfigFragments(
        RequiredConfigFragmentsProvider.Builder requiredFragments) {
      requiredFragments.addDefine(REQUIRED_DEFINE);
    }
  }

  private static final AspectWithConfigFragmentRequirements
      ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS = new AspectWithConfigFragmentRequirements();

  /** Rule that attaches {@link AspectWithConfigFragmentRequirements} to its deps. */
  public static final class RuleThatAttachesAspect
      implements RuleDefinition, RuleConfiguredTargetFactory {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(
              attr("deps", LABEL_LIST)
                  .allowedFileTypes(FileTypeSet.NO_FILE)
                  .aspect(ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("rule_that_attaches_aspect")
          .ancestors(BaseRuleClasses.NativeBuildRule.class)
          .factoryClass(RuleThatAttachesAspect.class)
          .build();
    }

    @Override
    public ConfiguredTarget create(RuleContext ruleContext)
        throws ActionConflictException, InterruptedException {
      return new RuleConfiguredTargetBuilder(ruleContext)
          .addProvider(RunfilesProvider.EMPTY)
          .build();
    }
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder =
        new ConfiguredRuleClassProvider.Builder()
            .addRuleDefinition(new RuleThatAttachesAspect())
            .addNativeAspectClass(ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS);
    TestRuleClassProvider.addStandardRules(builder);
    return builder.build();
  }

  @Test
  public void aspectRequiresFragments() throws Exception {
    scratch.file(
        "a/BUILD",
        "rule_that_attaches_aspect(name = 'parent', deps = [':dep'])",
        "rule_that_attaches_aspect(name = 'dep')");
    useConfiguration("--include_config_fragments_provider=transitive");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:parent").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.getFragmentClasses())
        .contains(AspectWithConfigFragmentRequirements.REQUIRED_FRAGMENT);
    assertThat(requiredFragments.getDefines())
        .containsExactly(AspectWithConfigFragmentRequirements.REQUIRED_DEFINE);
  }

  private void writeStarlarkTransitionsAndAllowList() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        "package_group(",
        "    name = 'function_transition_allowlist',",
        "    packages = [",
        "        '//a/...',",
        "    ],",
        ")");
    scratch.file(
        "transitions/defs.bzl",
        "def _java_write_transition_impl(settings, attr):",
        "  return {'//command_line_option:javacopt': ['foo'] }",
        "java_write_transition = transition(",
        "  implementation = _java_write_transition_impl,",
        "  inputs = [],",
        "  outputs = ['//command_line_option:javacopt'],",
        ")",
        "def _cpp_read_transition_impl(settings, attr):",
        "  return {}",
        "cpp_read_transition = transition(",
        "  implementation = _cpp_read_transition_impl,",
        "  inputs = ['//command_line_option:copt'],",
        "  outputs = [],",
        ")");
    scratch.file("transitions/BUILD");
  }

  @Test
  public void starlarkRuleTransitionReadsFragment() throws Exception {
    writeStarlarkTransitionsAndAllowList();
    scratch.file(
        "a/defs.bzl",
        "load('//transitions:defs.bzl', 'cpp_read_transition')",
        "def _impl(ctx):",
        "  pass",
        "has_cpp_aware_rule_transition = rule(",
        "  implementation = _impl,",
        "  cfg = cpp_read_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "a/BUILD",
        "load('//a:defs.bzl', 'has_cpp_aware_rule_transition')",
        "has_cpp_aware_rule_transition(name = 'cctarget')");
    useConfiguration("--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:cctarget").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.getOptionsClasses()).contains(CppOptions.class);
    assertThat(requiredFragments.getOptionsClasses()).doesNotContain(JavaOptions.class);
  }

  @Test
  public void starlarkRuleTransitionWritesFragment() throws Exception {
    writeStarlarkTransitionsAndAllowList();
    scratch.file(
        "a/defs.bzl",
        "load('//transitions:defs.bzl', 'java_write_transition')",
        "def _impl(ctx):",
        "  pass",
        "has_java_aware_rule_transition = rule(",
        "  implementation = _impl,",
        "  cfg = java_write_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "a/BUILD",
        "load('//a:defs.bzl', 'has_java_aware_rule_transition')",
        "has_java_aware_rule_transition(name = 'javatarget')");
    useConfiguration("--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:javatarget").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.getOptionsClasses()).contains(JavaOptions.class);
    assertThat(requiredFragments.getOptionsClasses()).doesNotContain(CppOptions.class);
  }

  @Test
  public void starlarkAttrTransition() throws Exception {
    writeStarlarkTransitionsAndAllowList();
    scratch.file(
        "a/defs.bzl",
        "load('//transitions:defs.bzl', 'cpp_read_transition', 'java_write_transition')",
        "def _impl(ctx):",
        "  pass",
        "has_java_aware_attr_transition = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'deps': attr.label_list(cfg = java_write_transition),",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })",
        "has_cpp_aware_rule_transition = rule(",
        "  implementation = _impl,",
        "  cfg = cpp_read_transition,",
        "  attrs = {",
        "    '_allowlist_function_transition': attr.label(",
        "        default = '//tools/allowlists/function_transition_allowlist',",
        "    ),",
        "  })");
    scratch.file(
        "a/BUILD",
        "load('//a:defs.bzl', 'has_cpp_aware_rule_transition', 'has_java_aware_attr_transition')",
        "has_cpp_aware_rule_transition(name = 'ccchild')",
        "has_java_aware_attr_transition(",
        "  name = 'javaparent',",
        "  deps = [':ccchild'])");
    useConfiguration("--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:javaparent").getProvider(RequiredConfigFragmentsProvider.class);
    // We consider the attribute transition over the parent -> child edge a property of the parent.
    assertThat(requiredFragments.getOptionsClasses()).contains(JavaOptions.class);
    // But not the child's rule transition.
    assertThat(requiredFragments.getOptionsClasses()).doesNotContain(CppOptions.class);
  }

  @Test
  public void aspectInheritsTransitiveFragmentsFromBaseCT(
      @TestParameter({"DIRECT", "TRANSITIVE"}) IncludeConfigFragmentsEnum setting)
      throws Exception {
    writeStarlarkTransitionsAndAllowList();
    scratch.file(
        "a/defs.bzl",
        "",
        "A1Info = provider()",
        "def _a1_impl(target, ctx):",
        "  return []",
        "a1 = aspect(implementation = _a1_impl)",
        "",
        "def _java_depender_impl(ctx):",
        "  return []",
        "java_depender = rule(",
        "  implementation = _java_depender_impl,",
        "  fragments = ['java'],",
        "  attrs = {})",
        "",
        "def _r_impl(ctx):",
        "  return []",
        "r = rule(",
        "  implementation = _r_impl,",
        "  attrs = {'dep': attr.label(aspects = [a1])})");
    scratch.file(
        "a/BUILD",
        "load(':defs.bzl', 'java_depender', 'r')",
        "java_depender(name = 'lib')",
        "r(name = 'r', dep = ':lib')");

    useConfiguration("--include_config_fragments_provider=" + setting);
    getConfiguredTarget("//a:r");
    RequiredConfigFragmentsProvider requiredFragments =
        getAspect("//a:defs.bzl%a1").getProvider(RequiredConfigFragmentsProvider.class);

    if (setting == IncludeConfigFragmentsEnum.TRANSITIVE) {
      assertThat(requiredFragments.getFragmentClasses()).contains(JavaConfiguration.class);
    } else {
      assertThat(requiredFragments.getFragmentClasses()).doesNotContain(JavaConfiguration.class);
    }
  }

  @Test
  public void aspectInheritsTransitiveFragmentsFromRequiredAspect(
      @TestParameter({"DIRECT", "TRANSITIVE"}) IncludeConfigFragmentsEnum setting)
      throws Exception {
    scratch.file(
        "a/defs.bzl",
        "",
        "A1Info = provider()",
        "def _a1_impl(target, ctx):",
        "  return A1Info(var = ctx.var.get('my_var', '0'))",
        "a1 = aspect(implementation = _a1_impl, provides = [A1Info])",
        "",
        "A2Info = provider()",
        "def _a2_impl(target, ctx):",
        "  return A2Info()",
        "a2 = aspect(implementation = _a2_impl, required_aspect_providers = [A1Info])",
        "",
        "def _simple_rule_impl(ctx):",
        "  return []",
        "simple_rule = rule(",
        "  implementation = _simple_rule_impl,",
        "  attrs = {})",
        "",
        "def _r_impl(ctx):",
        "  return []",
        "r = rule(",
        "  implementation = _r_impl,",
        "  attrs = {'dep': attr.label(aspects = [a1, a2])})");
    scratch.file(
        "a/BUILD",
        "load(':defs.bzl', 'r', 'simple_rule')",
        "simple_rule(name = 'lib')",
        "r(name = 'r', dep = ':lib')");

    useConfiguration("--include_config_fragments_provider=" + setting, "--define", "my_var=1");
    getConfiguredTarget("//a:r");
    RequiredConfigFragmentsProvider requiredFragments =
        getAspect("//a:defs.bzl%a2").getProvider(RequiredConfigFragmentsProvider.class);

    if (setting == IncludeConfigFragmentsEnum.TRANSITIVE) {
      assertThat(requiredFragments.getDefines()).contains("my_var");
    } else {
      assertThat(requiredFragments.getDefines()).doesNotContain("my_var");
    }
  }

  @Test
  public void invalidStarlarkFragmentsFiltered() throws Exception {
    scratch.file(
        "a/defs.bzl",
        "def _my_rule_impl(ctx):",
        "  pass",
        "",
        "my_rule = rule(implementation = _my_rule_impl, fragments = ['java', 'doesnotexist'])");
    scratch.file("a/BUILD", "load(':defs.bzl', 'my_rule')", "my_rule(name = 'example')");

    useConfiguration("--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:example").getProvider(RequiredConfigFragmentsProvider.class);

    assertThat(requiredFragments.getFragmentClasses()).contains(JavaConfiguration.class);
  }
}
