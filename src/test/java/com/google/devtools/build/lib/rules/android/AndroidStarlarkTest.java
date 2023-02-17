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

package com.google.devtools.build.lib.rules.android;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.util.BazelMockAndroidSupport;
import com.google.devtools.build.lib.rules.android.AndroidStarlarkTest.WithPlatforms;
import com.google.devtools.build.lib.rules.android.AndroidStarlarkTest.WithoutPlatforms;
import com.google.devtools.build.lib.testutil.TestConstants;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.Starlark;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

/** Tests Android Starlark APIs. */
@RunWith(Suite.class)
@SuiteClasses({WithoutPlatforms.class, WithPlatforms.class})
public abstract class AndroidStarlarkTest extends AndroidBuildViewTestCase {
  /** Use legacy toolchain resolution. */
  @RunWith(JUnit4.class)
  public static class WithoutPlatforms extends AndroidStarlarkTest {
    @Test
    public void testAndroidSplitTransition_fat_apk_cpu() throws Exception {
      getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a");
      writeAndroidSplitTransitionTestFiles("k8");

      useConfiguration("--fat_apk_cpu=k8,armeabi-v7a");
      ConfiguredTarget target = getConfiguredTarget("//test/starlark:test");
      StructImpl myInfo = getMyInfoFromTarget(target);

      // Check that ctx.split_attr.deps has this structure:
      // {
      //   "k8": [ConfiguredTarget],
      //   "armeabi-v7a": [ConfiguredTarget],
      // }
      @SuppressWarnings("unchecked")
      Map<String, List<ConfiguredTarget>> splitDeps =
          (Map<String, List<ConfiguredTarget>>) myInfo.getValue("split_attr_deps");
      assertThat(splitDeps).containsKey("k8");
      assertThat(splitDeps).containsKey("armeabi-v7a");
      assertThat(splitDeps.get("k8")).hasSize(2);
      assertThat(splitDeps.get("armeabi-v7a")).hasSize(2);
      assertThat(getConfiguration(splitDeps.get("k8").get(0)).getCpu()).isEqualTo("k8");
      assertThat(getConfiguration(splitDeps.get("k8").get(1)).getCpu()).isEqualTo("k8");
      assertThat(getConfiguration(splitDeps.get("armeabi-v7a").get(0)).getCpu())
          .isEqualTo("armeabi-v7a");
      assertThat(getConfiguration(splitDeps.get("armeabi-v7a").get(1)).getCpu())
          .isEqualTo("armeabi-v7a");

      // Check that ctx.split_attr.dep has this structure (that is, that the values are not lists):
      // {
      //   "k8": ConfiguredTarget,
      //   "armeabi-v7a": ConfiguredTarget,
      // }
      @SuppressWarnings("unchecked")
      Map<String, ConfiguredTarget> splitDep =
          (Map<String, ConfiguredTarget>) myInfo.getValue("split_attr_dep");
      assertThat(splitDep).containsKey("k8");
      assertThat(splitDep).containsKey("armeabi-v7a");
      assertThat(getConfiguration(splitDep.get("k8")).getCpu()).isEqualTo("k8");
      assertThat(getConfiguration(splitDep.get("armeabi-v7a")).getCpu()).isEqualTo("armeabi-v7a");

      // The regular ctx.attr.deps should be a single list with all the branches of the split merged
      // together (i.e. for aspects).
      @SuppressWarnings("unchecked")
      List<ConfiguredTarget> attrDeps = (List<ConfiguredTarget>) myInfo.getValue("attr_deps");
      assertThat(attrDeps).hasSize(4);
      ListMultimap<String, Object> attrDepsMap = ArrayListMultimap.create();
      for (ConfiguredTarget ct : attrDeps) {
        attrDepsMap.put(getConfiguration(ct).getCpu(), target);
      }
      assertThat(attrDepsMap).valuesForKey("k8").hasSize(2);
      assertThat(attrDepsMap).valuesForKey("armeabi-v7a").hasSize(2);

      // Check that even though my_rule.dep is defined as a single label, ctx.attr.dep is still a
      // list
      // with multiple ConfiguredTarget objects because of the two different CPUs.
      @SuppressWarnings("unchecked")
      List<ConfiguredTarget> attrDep = (List<ConfiguredTarget>) myInfo.getValue("attr_dep");
      assertThat(attrDep).hasSize(2);
      ListMultimap<String, Object> attrDepMap = ArrayListMultimap.create();
      for (ConfiguredTarget ct : attrDep) {
        attrDepMap.put(getConfiguration(ct).getCpu(), target);
      }
      assertThat(attrDepMap).valuesForKey("k8").hasSize(1);
      assertThat(attrDepMap).valuesForKey("armeabi-v7a").hasSize(1);

      // Check that the deps were correctly accessed from within Starlark.
      @SuppressWarnings("unchecked")
      List<ConfiguredTarget> defaultSplitDeps =
          (List<ConfiguredTarget>) myInfo.getValue("default_split_deps");
      assertThat(defaultSplitDeps).hasSize(2);
      assertThat(getConfiguration(defaultSplitDeps.get(0)).getCpu()).isEqualTo("k8");
      assertThat(getConfiguration(defaultSplitDeps.get(1)).getCpu()).isEqualTo("k8");
    }

