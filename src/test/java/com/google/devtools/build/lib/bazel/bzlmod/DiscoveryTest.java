// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static org.junit.Assert.fail;

import com.google.auto.value.AutoValue;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.ModuleBuilder;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryFunction;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Discovery}. */
@RunWith(JUnit4.class)
public class DiscoveryTest extends FoundationTestCase {

  private Path workspaceRoot;
  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private EvaluationContext evaluationContext;
  private FakeRegistry.Factory registryFactory;

  @AutoValue
  abstract static class DiscoveryValue implements SkyValue {
    static final SkyFunctionName FUNCTION_NAME = SkyFunctionName.createHermetic("test_discovery");
    static final SkyKey KEY = () -> FUNCTION_NAME;

    static DiscoveryValue create(ImmutableMap<ModuleKey, Module> depGraph) {
      return new AutoValue_DiscoveryTest_DiscoveryValue(depGraph);
    }

    abstract ImmutableMap<ModuleKey, Module> getDepGraph();
  }

  static class DiscoveryFunction implements SkyFunction {
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      RootModuleFileValue root =
          (RootModuleFileValue) env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE);
      if (root == null) {
        return null;
      }
      ImmutableMap<ModuleKey, Module> depGraph = Discovery.run(env, root);
      return depGraph == null ? null : DiscoveryValue.create(depGraph);
    }
  }

  @Before
  public void setup() throws Exception {
    setUpWithBuiltinModules(ImmutableMap.of());
  }

  private void setUpWithBuiltinModules(ImmutableMap<String, NonRegistryOverride> builtinModules)
      throws Exception {
    workspaceRoot = scratch.dir("/ws");
    differencer = new SequencedRecordingDifferencer();
    evaluationContext =
        EvaluationContext.newBuilder().setNumThreads(8).setEventHandler(reporter).build();
    registryFactory = new FakeRegistry.Factory();
    AtomicReference<PathPackageLocator> packageLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            AnalysisMock.get().getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            packageLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder
        .clearWorkspaceFileSuffixForTesting()
        .addStarlarkBootstrap(new RepositoryBootstrap(new StarlarkRepositoryModule()));
    ConfiguredRuleClassProvider ruleClassProvider = builder.build();

    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, new LocalRepositoryFunction());
    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(FileValue.FILE, new FileFunction(packageLocator, directories))
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(DiscoveryValue.FUNCTION_NAME, new DiscoveryFunction())
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(registryFactory, workspaceRoot, builtinModules))
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .put(
                    SkyFunctions.REPOSITORY_DIRECTORY,
                    new RepositoryDelegatorFunction(
                        repositoryHandlers,
                        null,
                        new AtomicBoolean(true),
                        ImmutableMap::of,
                        directories,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new BzlmodRepoRuleFunction(
                        ruleClassProvider,
                        directories,
                        new BzlmodRepoRuleHelperImpl()))
                .buildOrThrow(),
            differencer);

    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    RepositoryDelegatorFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.DEPENDENCY_FOR_UNCONDITIONAL_FETCHING.set(
        differencer, RepositoryDelegatorFunction.DONT_FETCH_UNCONDITIONALLY);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, packageLocator.get());
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    PrecomputedValue.REPO_ENV.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.OUTPUT_VERIFICATION_REPOSITORY_RULES.set(
        differencer, ImmutableSet.of());
    RepositoryDelegatorFunction.RESOLVED_FILE_FOR_VERIFICATION.set(differencer, Optional.empty());
    RepositoryDelegatorFunction.ENABLE_BZLMOD.set(differencer, true);
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
  }

  @Test
  public void testSimpleDiamond() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='1.0')",
        "bazel_dep(name='C',version='2.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B', version='1.0');bazel_dep(name='D',version='3.0')")
            .addModule(
                createModuleKey("C", "2.0"),
                "module(name='C', version='2.0');bazel_dep(name='D',version='3.0')")
            .addModule(createModuleKey("D", "3.0"), "module(name='D', version='3.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("A", "0.1")
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "1.0"))
                .addDep("C", createModuleKey("C", "2.0"))
                .buildEntry(),
            ModuleBuilder.create("B", "1.0")
                .addDep("D", createModuleKey("D", "3.0"))
                .setRegistry(registry)
                .buildEntry(),
            ModuleBuilder.create("C", "2.0")
                .addDep("D", createModuleKey("D", "3.0"))
                .setRegistry(registry)
                .buildEntry(),
            ModuleBuilder.create("D", "3.0").setRegistry(registry).buildEntry());
  }

  @Test
  public void testDevDependency() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='1.0')",
        "bazel_dep(name='C',version='1.0',dev_dependency=True)");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B', version='1.0')",
                "bazel_dep(name='C',version='2.0',dev_dependency=True)")
            .addModule(createModuleKey("C", "1.0"), "module(name='C', version='1.0')")
            .addModule(createModuleKey("C", "2.0"), "module(name='C', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("A", "0.1")
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "1.0"))
                .addDep("C", createModuleKey("C", "1.0"))
                .buildEntry(),
            ModuleBuilder.create("B", "1.0").setRegistry(registry).buildEntry(),
            ModuleBuilder.create("C", "1.0").setRegistry(registry).buildEntry());
  }

  @Test
  public void testIgnoreDevDependency() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='1.0')",
        "bazel_dep(name='C',version='1.0',dev_dependency=True)");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B', version='1.0')",
                "bazel_dep(name='C',version='2.0',dev_dependency=True)")
            .addModule(createModuleKey("C", "1.0"), "module(name='C', version='1.0')")
            .addModule(createModuleKey("C", "2.0"), "module(name='C', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, true);

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("A", "0.1")
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "1.0"))
                .buildEntry(),
            ModuleBuilder.create("B", "1.0").setRegistry(registry).buildEntry());
  }

  @Test
  public void testCircularDependency() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='1.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B', version='1.0');bazel_dep(name='C',version='2.0')")
            .addModule(
                createModuleKey("C", "2.0"),
                "module(name='C', version='2.0');bazel_dep(name='B',version='1.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("A", "0.1")
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "1.0"))
                .buildEntry(),
            ModuleBuilder.create("B", "1.0")
                .addDep("C", createModuleKey("C", "2.0"))
                .setRegistry(registry)
                .buildEntry(),
            ModuleBuilder.create("C", "2.0")
                .addDep("B", createModuleKey("B", "1.0"))
                .setRegistry(registry)
                .buildEntry());
  }

  @Test
  public void testCircularDependencyOnRootModule() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='1.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B', version='1.0');bazel_dep(name='A',version='2.0')")
            .addModule(createModuleKey("A", "2.0"), "module(name='A', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("A", "0.1")
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "1.0"))
                .buildEntry(),
            ModuleBuilder.create("B", "1.0")
                .addDep("A", ModuleKey.ROOT)
                .addOriginalDep("A", createModuleKey("A", "2.0"))
                .setRegistry(registry)
                .buildEntry());
  }

  @Test
  public void testSingleVersionOverride() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='0.1')",
        "single_version_override(module_name='C',version='2.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "0.1"),
                "module(name='B', version='0.1');bazel_dep(name='C',version='1.0')")
            .addModule(createModuleKey("C", "1.0"), "module(name='C', version='1.0');")
            .addModule(createModuleKey("C", "2.0"), "module(name='C', version='2.0');");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("A", "0.1")
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "0.1"))
                .buildEntry(),
            ModuleBuilder.create("B", "0.1")
                .addDep("C", createModuleKey("C", "2.0"))
                .addOriginalDep("C", createModuleKey("C", "1.0"))
                .setRegistry(registry)
                .buildEntry(),
            ModuleBuilder.create("C", "2.0").setRegistry(registry).buildEntry());
  }

  @Test
  public void testRegistryOverride() throws Exception {
    FakeRegistry registry1 =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "0.1"),
                "module(name='B', version='0.1');bazel_dep(name='C',version='1.0')")
            .addModule(createModuleKey("C", "1.0"), "module(name='C', version='1.0');");
    FakeRegistry registry2 =
        registryFactory
            .newFakeRegistry("/bar")
            .addModule(
                createModuleKey("C", "1.0"),
                "module(name='C', version='1.0');bazel_dep(name='B',version='0.1')");
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='0.1')",
        "single_version_override(module_name='C',registry='" + registry2.getUrl() + "')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry1.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("A", "0.1")
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "0.1"))
                .buildEntry(),
            ModuleBuilder.create("B", "0.1")
                .addDep("C", createModuleKey("C", "1.0"))
                .setRegistry(registry1)
                .buildEntry(),
            ModuleBuilder.create("C", "1.0")
                .addDep("B", createModuleKey("B", "0.1"))
                .setRegistry(registry2)
                .buildEntry());
  }

  @Test
  public void testLocalPathOverride() throws Exception {
    Path pathToC = scratch.dir("/pathToC");
    scratch.file(
        pathToC.getRelative("MODULE.bazel").getPathString(), "module(name='C',version='2.0')");
    scratch.file(pathToC.getRelative("WORKSPACE").getPathString());
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "bazel_dep(name='B',version='0.1')",
        "local_path_override(module_name='C',path='" + pathToC.getPathString() + "')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "0.1"),
                "module(name='B', version='0.1');bazel_dep(name='C',version='1.0')")
            .addModule(createModuleKey("C", "1.0"), "module(name='C', version='1.0');");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("A", "0.1")
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "0.1"))
                .buildEntry(),
            ModuleBuilder.create("B", "0.1")
                .addDep("C", createModuleKey("C", ""))
                .addOriginalDep("C", createModuleKey("C", "1.0"))
                .setRegistry(registry)
                .buildEntry(),
            ModuleBuilder.create("C", "2.0").setKey(createModuleKey("C", "")).buildEntry());
  }

  @Test
  public void testBuiltinModules_forRoot() throws Exception {
    ImmutableMap<String, NonRegistryOverride> builtinModules =
        ImmutableMap.of(
            "bazel_tools",
            LocalPathOverride.create(rootDirectory.getRelative("tools").getPathString()),
            "local_config_platform",
            LocalPathOverride.create(rootDirectory.getRelative("localplat").getPathString()));
    setUpWithBuiltinModules(builtinModules);
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='2.0')");
    scratch.file(rootDirectory.getRelative("tools/WORKSPACE").getPathString());
    scratch.file(
        rootDirectory.getRelative("tools/MODULE.bazel").getPathString(),
        "module(name='bazel_tools',version='1.0')",
        "bazel_dep(name='foo',version='1.0')");
    scratch.file(rootDirectory.getRelative("localplat/WORKSPACE").getPathString());
    scratch.file(
        rootDirectory.getRelative("localplat/MODULE.bazel").getPathString(),
        "module(name='local_config_platform')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(createModuleKey("foo", "1.0"), "module(name='foo', version='1.0')")
            .addModule(createModuleKey("foo", "2.0"), "module(name='foo', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<DiscoveryValue> result =
        evaluator.evaluate(ImmutableList.of(DiscoveryValue.KEY), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    DiscoveryValue discoveryValue = result.get(DiscoveryValue.KEY);
    assertThat(discoveryValue.getDepGraph().entrySet())
        .containsExactly(
            ModuleBuilder.create("", "")
                .addDep("bazel_tools", createModuleKey("bazel_tools", ""))
                .addDep("local_config_platform", createModuleKey("local_config_platform", ""))
                .addDep("foo", createModuleKey("foo", "2.0"))
                .buildEntry(),
            ModuleBuilder.create("bazel_tools", "1.0")
                .setKey(createModuleKey("bazel_tools", ""))
                .addDep("local_config_platform", createModuleKey("local_config_platform", ""))
                .addDep("foo", createModuleKey("foo", "1.0"))
                .buildEntry(),
            ModuleBuilder.create("local_config_platform", "")
                .setKey(createModuleKey("local_config_platform", ""))
                .addDep("bazel_tools", createModuleKey("bazel_tools", ""))
                .buildEntry(),
            ModuleBuilder.create("foo", "1.0")
                .addDep("bazel_tools", createModuleKey("bazel_tools", ""))
                .addDep("local_config_platform", createModuleKey("local_config_platform", ""))
                .setRegistry(registry)
                .buildEntry(),
            ModuleBuilder.create("foo", "2.0")
                .addDep("bazel_tools", createModuleKey("bazel_tools", ""))
                .addDep("local_config_platform", createModuleKey("local_config_platform", ""))
                .setRegistry(registry)
                .buildEntry());
  }
}
