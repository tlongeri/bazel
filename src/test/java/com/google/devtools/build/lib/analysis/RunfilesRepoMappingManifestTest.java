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
package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Injected;
import com.google.devtools.build.lib.vfs.Path;
import java.util.Map.Entry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that the repo mapping manifest file is properly generated for runfiles. */
@RunWith(JUnit4.class)
public class RunfilesRepoMappingManifestTest extends BuildViewTestCase {
  private Path moduleRoot;
  private FakeRegistry registry;

  @Override
  protected ImmutableList<Injected> extraPrecomputedValues() throws Exception {
    moduleRoot = scratch.dir("modules");
    registry = FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString());
    return ImmutableList.of(
        PrecomputedValue.injected(
            ModuleFileFunction.REGISTRIES, ImmutableList.of(registry.getUrl())),
        PrecomputedValue.injected(ModuleFileFunction.IGNORE_DEV_DEPS, false),
        PrecomputedValue.injected(ModuleFileFunction.MODULE_OVERRIDES, ImmutableMap.of()),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES, CheckDirectDepsMode.WARNING),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE, BazelCompatibilityMode.ERROR),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.ALLOWED_YANKED_VERSIONS, ImmutableList.of()));
  }

  @Before
  public void enableBzlmod() throws Exception {
    setBuildLanguageOptions("--enable_bzlmod");
  }

  /**
   * Sets up a Bazel module bare_rule@1.0, which provides a bare_binary rule that passes along
   * runfiles in the data attribute, and does nothing else.
   */
  @Before
  public void setupBareBinaryRule() throws Exception {
    registry.addModule(
        createModuleKey("bare_rule", "1.0"), "module(name='bare_rule',version='1.0')");
    scratch.overwriteFile(moduleRoot.getRelative("bare_rule~1.0/WORKSPACE").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("bare_rule~1.0/defs.bzl").getPathString(),
        "def _bare_binary_impl(ctx):",
        "  exe = ctx.actions.declare_file(ctx.label.name)",
        "  ctx.actions.write(exe, 'i got nothing', True)",
        "  runfiles = ctx.runfiles(files=ctx.files.data)",
        "  for data in ctx.attr.data:",
        "    runfiles = runfiles.merge(data[DefaultInfo].default_runfiles)",
        "  return DefaultInfo(files=depset(direct=[exe]), executable=exe, runfiles=runfiles)",
        "bare_binary=rule(",
        "  implementation=_bare_binary_impl,",
        "  attrs={'data':attr.label_list(allow_files=True)},",
        "  executable=True,",
        ")");
    scratch.overwriteFile(
        moduleRoot.getRelative("bare_rule~1.0/BUILD").getPathString(),
        "load('//:defs.bzl', 'bare_binary')",
        "bare_binary(name='bare_binary')");
  }

  private ImmutableList<String> getRepoMappingManifestForTarget(String label) throws Exception {
    Action action = getGeneratingAction(getRunfilesSupport(label).getRepoMappingManifest());
    assertThat(action).isInstanceOf(RepoMappingManifestAction.class);
    return ((RepoMappingManifestAction) action)
        .newDeterministicWriter(null)
        .getBytes()
        .toStringUtf8()
        .lines()
        .collect(toImmutableList());
  }

  @Test
  public void diamond() throws Exception {
    rewriteWorkspace("workspace(name='aaa_ws')");
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='1.0')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    registry.addModule(
        createModuleKey("bbb", "1.0"),
        "module(name='bbb',version='1.0')",
        "bazel_dep(name='ddd',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ccc", "2.0"),
        "module(name='ccc',version='2.0')",
        "bazel_dep(name='ddd',version='2.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ddd", "1.0"),
        "module(name='ddd',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ddd", "2.0"),
        "module(name='ddd',version='2.0')",
        "bazel_dep(name='bare_rule',version='1.0')");

    scratch.overwriteFile(
        "BUILD",
        "load('@bare_rule//:defs.bzl', 'bare_binary')",
        "bare_binary(name='aaa',data=['@bbb'])");
    ImmutableMap<String, String> buildFiles =
        ImmutableMap.of(
            "bbb~1.0", "bare_binary(name='bbb',data=['@ddd'])",
            "ccc~2.0", "bare_binary(name='ccc',data=['@ddd'])",
            "ddd~1.0", "bare_binary(name='ddd')",
            "ddd~2.0", "bare_binary(name='ddd')");
    for (Entry<String, String> entry : buildFiles.entrySet()) {
      scratch.overwriteFile(
          moduleRoot.getRelative(entry.getKey()).getRelative("WORKSPACE").getPathString());
      scratch.overwriteFile(
          moduleRoot.getRelative(entry.getKey()).getRelative("BUILD").getPathString(),
          "load('@bare_rule//:defs.bzl', 'bare_binary')",
          entry.getValue());
    }

    assertThat(getRepoMappingManifestForTarget("//:aaa"))
        .containsExactly(
            ",aaa,_main",
            ",aaa_ws,_main",
            ",bbb,bbb~1.0",
            "bbb~1.0,bbb,bbb~1.0",
            "bbb~1.0,ddd,ddd~2.0",
            "ddd~2.0,ddd,ddd~2.0")
        .inOrder();
    assertThat(getRepoMappingManifestForTarget("@@ccc~2.0//:ccc"))
        .containsExactly("ccc~2.0,ccc,ccc~2.0", "ccc~2.0,ddd,ddd~2.0", "ddd~2.0,ddd,ddd~2.0")
        .inOrder();
  }

  @Test
  public void runfilesFromToolchain() throws Exception {
    rewriteWorkspace("workspace(name='main')");
    scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='tooled_rule',version='1.0')");
    // tooled_rule offers a tooled_binary rule, which uses a toolchain backed by a binary from
    // bare_rule. tooled_binary explicitly requests that runfiles from this binary are included in
    // its runfiles tree, which would mean that bare_rule should be included in the repo mapping
    // manifest.
    registry.addModule(
        createModuleKey("tooled_rule", "1.0"),
        "module(name='tooled_rule',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')",
        "register_toolchains('//:all')");
    scratch.overwriteFile(moduleRoot.getRelative("tooled_rule~1.0/WORKSPACE").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("tooled_rule~1.0/defs.bzl").getPathString(),
        "def _tooled_binary_impl(ctx):",
        "  exe = ctx.actions.declare_file(ctx.label.name)",
        "  ctx.actions.write(exe, 'i got something', True)",
        "  runfiles = ctx.runfiles(files=ctx.files.data)",
        "  for data in ctx.attr.data:",
        "    runfiles = runfiles.merge(data[DefaultInfo].default_runfiles)",
        "  runfiles = runfiles.merge(",
        "      ctx.toolchains['//:toolchain_type'].tooled_info[DefaultInfo].default_runfiles)",
        "  return DefaultInfo(files=depset(direct=[exe]), executable=exe, runfiles=runfiles)",
        "tooled_binary = rule(",
        "  implementation=_tooled_binary_impl,",
        "  attrs={'data':attr.label_list(allow_files=True)},",
        "  executable=True,",
        "  toolchains=['//:toolchain_type'],",
        ")",
        "",
        "def _tooled_toolchain_rule_impl(ctx):",
        "  return [platform_common.ToolchainInfo(tooled_info = ctx.attr.backing_binary)]",
        "tooled_toolchain_rule=rule(_tooled_toolchain_rule_impl,",
        "  attrs={'backing_binary':attr.label()})",
        "def tooled_toolchain(name, backing_binary):",
        "  tooled_toolchain_rule(name=name+'_impl',backing_binary=backing_binary)",
        "  native.toolchain(",
        "    name=name,",
        "    toolchain=':'+name+'_impl',",
        "    toolchain_type=Label('//:toolchain_type'),",
        "  )");
    scratch.overwriteFile(
        moduleRoot.getRelative("tooled_rule~1.0/BUILD").getPathString(),
        "load('//:defs.bzl', 'tooled_toolchain')",
        "toolchain_type(name='toolchain_type')",
        "tooled_toolchain(name='tooled_toolchain', backing_binary='@bare_rule//:bare_binary')");

    scratch.overwriteFile(
        "BUILD",
        "load('@tooled_rule//:defs.bzl', 'tooled_binary')",
        "tooled_binary(name='tooled')");

    assertThat(getRepoMappingManifestForTarget("//:tooled"))
        .containsExactly(
            ",main,_main",
            "bare_rule~1.0,bare_rule,bare_rule~1.0",
            "tooled_rule~1.0,bare_rule,bare_rule~1.0")
        .inOrder();
  }
}
