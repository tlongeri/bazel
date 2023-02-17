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

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.packages.PackageFactory;
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
import com.google.devtools.build.lib.skyframe.ManagedDirectoriesKnowledge;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.Location;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ModuleFileFunction}. */
@RunWith(JUnit4.class)
public class ModuleFileFunctionTest extends FoundationTestCase {

  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private EvaluationContext evaluationContext;
  private FakeRegistry.Factory registryFactory;

  @Before
  public void setup() throws Exception {
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

    PackageFactory packageFactory =
        AnalysisMock.get()
            .getPackageFactoryBuilderForTesting(directories)
            .build(ruleClassProvider, fileSystem);

    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, new LocalRepositoryFunction());
    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(FileValue.FILE, new FileFunction(packageLocator))
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(registryFactory, rootDirectory))
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .put(
                    SkyFunctions.REPOSITORY_DIRECTORY,
                    new RepositoryDelegatorFunction(
                        repositoryHandlers,
                        null,
                        new AtomicBoolean(true),
                        ImmutableMap::of,
                        directories,
                        ManagedDirectoriesKnowledge.NO_MANAGED_DIRECTORIES,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new BzlmodRepoRuleFunction(
                        packageFactory,
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
  public void testRootModule() throws Exception {
    scratch.file(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(",
        "    name='A',",
        "    version='0.1',",
        "    compatibility_level=4,",
        "    toolchains_to_register=['//my:toolchain', '//my:toolchain2'],",
        "    execution_platforms_to_register=['//my:platform', '//my:platform2'],",
        ")",
        "bazel_dep(name='B',version='1.0')",
        "bazel_dep(name='C',version='2.0',repo_name='see')",
        "single_version_override(module_name='D',version='18')",
        "local_path_override(module_name='E',path='somewhere/else')",
        "multiple_version_override(module_name='F',versions=['1.0','2.0'])",
        "archive_override(module_name='G',urls=['https://hello.com/world.zip'])");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RootModuleFileValue rootModuleFileValue = result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    assertThat(rootModuleFileValue.getModule())
        .isEqualTo(
            Module.builder()
                .setName("A")
                .setVersion(Version.parse("0.1"))
                .setKey(ModuleKey.ROOT)
                .setCompatibilityLevel(4)
                .setExecutionPlatformsToRegister(
                    ImmutableList.of("//my:platform", "//my:platform2"))
                .setToolchainsToRegister(ImmutableList.of("//my:toolchain", "//my:toolchain2"))
                .addDep("B", createModuleKey("B", "1.0"))
                .addDep("see", createModuleKey("C", "2.0"))
                .build());
    assertThat(rootModuleFileValue.getOverrides())
        .containsExactly(
            "D", SingleVersionOverride.create(Version.parse("18"), "", ImmutableList.of(), 0),
            "E", LocalPathOverride.create("somewhere/else"),
            "F",
                MultipleVersionOverride.create(
                    ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""),
            "G",
                ArchiveOverride.create(
                    ImmutableList.of("https://hello.com/world.zip"),
                    ImmutableList.of(),
                    "",
                    "",
                    0));
    assertThat(rootModuleFileValue.getNonRegistryOverrideCanonicalRepoNameLookup())
        .containsExactly("E.override", "E", "G.override", "G");
  }

  @Test
  public void testRootModule_noModuleFunctionIsOkay() throws Exception {
    scratch.file(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='B',version='1.0')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RootModuleFileValue rootModuleFileValue = result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    assertThat(rootModuleFileValue.getModule())
        .isEqualTo(
            Module.builder()
                .setKey(ModuleKey.ROOT)
                .addDep("B", createModuleKey("B", "1.0"))
                .build());
    assertThat(rootModuleFileValue.getOverrides()).isEmpty();
    assertThat(rootModuleFileValue.getNonRegistryOverrideCanonicalRepoNameLookup()).isEmpty();
  }

  @Test
  public void testRootModule_badSelfOverride() throws Exception {
    scratch.file(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='A')",
        "single_version_override(module_name='A',version='7')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString()).contains("invalid override for the root module");
  }

  @Test
  public void testRegistriesCascade() throws Exception {
    // Registry1 has no module B@1.0; registry2 and registry3 both have it. We should be using the
    // B@1.0 from registry2.
    FakeRegistry registry1 = registryFactory.newFakeRegistry("/foo");
    FakeRegistry registry2 =
        registryFactory
            .newFakeRegistry("/bar")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B',version='1.0');bazel_dep(name='C',version='2.0')");
    FakeRegistry registry3 =
        registryFactory
            .newFakeRegistry("/baz")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B',version='1.0');bazel_dep(name='D',version='3.0')");
    ModuleFileFunction.REGISTRIES.set(
        differencer, ImmutableList.of(registry1.getUrl(), registry2.getUrl(), registry3.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("B", "1.0"), null);
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            Module.builder()
                .setName("B")
                .setVersion(Version.parse("1.0"))
                .setKey(createModuleKey("B", "1.0"))
                .addDep("C", createModuleKey("C", "2.0"))
                .setRegistry(registry2)
                .build());
  }

  @Test
  public void testLocalPathOverride() throws Exception {
    // There is an override for B to use the local path "code_for_b", so we shouldn't even be
    // looking at the registry.
    scratch.file(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1')",
        "local_path_override(module_name='B',path='code_for_b')");
    scratch.file(
        rootDirectory.getRelative("code_for_b/MODULE.bazel").getPathString(),
        "module(name='B',version='1.0')",
        "bazel_dep(name='C',version='2.0')");
    scratch.file(rootDirectory.getRelative("code_for_b/WORKSPACE").getPathString());
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B',version='1.0');bazel_dep(name='C',version='3.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    // The version is empty here due to the override.
    SkyKey skyKey =
        ModuleFileValue.key(createModuleKey("B", ""), LocalPathOverride.create("code_for_b"));
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            Module.builder()
                .setName("B")
                .setVersion(Version.parse("1.0"))
                .setKey(createModuleKey("B", ""))
                .addDep("C", createModuleKey("C", "2.0"))
                .build());
  }

  @Test
  public void testRegistryOverride() throws Exception {
    FakeRegistry registry1 =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B',version='1.0',compatibility_level=4)",
                "bazel_dep(name='C',version='2.0')");
    FakeRegistry registry2 =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("B", "1.0"),
                "module(name='B',version='1.0',compatibility_level=6)",
                "bazel_dep(name='C',version='3.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry1.getUrl()));

    // Override the registry for B to be registry2 (instead of the default registry1).
    SkyKey skyKey =
        ModuleFileValue.key(
            createModuleKey("B", "1.0"),
            SingleVersionOverride.create(Version.EMPTY, registry2.getUrl(), ImmutableList.of(), 0));
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            Module.builder()
                .setName("B")
                .setVersion(Version.parse("1.0"))
                .setKey(createModuleKey("B", "1.0"))
                .setCompatibilityLevel(6)
                .addDep("C", createModuleKey("C", "3.0"))
                .setRegistry(registry2)
                .build());
  }

  @Test
  public void testModuleExtensions_good() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("mymod", "1.0"),
                "module(name='mymod',version='1.0')",
                "myext1 = use_extension('//:defs.bzl','myext1')",
                "use_repo(myext1, 'repo1')",
                "myext1.tag(key='val')",
                "myext2 = use_extension('//:defs.bzl','myext2')",
                "use_repo(myext2, 'repo2', other_repo1='repo1')",
                "myext2.tag1(key1='val1')",
                "myext2.tag2(key2='val2')",
                "bazel_dep(name='rules_jvm_external',version='2.0')",
                "maven = use_extension('@rules_jvm_external//:defs.bzl','maven')",
                "use_repo(maven, mvn='maven')",
                "maven.dep(coord='junit')",
                "use_repo(maven, 'junit', 'guava')",
                "maven.dep(coord='guava')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("mymod", "1.0"), null);
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            Module.builder()
                .setName("mymod")
                .setVersion(Version.parse("1.0"))
                .setKey(createModuleKey("mymod", "1.0"))
                .addDep("rules_jvm_external", createModuleKey("rules_jvm_external", "2.0"))
                .setRegistry(registry)
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//:defs.bzl")
                        .setExtensionName("myext1")
                        .setLocation(Location.fromFileLineColumn("mymod@1.0/MODULE.bazel", 2, 23))
                        .setImports(ImmutableBiMap.of("repo1", "repo1"))
                        .addTag(
                            Tag.builder()
                                .setTagName("tag")
                                .setAttributeValues(
                                    Dict.<String, Object>builder()
                                        .put("key", "val")
                                        .buildImmutable())
                                .setLocation(
                                    Location.fromFileLineColumn("mymod@1.0/MODULE.bazel", 4, 11))
                                .build())
                        .build())
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//:defs.bzl")
                        .setExtensionName("myext2")
                        .setLocation(Location.fromFileLineColumn("mymod@1.0/MODULE.bazel", 5, 23))
                        .setImports(ImmutableBiMap.of("other_repo1", "repo1", "repo2", "repo2"))
                        .addTag(
                            Tag.builder()
                                .setTagName("tag1")
                                .setAttributeValues(
                                    Dict.<String, Object>builder()
                                        .put("key1", "val1")
                                        .buildImmutable())
                                .setLocation(
                                    Location.fromFileLineColumn("mymod@1.0/MODULE.bazel", 7, 12))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag2")
                                .setAttributeValues(
                                    Dict.<String, Object>builder()
                                        .put("key2", "val2")
                                        .buildImmutable())
                                .setLocation(
                                    Location.fromFileLineColumn("mymod@1.0/MODULE.bazel", 8, 12))
                                .build())
                        .build())
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("@rules_jvm_external//:defs.bzl")
                        .setExtensionName("maven")
                        .setLocation(Location.fromFileLineColumn("mymod@1.0/MODULE.bazel", 10, 22))
                        .setImports(
                            ImmutableBiMap.of("mvn", "maven", "junit", "junit", "guava", "guava"))
                        .addTag(
                            Tag.builder()
                                .setTagName("dep")
                                .setAttributeValues(
                                    Dict.<String, Object>builder()
                                        .put("coord", "junit")
                                        .buildImmutable())
                                .setLocation(
                                    Location.fromFileLineColumn("mymod@1.0/MODULE.bazel", 12, 10))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("dep")
                                .setAttributeValues(
                                    Dict.<String, Object>builder()
                                        .put("coord", "guava")
                                        .buildImmutable())
                                .setLocation(
                                    Location.fromFileLineColumn("mymod@1.0/MODULE.bazel", 14, 10))
                                .build())
                        .build())
                .build());
  }

  @Test
  public void testModuleExtensions_duplicateProxy() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("mymod", "1.0"),
                "module(name='mymod',version='1.0')",
                "myext1 = use_extension('//:defs.bzl','myext')",
                "myext2 = use_extension('//:defs.bzl','myext')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("mymod", "1.0"), null);
    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertContainsEvent("this extension is already being used at");
  }

  @Test
  public void testModuleExtensions_repoNameCollision_localRepoName() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("mymod", "1.0"),
                "module(name='mymod',version='1.0')",
                "myext = use_extension('//:defs.bzl','myext')",
                "use_repo(myext, mymod='some_repo')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("mymod", "1.0"), null);
    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);

    assertContainsEvent(
        "The repo name 'mymod' is already being used as the current module name at");
  }

  @Test
  public void testModuleExtensions_repoNameCollision_exportedRepoName() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("mymod", "1.0"),
                "module(name='mymod',version='1.0')",
                "myext = use_extension('//:defs.bzl','myext')",
                "use_repo(myext, 'some_repo', again='some_repo')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("mymod", "1.0"), null);
    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);

    assertContainsEvent(
        "The repo exported as 'some_repo' by module extension 'myext' is already imported at");
  }

  @Test
  public void testModuelFileExecute_syntaxError() throws Exception {
    scratch.file(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1',compatibility_level=4)",
        "foo()");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertContainsEvent("name 'foo' is not defined");
  }

  @Test
  public void testModuelFileExecute_evalError() throws Exception {
    scratch.file(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='A',version='0.1',compatibility_level=\"4\")");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("parameter 'compatibility_level' got value of type 'string', want 'int'");
  }
}