    @Test
    public void testAndroidSplitTransition_android_cpu() throws Exception {
      writeAndroidSplitTransitionTestFiles("k8");
      BazelMockAndroidSupport.setupNdk(mockToolsConfig);

      // --android_cpu with --android_crosstool_top also triggers the split transition.
      useConfiguration(
          "--android_cpu=armeabi-v7a", "--android_crosstool_top=//android/crosstool:everything");
      ConfiguredTarget target = getConfiguredTarget("//test/starlark:test");

      @SuppressWarnings("unchecked")
      Map<Object, List<ConfiguredTarget>> splitDeps =
          (Map<Object, List<ConfiguredTarget>>)
              getMyInfoFromTarget(target).getValue("split_attr_deps");

      String cpu = "armeabi-v7a";
      assertThat(splitDeps.get(cpu)).hasSize(2);
      assertThat(getConfiguration(splitDeps.get(cpu).get(0)).getCpu()).isEqualTo(cpu);
      assertThat(getConfiguration(splitDeps.get(cpu).get(1)).getCpu()).isEqualTo(cpu);
    }

    @Test
    public void testAndroidSplitTransition_legacy_no_flags() throws Exception {
      writeAndroidSplitTransitionTestFiles("k8");

      useConfiguration("--fat_apk_cpu=", "--android_crosstool_top=", "--cpu=k8");
      ConfiguredTarget target = getConfiguredTarget("//test/starlark:test");

      @SuppressWarnings("unchecked")
      Map<Object, List<ConfiguredTarget>> splitDeps =
          (Map<Object, List<ConfiguredTarget>>)
              getMyInfoFromTarget(target).getValue("split_attr_deps");

      // Split transition isn't in effect, so the deps are compiled normally (i.e. using --cpu).
      assertThat(splitDeps.get(Starlark.NONE)).hasSize(2);
      assertThat(getConfiguration(splitDeps.get(Starlark.NONE).get(0)).getCpu()).isEqualTo("k8");
      assertThat(getConfiguration(splitDeps.get(Starlark.NONE).get(1)).getCpu()).isEqualTo("k8");
    }
  }

  /** Use platform-based toolchain resolution. */
  @RunWith(JUnit4.class)
  public static class WithPlatforms extends AndroidStarlarkTest {
    @Override
    protected boolean platformBasedToolchains() {
      return true;
    }

    @Before
    public void setUp() throws Exception {
      scratch.file(
          "java/android/platforms/BUILD",
          "platform(",
          "    name = 'x86',",
          "    parents = ['" + TestConstants.PLATFORM_PACKAGE_ROOT + "/android:armeabi-v7a'],",
          "    constraint_values = ['" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_32'],",
          ")",
          "platform(",
          "    name = 'armeabi-v7a',",
          "    parents = ['" + TestConstants.PLATFORM_PACKAGE_ROOT + "/android:armeabi-v7a'],",
          "    constraint_values = ['" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:armv7'],",
          ")");
      scratch.file(
          "/workspace/platform_mappings",
          "platforms:",
          "  //java/android/platforms:armeabi-v7a",
          "    --cpu=armeabi-v7a",
          "    --android_cpu=armeabi-v7a",
          "    --crosstool_top=//android/crosstool:everything",
          "  //java/android/platforms:x86",
          "    --cpu=x86",
          "    --android_cpu=x86",
          "    --crosstool_top=//android/crosstool:everything",
          "flags:",
          "  --crosstool_top=//android/crosstool:everything",
          "  --cpu=armeabi-v7a",
          "    //java/android/platforms:armeabi-v7a",
          "  --crosstool_top=//android/crosstool:everything",
          "  --cpu=x86",
          "    //java/android/platforms:x86");
      invalidatePackages(false);
    }

