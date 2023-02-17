// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.ConstantRuleVisibility;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Injected;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.common.options.Options;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkInt;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for BzlLoadFunction. */
@RunWith(JUnit4.class)
public class BzlLoadFunctionTest extends BuildViewTestCase {
  private Path moduleRoot;
  private FakeRegistry registry;

  @Override
  protected FileSystem createFileSystem() {
    return new CustomInMemoryFs();
  }

  @Before
  public final void preparePackageLoading() throws Exception {
    Path alternativeRoot = scratch.dir("/root_2");
    PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
    packageOptions.defaultVisibility = ConstantRuleVisibility.PUBLIC;
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;
    getSkyframeExecutor()
        .preparePackageLoading(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory), Root.fromPath(alternativeRoot)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
            packageOptions,
            Options.getDefaults(BuildLanguageOptions.class),
            UUID.randomUUID(),
            ImmutableMap.of(),
            new TimestampGranularityMonitor(BlazeClock.instance()));
    skyframeExecutor.setActionEnv(ImmutableMap.of());
  }

  @Override
  protected ImmutableList<Injected> extraPrecomputedValues() {
    try {
      moduleRoot = scratch.dir("modules");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    registry = FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString());
    return ImmutableList.of(
        PrecomputedValue.injected(
            ModuleFileFunction.REGISTRIES, ImmutableList.of(registry.getUrl())),
        PrecomputedValue.injected(ModuleFileFunction.IGNORE_DEV_DEPS, false),
        PrecomputedValue.injected(ModuleFileFunction.MODULE_OVERRIDES, ImmutableMap.of()),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.ALLOWED_YANKED_VERSIONS, ImmutableList.of()),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES, CheckDirectDepsMode.WARNING),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE, BazelCompatibilityMode.ERROR));
  }

  @Before
  public void setUpForBzlmod() throws Exception {
    scratch.file("MODULE.bazel");
  }

  @Test
  public void testBzlLoadLabels() throws Exception {
    scratch.file("pkg1/BUILD");
    scratch.file("pkg1/ext.bzl");
    checkSuccessfulLookup("//pkg1:ext.bzl");

    scratch.file("pkg2/BUILD");
    scratch.file("pkg2/dir/ext.bzl");
    checkSuccessfulLookup("//pkg2:dir/ext.bzl");

    scratch.file("dir/pkg3/BUILD");
    scratch.file("dir/pkg3/dir/ext.bzl");
    checkSuccessfulLookup("//dir/pkg3:dir/ext.bzl");
  }

  @Test
  public void testBzlLoadLabelsAlternativeRoot() throws Exception {
    scratch.file("/root_2/pkg4/BUILD");
    scratch.file("/root_2/pkg4/ext.bzl");
    checkSuccessfulLookup("//pkg4:ext.bzl");
  }

  @Test
  public void testBzlLoadLabelsMultipleBuildFiles() throws Exception {
    scratch.file("dir1/BUILD");
    scratch.file("dir1/dir2/BUILD");
    scratch.file("dir1/dir2/ext.bzl");
    checkSuccessfulLookup("//dir1/dir2:ext.bzl");
  }

  @Test
  public void testLoadFromStarlarkFileInRemoteRepo() throws Exception {
    scratch.overwriteFile(
        "WORKSPACE",
        "local_repository(",
        "    name = 'a_remote_repo',",
        "    path = '/a_remote_repo'",
        ")");
    scratch.file("/a_remote_repo/WORKSPACE");
    scratch.file("/a_remote_repo/remote_pkg/BUILD");
    scratch.file("/a_remote_repo/remote_pkg/ext1.bzl", "load(':ext2.bzl', 'CONST')");
    scratch.file("/a_remote_repo/remote_pkg/ext2.bzl", "CONST = 17");
    checkSuccessfulLookup("@a_remote_repo//remote_pkg:ext1.bzl");
  }

  @Test
  public void testLoadRelativeLabel() throws Exception {
    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext1.bzl", "a = 1");
    scratch.file("pkg/ext2.bzl", "load(':ext1.bzl', 'a')");
    checkSuccessfulLookup("//pkg:ext2.bzl");
  }

  @Test
  public void testLoadAbsoluteLabel() throws Exception {
    scratch.file("pkg2/BUILD");
    scratch.file("pkg3/BUILD");
    scratch.file("pkg2/ext.bzl", "b = 1");
    scratch.file("pkg3/ext.bzl", "load('//pkg2:ext.bzl', 'b')");
    checkSuccessfulLookup("//pkg3:ext.bzl");
  }

  @Test
  public void testLoadFromSameAbsoluteLabelTwice() throws Exception {
    scratch.file("pkg1/BUILD");
    scratch.file("pkg2/BUILD");
    scratch.file("pkg1/ext.bzl", "a = 1", "b = 2");
    scratch.file("pkg2/ext.bzl", "load('//pkg1:ext.bzl', 'a')", "load('//pkg1:ext.bzl', 'b')");
    checkSuccessfulLookup("//pkg2:ext.bzl");
  }

  @Test
  public void testLoadFromSameRelativeLabelTwice() throws Exception {
    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext1.bzl", "a = 1", "b = 2");
    scratch.file("pkg/ext2.bzl", "load(':ext1.bzl', 'a')", "load(':ext1.bzl', 'b')");
    checkSuccessfulLookup("//pkg:ext2.bzl");
  }

  @Test
  public void testLoadFromRelativeLabelInSubdir() throws Exception {
    scratch.file("pkg/BUILD");
    scratch.file("pkg/subdir/ext1.bzl", "a = 1");
    scratch.file("pkg/subdir/ext2.bzl", "load(':subdir/ext1.bzl', 'a')");
    checkSuccessfulLookup("//pkg:subdir/ext2.bzl");
  }

  private EvaluationResult<BzlLoadValue> get(SkyKey skyKey) throws Exception {
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    if (result.hasError()) {
      fail(result.getError(skyKey).getException().getMessage());
    }
    return result;
  }

  private static SkyKey key(String label) {
    return BzlLoadValue.keyForBuild(Label.parseAbsoluteUnchecked(label));
  }

  /** Loads a .bzl with the given label and asserts success. */
  private void checkSuccessfulLookup(String label) throws Exception {
    SkyKey skyKey = key(label);
    EvaluationResult<BzlLoadValue> result = get(skyKey);
    // Ensure that the file has been processed by checking its Module for the label field.
    assertThat(label)
        .isEqualTo(BazelModuleContext.of(result.get(skyKey).getModule()).label().toString());
  }

  /* Loads a .bzl with the given label and asserts BzlLoadFailedException with the given message. */
  private void checkFailingLookup(String label, String expectedMessage)
      throws InterruptedException {
    SkyKey skyKey = key(label);
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .isInstanceOf(BzlLoadFailedException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(expectedMessage);
  }

  @Test
  public void testBzlLoadNoBuildFile() throws Exception {
    scratch.file("pkg/ext.bzl", "");
    SkyKey skyKey = key("//pkg:ext.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    String errorMessage = errorInfo.getException().getMessage();
    assertThat(errorMessage)
        .contains(
            "Every .bzl file must have a corresponding package, but '//pkg:ext.bzl' does not");
  }

  @Test
  public void testBzlLoadNoBuildFileForLoad() throws Exception {
    scratch.file("pkg2/BUILD");
    scratch.file("pkg1/ext.bzl", "a = 1");
    scratch.file("pkg2/ext.bzl", "load('//pkg1:ext.bzl', 'a')");
    SkyKey skyKey = key("//pkg:ext.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    String errorMessage = errorInfo.getException().getMessage();
    assertThat(errorMessage).contains("Every .bzl file must have a corresponding package");
  }

  @Test
  public void testBzlLoadFilenameWithControlChars() throws Exception {
    scratch.file("pkg/BUILD", "");
    scratch.file("pkg/ext.bzl", "load('//pkg:oops\u0000.bzl', 'a')");
    SkyKey skyKey = key("//pkg:ext.bzl");
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                SkyframeExecutorTestUtils.evaluate(
                    getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter));
    String errorMessage = e.getMessage();
    assertThat(errorMessage)
        .contains(
            "invalid target name 'oops<?>.bzl': "
                + "target names may not contain non-printable characters: '\\x00'");
  }

  @Test
  public void testLoadFromExternalRepoInWorkspaceFileAllowed() throws Exception {
    Path p =
        scratch.overwriteFile(
            "WORKSPACE",
            "local_repository(",
            "    name = 'a_remote_repo',",
            "    path = '/a_remote_repo'",
            ")");
    scratch.file("/a_remote_repo/WORKSPACE");
    scratch.file("/a_remote_repo/remote_pkg/BUILD");
    scratch.file("/a_remote_repo/remote_pkg/ext.bzl", "CONST = 17");

    RootedPath rootedPath =
        RootedPath.toRootedPath(
            Root.fromPath(p.getParentDirectory()), PathFragment.create("WORKSPACE"));

    SkyKey skyKey =
        BzlLoadValue.keyForWorkspace(
            Label.parseAbsoluteUnchecked("@a_remote_repo//remote_pkg:ext.bzl"),
            /* inWorkspace= */
            /* workspaceChunk= */ 0,
            rootedPath);
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);

    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testLoadFromSubdirInSamePackageIsOk() throws Exception {
    scratch.file("a/BUILD");
    scratch.file("a/a.bzl", "load('//a:b/b.bzl', 'b')");
    scratch.file("a/b/b.bzl", "b = 42");

    checkSuccessfulLookup("//a:a.bzl");
  }

  @Test
  public void testLoadMustRespectPackageBoundary_ofSubpkg() throws Exception {
    scratch.file("a/BUILD");
    scratch.file("a/a.bzl", "load('//a:b/b.bzl', 'b')");
    scratch.file("a/b/BUILD", "");
    scratch.file("a/b/b.bzl", "b = 42");
    checkFailingLookup(
        "//a:a.bzl",
        "Label '//a:b/b.bzl' is invalid because 'a/b' is a subpackage; perhaps you meant to"
            + " put the colon here: '//a/b:b.bzl'?");
  }

  @Test
  public void testLoadMustRespectPackageBoundary_ofSubpkg_relative() throws Exception {
    scratch.file("a/BUILD");
    scratch.file("a/a.bzl", "load('b/b.bzl', 'b')");
    scratch.file("a/b/BUILD", "");
    scratch.file("a/b/b.bzl", "b = 42");
    checkFailingLookup(
        "//a:a.bzl",
        "Label '//a:b/b.bzl' is invalid because 'a/b' is a subpackage; perhaps you meant to"
            + " put the colon here: '//a/b:b.bzl'?");
  }

  @Test
  public void testLoadMustRespectPackageBoundary_ofIndirectSubpkg() throws Exception {
    scratch.file("a/BUILD");
    scratch.file("a/a.bzl", "load('//a/b:c/c.bzl', 'c')");
    scratch.file("a/b/BUILD", "");
    scratch.file("a/b/c/BUILD", "");
    scratch.file("a/b/c/c.bzl", "c = 42");
    checkFailingLookup(
        "//a:a.bzl",
        "Label '//a/b:c/c.bzl' is invalid because 'a/b/c' is a subpackage; perhaps you meant"
            + " to put the colon here: '//a/b/c:c.bzl'?");
  }

  @Test
  public void testLoadMustRespectPackageBoundary_ofParentPkg() throws Exception {
    scratch.file("a/b/BUILD");
    scratch.file("a/b/b.bzl", "load('//a/c:c/c.bzl', 'c')");
    scratch.file("a/BUILD");
    scratch.file("a/c/c/c.bzl", "c = 42");
    checkFailingLookup(
        "//a/b:b.bzl",
        "Label '//a/c:c/c.bzl' is invalid because 'a/c' is not a package; perhaps you meant to "
            + "put the colon here: '//a:c/c/c.bzl'?");
  }

  @Test
  public void testBzlVisibility_disabledWithoutFlag() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=false");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility(\"private\")",
        "x = 1");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'b/bar.bzl' failed");
    assertContainsEvent("Use of `visibility()` requires --experimental_bzl_visibility");
  }

  @Test
  public void testBzlVisibility_disabledWithoutAllowlist() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true",
        // Put a in allowlist, but not b, to show that it's b we need and not a.
        "--experimental_bzl_visibility_allowlist=a");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility(\"public\")",
        "x = 1");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'b/bar.bzl' failed");
    assertContainsEvent("`visibility() is not enabled for package //b");
  }

  @Test
  public void testBzlVisibility_allowlistAcceptsLeadingDoubleSlash() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=//b");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility(\"public\")",
        "x = 1");

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_allowlistExcludesSubpackagesWithoutWildcard() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=b");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b/subpkg:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file("b/subpkg/BUILD");
    scratch.file(
        "b/subpkg/bar.bzl", //
        "visibility(\"public\")",
        "x = 1");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'b/subpkg/bar.bzl' failed");
    assertContainsEvent("`visibility() is not enabled for package //b/subpkg");
  }

  @Test
  public void testBzlVisibility_allowlistIncludesSubpackagesWithWildcard() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=b/...");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b/subpkg:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file("b/subpkg/BUILD");
    scratch.file(
        "b/subpkg/bar.bzl", //
        "visibility(\"public\")",
        "x = 1");

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_enabledWhenAllowlistDisabled() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true",
        // Put unrelated c in allowlist, but not b, to show that "everyone" disables checking when
        // included as a list item
        "--experimental_bzl_visibility_allowlist=c,everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility(\"public\")",
        "x = 1");

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_malformedAllowlist() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true",
        // Not a valid package name.
        "--experimental_bzl_visibility_allowlist=:::");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility(\"public\")");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("Invalid bzl-visibility allowlist");
  }

  @Test
  public void testBzlVisibility_publicExplicit() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility(\"public\")",
        "x = 1");

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_publicImplicit() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        // No visibility() declaration, defaults to public.
        "x = 1");

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_privateSamePackage() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//a:bar.bzl\", \"x\")");
    scratch.file(
        "a/bar.bzl", //
        "visibility(\"private\")",
        "x = 1");

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_privateDifferentPackage() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility(\"private\")",
        "x = 1");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a:foo.bzl", "module //a:foo.bzl contains .bzl load-visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a.");
  }

  @Test
  public void testBzlVisibility_emptyListMeansPrivate() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility([])",
        "x = 1");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a:foo.bzl", "module //a:foo.bzl contains .bzl load-visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a.");
  }

  @Test
  public void testBzlVisibility_publicListElement() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        // Tests "public" as a list item, and alongside other list items.
        "visibility([\"public\", \"//c\"])",
        "x = 1");

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_privateListElement() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a1/BUILD");
    scratch.file(
        "a1/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a2/BUILD");
    scratch.file(
        "a2/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        // Tests "private" as a list item, and alongside other list items.
        "visibility([\"private\", \"//a1\"])",
        "x = 1");

    checkSuccessfulLookup("//a1:foo.bzl");
    assertNoEvents();
    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a2:foo.bzl", "module //a2:foo.bzl contains .bzl load-visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.");
  }

  @Test
  public void testBzlVisibility_failureInDependency() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "load(\"//c:baz.bzl\", \"y\")",
        "visibility(\"public\")",
        "x = y");
    scratch.file("c/BUILD");
    scratch.file(
        "c/baz.bzl", //
        "visibility(\"private\")",
        "y = 1");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a:foo.bzl",
        "at /workspace/a/foo.bzl:1:6: module //b:bar.bzl contains .bzl load-visibility violations");
    assertContainsEvent("Starlark file //c:baz.bzl is not visible for loading from package //b.");
  }

  @Test
  public void testBzlVisibility_cannotBeSetInFunction() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "def helper():",
        "    visibility(\"public\")",
        "helper()");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent(".bzl visibility may only be set at the top level");
  }

  @Test
  public void testBzlVisibility_cannotBeSetTwice() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility(\"public\")",
        "visibility(\"public\")");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent(".bzl visibility may not be set more than once");
  }

  @Test
  public void testBzlVisibility_enumeratedPackages() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a1/BUILD");
    scratch.file(
        "a1/foo1.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a2/BUILD");
    scratch.file(
        "a2/foo2.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility([\"//a1\"])",
        "x = 1");

    checkSuccessfulLookup("//a1:foo1.bzl");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a2:foo2.bzl", "module //a2:foo2.bzl contains .bzl load-visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.");
  }

  @Test
  public void testBzlVisibility_singleEnumeratedPackageAsString() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a1/BUILD");
    scratch.file(
        "a1/foo1.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a2/BUILD");
    scratch.file(
        "a2/foo2.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        // Note: "//a1", not ["//a1"]
        "visibility(\"//a1\")",
        "x = 1");

    checkSuccessfulLookup("//a1:foo1.bzl");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a2:foo2.bzl", "module //a2:foo2.bzl contains .bzl load-visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.");
  }

  @Test
  public void testBzlVisibility_enumeratedPackagesMultipleRepos() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    // @repo//pkg:foo1.bzl and @//pkg:foo2.bzl both try to access @repo//lib:bar.bzl. Test that when
    // bar.bzl declares a visibility allowing "//pkg", it means @repo//pkg and *not* @//pkg.
    scratch.overwriteFile(
        "WORKSPACE", //
        "local_repository(",
        "    name = 'repo',",
        "    path = 'repo'",
        ")");
    scratch.file("repo/WORKSPACE");
    scratch.file("repo/pkg/BUILD");
    scratch.file(
        "repo/pkg/foo1.bzl", //
        "load(\"//lib:bar.bzl\", \"x\")");
    scratch.file("repo/lib/BUILD");
    scratch.file(
        "repo/lib/bar.bzl", //
        "visibility([\"//pkg\"])",
        "x = 1");
    scratch.file("pkg/BUILD");
    scratch.file(
        "pkg/foo2.bzl", //
        "load(\"@repo//lib:bar.bzl\", \"x\")");

    checkSuccessfulLookup("@repo//pkg:foo1.bzl");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//pkg:foo2.bzl", "module //pkg:foo2.bzl contains .bzl load-visibility violations");
    assertContainsEvent(
        "Starlark file @repo//lib:bar.bzl is not visible for loading from package //pkg.");
  }

  // TODO(#16365): This test case can be deleted once --incompatible_package_group_has_public_syntax
  // is deleted (not just flipped).
  @Test
  public void testBzlVisibility_canUsePublicPrivate_regardlessOfFlag() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true",
        "--experimental_bzl_visibility_allowlist=everyone",
        // Test that we can use "public" and "private" visibility for .bzl files even when the
        // incompatible flag is disabled.
        "--incompatible_package_group_has_public_syntax=false");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo1.bzl", //
        "visibility(\"public\")");
    scratch.file(
        "a/foo2.bzl", //
        "visibility(\"private\")");

    checkSuccessfulLookup("//a:foo1.bzl");
    checkSuccessfulLookup("//a:foo2.bzl");
    assertNoEvents();
  }

  // TODO(#16324): Once --incompatible_fix_package_group_reporoot_syntax is deleted (not just
  // flipped), this test case will be redundant with tests for //... in PackageGroupTest. At that
  // point we'll just delete this test case.
  @Test
  public void testBzlVisibility_repoRootSubpackagesIsNotPublic_regardlessOfFlag() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true",
        "--experimental_bzl_visibility_allowlist=everyone",
        // Test that we get the fixed behavior even when the incompatible flag is disabled.
        "--incompatible_fix_package_group_reporoot_syntax=false");

    scratch.overwriteFile(
        "WORKSPACE", //
        "local_repository(",
        "    name = 'repo',",
        "    path = 'repo'",
        ")");
    scratch.file("repo/WORKSPACE");
    scratch.file("repo/a/BUILD");
    scratch.file(
        "repo/a/foo.bzl", //
        "load(\"@//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility([\"//...\"])",
        "x = 1");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "@repo//a:foo.bzl", "module @repo//a:foo.bzl contains .bzl load-visibility violations");
    assertContainsEvent(
        "Starlark file //b:bar.bzl is not visible for loading from package @repo//a.");
  }

  @Test
  public void testBzlVisibility_disallowsSubpackagesWithoutWildcard() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo1.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a/subpkg/BUILD");
    scratch.file(
        "a/subpkg/foo2.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility([\"//a\"])",
        "x = 1");

    checkSuccessfulLookup("//a:foo1.bzl");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a/subpkg:foo2.bzl",
        "module //a/subpkg:foo2.bzl contains .bzl load-visibility violations");
    assertContainsEvent(
        "Starlark file //b:bar.bzl is not visible for loading from package //a/subpkg.");
  }

  @Test
  public void testBzlVisibility_allowsSubpackagesWithWildcard() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo1.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a/subpkg/BUILD");
    scratch.file(
        "a/subpkg/foo2.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl", //
        "visibility([\"//a/...\"])",
        "x = 1");

    checkSuccessfulLookup("//a:foo1.bzl");
    assertNoEvents();

    checkSuccessfulLookup("//a/subpkg:foo2.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_invalid_badType() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility(123)");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("Invalid bzl-visibility: got 'int', want string or list of strings");
  }

  @Test
  public void testBzlVisibility_invalid_badElementType() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility([\"//a\", 123])");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("at index 0 of visibility list, got element of type int, want string");
  }

  @Test
  public void testBzlVisibility_invalid_packageOutsideRepo() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility([\"@repo//b\"])");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("invalid package name '@repo//b': must start with '//'");
  }

  @Test
  public void testBzlVisibility_invalid_negationNotSupported() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true", "--experimental_bzl_visibility_allowlist=everyone");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility([\"-//a\"])");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("Cannot use negative package patterns here");
  }

  @Test
  public void testLoadFromNonExistentRepository_producesMeaningfulError() throws Exception {
    scratch.file("BUILD", "load(\"@repository//dir:file.bzl\", \"foo\")");

    SkyKey skyKey = key("@repository//dir:file.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .isInstanceOf(BzlLoadFailedException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "Unable to find package for @repository//dir:file.bzl: The repository '@repository' "
                + "could not be resolved: Repository '@repository' is not defined.");
  }

  @Test
  public void testLoadBzlFileFromWorkspaceWithRemapping() throws Exception {
    Path p =
        scratch.overwriteFile(
            "WORKSPACE",
            "local_repository(",
            "    name = 'y',",
            "    path = '/y'",
            ")",
            "local_repository(",
            "    name = 'a',",
            "    path = '/a',",
            "    repo_mapping = {'@x' : '@y'}",
            ")",
            "load('@a//:a.bzl', 'a_symbol')");

    scratch.file("/y/WORKSPACE");
    scratch.file("/y/BUILD");
    scratch.file("/y/y.bzl", "y_symbol = 5");

    scratch.file("/a/WORKSPACE");
    scratch.file("/a/BUILD");
    scratch.file("/a/a.bzl", "load('@x//:y.bzl', 'y_symbol')", "a_symbol = y_symbol");

    Root root = Root.fromPath(p.getParentDirectory());
    RootedPath rootedPath = RootedPath.toRootedPath(root, PathFragment.create("WORKSPACE"));

    SkyKey skyKey =
        BzlLoadValue.keyForWorkspace(Label.parseAbsoluteUnchecked("@a//:a.bzl"), 1, rootedPath);

    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);

    assertThat(result.get(skyKey).getModule().getGlobals())
        .containsEntry("a_symbol", StarlarkInt.of(5));
  }

  @Test
  public void testLoadBzlFileFromBzlmod() throws Exception {
    setBuildLanguageOptions("--enable_bzlmod");
    scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='foo',version='1.0')");
    registry
        .addModule(
            createModuleKey("foo", "1.0"),
            "module(name='foo',version='1.0')",
            "bazel_dep(name='bar',version='2.0',repo_name='bar_alias')")
        .addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')");
    Path fooDir = moduleRoot.getRelative("foo~1.0");
    scratch.file(fooDir.getRelative("WORKSPACE").getPathString());
    scratch.file(fooDir.getRelative("BUILD").getPathString());
    scratch.file(
        fooDir.getRelative("test.bzl").getPathString(),
        "load('@bar_alias//:test.bzl', 'haha')",
        "hoho = haha");
    Path barDir = moduleRoot.getRelative("bar~2.0");
    scratch.file(barDir.getRelative("WORKSPACE").getPathString());
    scratch.file(barDir.getRelative("BUILD").getPathString());
    scratch.file(barDir.getRelative("test.bzl").getPathString(), "haha = 5");

    SkyKey skyKey = BzlLoadValue.keyForBzlmod(Label.parseCanonical("@@foo~1.0//:test.bzl"));
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);

    assertThatEvaluationResult(result).hasNoError();
    assertThat(result.get(skyKey).getModule().getGlobals())
        .containsEntry("hoho", StarlarkInt.of(5));
    // Note that we're not testing the case of a non-registry override using @bazel_tools here, but
    // that is incredibly hard to set up in a unit test. So we should just rely on integration tests
    // for that.
  }

  @Test
  public void testBuiltinsInjectionFailure() throws Exception {
    setBuildLanguageOptions("--experimental_builtins_bzl_path=tools/builtins_staging");
    scratch.file(
        "tools/builtins_staging/exports.bzl",
        "1 // 0  # <-- dynamic error",
        "exported_toplevels = {}",
        "exported_rules = {}",
        "exported_to_java = {}");
    scratch.file("pkg/BUILD");
    scratch.file("pkg/foo.bzl");
    reporter.removeHandler(failFastHandler);

    SkyKey key = key("//pkg:foo.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);

    assertContainsEvent(
        "File \"/workspace/tools/builtins_staging/exports.bzl\", line 1, column 3, in <toplevel>");
    assertContainsEvent("Error: integer division by zero");
    Exception ex = result.getError(key).getException();
    assertThat(ex)
        .hasMessageThat()
        .contains(
            "Internal error while loading Starlark builtins for //pkg:foo.bzl: Failed to load"
                + " builtins sources: initialization of module 'exports.bzl' (internal) failed");
  }

  @Test
  public void testErrorReadingBzlFileIsTransientWhenUsingASTInlining() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) fileSystem;
    scratch.file("a/BUILD");
    fs.badPathForRead = scratch.file("a/a1.bzl", "doesntmatter");

    SkyKey key = key("//a:a1.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isTransient();
  }

  @Test
  public void testErrorReadingOtherBzlFileIsPersistentFromPerspectiveOfParent() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) fileSystem;
    scratch.file("a/BUILD");
    scratch.file("a/a1.bzl", "load('//a:a2.bzl', 'a2')");
    fs.badPathForRead = scratch.file("a/a2.bzl", "doesntmatter");

    SkyKey key = key("//a:a1.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isNotTransient();
  }

  @Test
  public void testErrorStatingBzlFileInFileStateFunctionIsPersistent() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) fileSystem;
    scratch.file("a/BUILD");
    fs.badPathForStat = scratch.file("a/a1.bzl", "doesntmatter");

    SkyKey key = key("//a:a1.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isNotTransient();
  }

  private static class CustomInMemoryFs extends InMemoryFileSystem {
    @Nullable private Path badPathForStat;
    @Nullable private Path badPathForRead;

    CustomInMemoryFs() {
      super(DigestHashFunction.SHA256);
    }

    @Override
    public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
      if (badPathForStat != null && badPathForStat.asFragment().equals(path)) {
        throw new IOException("bad");
      }
      return super.statIfFound(path, followSymlinks);
    }

    @Override
    protected synchronized InputStream getInputStream(PathFragment path) throws IOException {
      if (badPathForRead != null && badPathForRead.asFragment().equals(path)) {
        throw new IOException("bad");
      }
      return super.getInputStream(path);
    }
  }
}