    // Duplicated from WithoutPlatforms.testAndroidSplitTransition_fat_apk_cpu.
    @Test
    public void testAndroidSplitTransition_android_platforms() throws Exception {
      getAnalysisMock()
          .ccSupport()
          .setupCcToolchainConfigForCpu(mockToolsConfig, "x86", "armeabi-v7a");
      writeAndroidSplitTransitionTestFiles("x86");

      useConfiguration(
          "--android_platforms=//java/android/platforms:x86,//java/android/platforms:armeabi-v7a");
      ConfiguredTarget target = getConfiguredTarget("//test/starlark:test");
      StructImpl myInfo = getMyInfoFromTarget(target);

      // Check that ctx.split_attr.deps has this structure:
      // {
      //   "x86": [ConfiguredTarget],
      //   "armeabi-v7a": [ConfiguredTarget],
      // }
      @SuppressWarnings("unchecked")
      Map<String, List<ConfiguredTarget>> splitDeps =
          (Map<String, List<ConfiguredTarget>>) myInfo.getValue("split_attr_deps");
      assertThat(splitDeps).containsKey("x86");
      assertThat(splitDeps).containsKey("armeabi-v7a");
      assertThat(splitDeps.get("x86")).hasSize(2);
      assertThat(splitDeps.get("armeabi-v7a")).hasSize(2);
      assertThat(getConfiguration(splitDeps.get("x86").get(0)).getCpu()).isEqualTo("x86");
      assertThat(getConfiguration(splitDeps.get("x86").get(1)).getCpu()).isEqualTo("x86");
      assertThat(getConfiguration(splitDeps.get("armeabi-v7a").get(0)).getCpu())
          .isEqualTo("armeabi-v7a");
      assertThat(getConfiguration(splitDeps.get("armeabi-v7a").get(1)).getCpu())
          .isEqualTo("armeabi-v7a");

      // Check that ctx.split_attr.dep has this structure (that is, that the values are not lists):
      // {
      //   "x86": ConfiguredTarget,
      //   "armeabi-v7a": ConfiguredTarget,
      // }
      @SuppressWarnings("unchecked")
      Map<String, ConfiguredTarget> splitDep =
          (Map<String, ConfiguredTarget>) myInfo.getValue("split_attr_dep");
      assertThat(splitDep).containsKey("x86");
      assertThat(splitDep).containsKey("armeabi-v7a");
      assertThat(getConfiguration(splitDep.get("x86")).getCpu()).isEqualTo("x86");
      assertThat(getConfiguration(splitDep.get("armeabi-v7a")).getCpu()).isEqualTo("armeabi-v7a");

      // The regular ctx.attr.deps should be a single list with all the branches of the split merged
      // together (i.e. for aspects).
      @SuppressWarnings("unchecked")
      List<ConfiguredTarget> attrDeps = (List<ConfiguredTarget>) myInfo.getValue("attr_deps");
      assertThat(attrDeps).hasSize(4);
      ListMultimap<String, Object> attrDepsMap = ArrayListMultimap.create();
      for (ConfiguredTarget ct : attrDeps) {
        attrDepsMap.put(getConfiguration(ct).getCpu(), target);
      }
      assertThat(attrDepsMap).valuesForKey("x86").hasSize(2);
      assertThat(attrDepsMap).valuesForKey("armeabi-v7a").hasSize(2);

      // Check that even though my_rule.dep is defined as a single label, ctx.attr.dep is still a
      // list
      // with multiple ConfiguredTarget objects because of the two different CPUs.
      @SuppressWarnings("unchecked")
      List<ConfiguredTarget> attrDep = (List<ConfiguredTarget>) myInfo.getValue("attr_dep");
      assertThat(attrDep).hasSize(2);
      ListMultimap<String, Object> attrDepMap = ArrayListMultimap.create();
      for (ConfiguredTarget ct : attrDep) {
        attrDepMap.put(getConfiguration(ct).getCpu(), target);
      }
      assertThat(attrDepMap).valuesForKey("x86").hasSize(1);
      assertThat(attrDepMap).valuesForKey("armeabi-v7a").hasSize(1);

      // Check that the deps were correctly accessed from within Starlark.
      @SuppressWarnings("unchecked")
      List<ConfiguredTarget> defaultSplitDeps =
          (List<ConfiguredTarget>) myInfo.getValue("default_split_deps");
      assertThat(defaultSplitDeps).hasSize(2);
      assertThat(getConfiguration(defaultSplitDeps.get(0)).getCpu()).isEqualTo("x86");
      assertThat(getConfiguration(defaultSplitDeps.get(1)).getCpu()).isEqualTo("x86");
    }
  }

  void writeAndroidSplitTransitionTestFiles(String defaultCpuName) throws Exception {
    scratch.file(
        "test/starlark/my_rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def impl(ctx): ",
        "  return MyInfo(",
        "    split_attr_deps = ctx.split_attr.deps,",
        "    split_attr_dep = ctx.split_attr.dep,",
        "    default_split_deps = ctx.split_attr.deps.get('" + defaultCpuName + "', None),",
        "    attr_deps = ctx.attr.deps,",
        "    attr_dep = ctx.attr.dep)",
        "my_rule = rule(",
        "  implementation = impl,",
        "  attrs = {",
        "    'deps': attr.label_list(cfg = android_common.multi_cpu_configuration),",
        "    'dep':  attr.label(cfg = android_common.multi_cpu_configuration),",
        "  })");

    scratch.file(
        "test/starlark/BUILD",
        "load('//test/starlark:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'test', deps = [':main1', ':main2'], dep = ':main1')",
        "cc_binary(name = 'main1', srcs = ['main1.c'])",
        "cc_binary(name = 'main2', srcs = ['main2.c'])");
  }

  @Before
  public void setup() throws Exception {
    BazelMockAndroidSupport.setupNdk(mockToolsConfig);
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");

    scratch.file("myinfo/BUILD");
    setBuildLanguageOptions("--experimental_google_legacy_api");
  }

  StructImpl getMyInfoFromTarget(ConfiguredTarget configuredTarget) throws Exception {
    Provider.Key key =
        new StarlarkProvider.Key(Label.parseCanonical("//myinfo:myinfo.bzl"), "MyInfo");
    return (StructImpl) configuredTarget.get(key);
  }

  @Test
  public void testAndroidSdkConfigurationField() throws Exception {
    scratch.file(
        "foo_library.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  return MyInfo(foo = ctx.attr._android_sdk.label)",
        "foo_library = rule(implementation = _impl,",
        "    attrs = { '_android_sdk': attr.label(default = configuration_field(",
        "        fragment = 'android', name = 'android_sdk_label'))},",
        "    fragments = ['android'])");
    scratch.file(
        "BUILD",
        "load('//:foo_library.bzl', 'foo_library')",
        "filegroup(name = 'new_sdk')",
        "foo_library(name = 'lib')");
    if (platformBasedToolchains()) {
      // TODO(b/161709111): fails to find a matching Android toolchain.
      if (true) {
        return;
      }
      scratch.file(
          "platform_toolchain_defs/BUILD",
          "toolchain(",
          "    name = 'new_sdk_toolchain',",
          String.format("    toolchain_type = '%s',", TestConstants.ANDROID_TOOLCHAIN_TYPE_LABEL),
          "toolchain = '//:new_sdk',",
          ")");
      useConfiguration(
          "--extra_toolchains=//platform_toolchain_defs:new_sdk_toolchain",
          "--android_sdk=//:new_sdk");
    } else {
      useConfiguration("--android_sdk=//:new_sdk");
    }

    ConfiguredTarget ct = getConfiguredTarget("//:lib");
    assertThat(getMyInfoFromTarget(ct).getValue("foo"))
        .isEqualTo(Label.parseAbsoluteUnchecked("//:new_sdk"));
  }
}
