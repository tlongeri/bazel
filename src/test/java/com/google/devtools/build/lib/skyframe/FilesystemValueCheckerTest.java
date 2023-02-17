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
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Runnables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.TestAction;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.io.FileSymlinkCycleUniquenessFunction;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.BasicFilesystemDirtinessChecker;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestPackageFactoryBuilderFactory;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.testutil.TimestampGranularityUtils;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.BatchStat;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileStatusWithDigestAdapter;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.skyframe.Differencer.Diff;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link FilesystemValueChecker}. */
@RunWith(TestParameterInjector.class)
public final class FilesystemValueCheckerTest extends FilesystemValueCheckerTestBase {
  private static final EvaluationContext EVALUATION_OPTIONS =
      EvaluationContext.newBuilder()
          .setKeepGoing(false)
          .setNumThreads(SkyframeExecutor.DEFAULT_THREAD_COUNT)
          .setEventHandler(NullEventHandler.INSTANCE)
          .build();

  private RecordingDifferencer differencer;
  private MemoizingEvaluator evaluator;
  private SequentialBuildDriver driver;
  private Path pkgRoot;

  @Before
  public void setUp() throws Exception {
    ImmutableMap.Builder<SkyFunctionName, SkyFunction> skyFunctions = ImmutableMap.builder();

    pkgRoot = fs.getPath("/testroot");
    pkgRoot.createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(pkgRoot.getRelative("WORKSPACE"));

    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                fs.getPath("/output_base"),
                ImmutableList.of(Root.fromPath(pkgRoot)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(pkgRoot, pkgRoot, pkgRoot),
            pkgRoot,
            /* defaultSystemJavabase= */ null,
            TestConstants.PRODUCT_NAME);
    ExternalFilesHelper externalFilesHelper = ExternalFilesHelper.createForTesting(
        pkgLocator, ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS, directories);
    skyFunctions.put(
        FileStateValue.FILE_STATE,
        new FileStateFunction(
            new AtomicReference<>(),
            new AtomicReference<>(UnixGlob.DEFAULT_SYSCALLS),
            externalFilesHelper));
    skyFunctions.put(FileValue.FILE, new FileFunction(pkgLocator));
    skyFunctions.put(
        FileSymlinkCycleUniquenessFunction.NAME, new FileSymlinkCycleUniquenessFunction());
    skyFunctions.put(
        FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
        new FileSymlinkInfiniteExpansionUniquenessFunction());
    skyFunctions.put(
        SkyFunctions.PACKAGE,
        new PackageFunction(
            null,
            null,
            null,
            null,
            null,
            /*packageProgress=*/ null,
            PackageFunction.ActionOnIOExceptionReadingBuildFile.UseOriginalIOException.INSTANCE,
            PackageFunction.IncrementalityIntent.INCREMENTAL,
            k -> ThreadStateReceiver.NULL_INSTANCE));
    skyFunctions.put(
        SkyFunctions.PACKAGE_LOOKUP,
        new PackageLookupFunction(
            new AtomicReference<>(ImmutableSet.of()),
            CrossRepositoryLabelViolationStrategy.ERROR,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        WorkspaceFileValue.WORKSPACE_FILE,
        new WorkspaceFileFunction(
            TestRuleClassProvider.getRuleClassProvider(),
            TestPackageFactoryBuilderFactory.getInstance()
                .builder(directories)
                .build(TestRuleClassProvider.getRuleClassProvider(), fs),
            directories,
            /*bzlLoadFunctionForInlining=*/ null));
    skyFunctions.put(
        SkyFunctions.EXTERNAL_PACKAGE,
        new ExternalPackageFunction(BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));

    differencer = new SequencedRecordingDifferencer();
    evaluator = new InMemoryMemoizingEvaluator(skyFunctions.buildOrThrow(), differencer);
    driver = new SequentialBuildDriver(evaluator);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
  }

  @Test
  public void testEmpty() throws Exception {
    FilesystemValueChecker checker =
        new FilesystemValueChecker(
            /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST);
    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));
  }

  @Test
  public void testSimple() throws Exception {
    FilesystemValueChecker checker =
        new FilesystemValueChecker(
            /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST);

    Path path = fs.getPath("/foo");
    FileSystemUtils.createEmptyFile(path);
    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));

    SkyKey skyKey =
        FileStateValue.key(
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/foo")));
    EvaluationResult<SkyValue> result =
        driver.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isFalse();

    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));

    FileSystemUtils.writeContentAsLatin1(path, "hello");
    assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), skyKey);

    // The dirty bits are not reset until the FileValues are actually revalidated.
    assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), skyKey);

    differencer.invalidate(ImmutableList.of(skyKey));
    result = driver.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isFalse();
    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));
  }

  /**
   * Tests that an already-invalidated value can still be marked changed: symlink points at sym1.
   * Invalidate symlink by changing sym1 from pointing at path to point to sym2. This only dirties
   * (rather than changes) symlink because sym2 still points at path, so all symlink stats remain
   * the same. Then do a null build, change sym1 back to point at path, and change symlink to not be
   * a symlink anymore. The fact that it is not a symlink should be detected.
   */
  @Test
  public void testDirtySymlink() throws Exception {
    FilesystemValueChecker checker =
        new FilesystemValueChecker(
            /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST);

    Path path = fs.getPath("/foo");
    FileSystemUtils.writeContentAsLatin1(path, "foo contents");
    // We need the intermediate sym1 and sym2 so that we can dirty a child of symlink without
    // actually changing the FileValue calculated for symlink (if we changed the contents of foo,
    // the FileValue created for symlink would notice, since it stats foo).
    Path sym1 = fs.getPath("/sym1");
    Path sym2 = fs.getPath("/sym2");
    Path symlink = fs.getPath("/bar");
    FileSystemUtils.ensureSymbolicLink(symlink, sym1);
    FileSystemUtils.ensureSymbolicLink(sym1, path);
    FileSystemUtils.ensureSymbolicLink(sym2, path);
    SkyKey fooKey =
        FileValue.key(RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/foo")));
    RootedPath symlinkRootedPath =
        RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/bar"));
    SkyKey symlinkKey = FileValue.key(symlinkRootedPath);
    SkyKey symlinkFileStateKey = FileStateValue.key(symlinkRootedPath);
    RootedPath sym1RootedPath =
        RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/sym1"));
    SkyKey sym1FileStateKey = FileStateValue.key(sym1RootedPath);
    Iterable<SkyKey> allKeys = ImmutableList.of(symlinkKey, fooKey);

    // First build -- prime the graph.
    EvaluationResult<FileValue> result = driver.evaluate(allKeys, EVALUATION_OPTIONS);
    assertThat(result.hasError()).isFalse();
    FileValue symlinkValue = result.get(symlinkKey);
    FileValue fooValue = result.get(fooKey);
    assertWithMessage(symlinkValue.toString()).that(symlinkValue.isSymlink()).isTrue();
    // Digest is not always available, so use size as a proxy for contents.
    assertThat(symlinkValue.getSize()).isEqualTo(fooValue.getSize());
    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));

    // Before second build, move sym1 to point to sym2.
    assertThat(sym1.delete()).isTrue();
    FileSystemUtils.ensureSymbolicLink(sym1, sym2);
    assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), sym1FileStateKey);

    differencer.invalidate(ImmutableList.of(sym1FileStateKey));
    result = driver.evaluate(ImmutableList.of(), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isFalse();
    assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), sym1FileStateKey);

    // Before third build, move sym1 back to original (so change pruning will prevent signaling of
    // its parents, but change symlink for real.
    assertThat(sym1.delete()).isTrue();
    FileSystemUtils.ensureSymbolicLink(sym1, path);
    assertThat(symlink.delete()).isTrue();
    FileSystemUtils.writeContentAsLatin1(symlink, "new symlink contents");
    assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), symlinkFileStateKey);
    differencer.invalidate(ImmutableList.of(symlinkFileStateKey));
    result = driver.evaluate(allKeys, EVALUATION_OPTIONS);
    assertThat(result.hasError()).isFalse();
    symlinkValue = result.get(symlinkKey);
    assertWithMessage(symlinkValue.toString()).that(symlinkValue.isSymlink()).isFalse();
    assertThat(result.get(fooKey)).isEqualTo(fooValue);
    assertThat(symlinkValue.getSize()).isNotEqualTo(fooValue.getSize());
    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));
  }

  @Test
  public void testExplicitFiles() throws Exception {
    FilesystemValueChecker checker =
        new FilesystemValueChecker(
            /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST);

    Path path1 = fs.getPath("/foo1");
    Path path2 = fs.getPath("/foo2");
    FileSystemUtils.createEmptyFile(path1);
    FileSystemUtils.createEmptyFile(path2);
    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));

    SkyKey key1 =
        FileStateValue.key(
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/foo1")));
    SkyKey key2 =
        FileStateValue.key(
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/foo2")));
    Iterable<SkyKey> skyKeys = ImmutableList.of(key1, key2);
    EvaluationResult<SkyValue> result = driver.evaluate(skyKeys, EVALUATION_OPTIONS);
    assertThat(result.hasError()).isFalse();

    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));

    // Wait for the timestamp granularity to elapse, so updating the files will observably advance
    // their ctime.
    TimestampGranularityUtils.waitForTimestampGranularity(
        System.currentTimeMillis(), OutErr.SYSTEM_OUT_ERR);
    // Update path1's contents. This will update the file's ctime with current time indicated by the
    // clock.
    fs.advanceClockMillis(1);
    FileSystemUtils.writeContentAsLatin1(path1, "hello1");
    // Update path2's mtime but not its contents. We expect that an mtime change suffices to update
    // the ctime.
    path2.setLastModifiedTime(42);
    // Assert that both files changed. The change detection relies, among other things, on ctime
    // change.
    assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), key1, key2);

    differencer.invalidate(skyKeys);
    result = driver.evaluate(skyKeys, EVALUATION_OPTIONS);
    assertThat(result.hasError()).isFalse();
    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));
  }

  @Test
  public void testFileWithIOExceptionNotConsideredDirty() throws Exception {
    Path path = fs.getPath("/testroot/foo");
    path.getParentDirectory().createDirectory();
    path.createSymbolicLink(PathFragment.create("bar"));

    fs.readlinkThrowsIoException = true;
    SkyKey fileKey =
        FileStateValue.key(
            RootedPath.toRootedPath(Root.fromPath(pkgRoot), PathFragment.create("foo")));
    EvaluationResult<SkyValue> result =
        driver.evaluate(ImmutableList.of(fileKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();

    fs.readlinkThrowsIoException = false;
    FilesystemValueChecker checker =
        new FilesystemValueChecker(
            /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST);
    Diff diff = getDirtyFilesystemKeys(evaluator, checker);
    assertThat(diff.changedKeysWithoutNewValues()).isEmpty();
    assertThat(diff.changedKeysWithNewValues()).isEmpty();
  }

  @Test
  public void testFilesInCycleNotConsideredDirty() throws Exception {
    Path path1 = pkgRoot.getRelative("foo1");
    Path path2 = pkgRoot.getRelative("foo2");
    Path path3 = pkgRoot.getRelative("foo3");
    FileSystemUtils.ensureSymbolicLink(path1, path2);
    FileSystemUtils.ensureSymbolicLink(path2, path3);
    FileSystemUtils.ensureSymbolicLink(path3, path1);
    SkyKey fileKey1 = FileValue.key(RootedPath.toRootedPath(Root.fromPath(pkgRoot), path1));

    EvaluationResult<SkyValue> result =
        driver.evaluate(ImmutableList.of(fileKey1), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();

    FilesystemValueChecker checker =
        new FilesystemValueChecker(
            /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST);
    Diff diff = getDirtyFilesystemKeys(evaluator, checker);
    assertThat(diff.changedKeysWithoutNewValues()).isEmpty();
    assertThat(diff.changedKeysWithNewValues()).isEmpty();
  }

  public void checkDirtyActions(BatchStat batchStatter) throws Exception {
    Artifact out1 = createDerivedArtifact("fiz");
    Artifact out2 = createDerivedArtifact("pop");

    FileSystemUtils.writeContentAsLatin1(out1.getPath(), "hello");
    FileSystemUtils.writeContentAsLatin1(out2.getPath(), "fizzlepop");

    TimestampGranularityMonitor tsgm = new TimestampGranularityMonitor(BlazeClock.instance());
    SkyKey actionKey1 = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    SkyKey actionKey2 = ActionLookupData.create(ACTION_LOOKUP_KEY, 1);

    pretendBuildTwoArtifacts(out1, actionKey1, out2, actionKey2, batchStatter, tsgm);

    // Change the file but not its size
    FileSystemUtils.writeContentAsLatin1(out1.getPath(), "hallo");
    checkActionDirtiedByFile(out1, actionKey1, batchStatter, tsgm);
    pretendBuildTwoArtifacts(out1, actionKey1, out2, actionKey2, batchStatter, tsgm);

    // Now try with a different size
    FileSystemUtils.writeContentAsLatin1(out1.getPath(), "hallo2");
    checkActionDirtiedByFile(out1, actionKey1, batchStatter, tsgm);
  }

  private void pretendBuildTwoArtifacts(
      Artifact out1,
      SkyKey actionKey1,
      Artifact out2,
      SkyKey actionKey2,
      BatchStat batchStatter,
      TimestampGranularityMonitor tsgm)
      throws InterruptedException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(1)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();

    tsgm.setCommandStartTime();
    differencer.inject(
        ImmutableMap.<SkyKey, SkyValue>of(
            actionKey1,
                actionValue(
                    new TestAction(
                        Runnables.doNothing(),
                        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                        ImmutableSet.of(out1))),
            actionKey2,
                actionValue(
                    new TestAction(
                        Runnables.doNothing(),
                        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                        ImmutableSet.of(out2)))));
    assertThat(driver.evaluate(ImmutableList.of(), evaluationContext).hasError()).isFalse();
    assertThat(
            new FilesystemValueChecker(
                    /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    /* trustRemoteArtifacts= */ false))
        .isEmpty();

    tsgm.waitForTimestampGranularity(OutErr.SYSTEM_OUT_ERR);
  }

  private void checkActionDirtiedByFile(
      Artifact file, SkyKey actionKey, BatchStat batchStatter, TimestampGranularityMonitor tsgm)
      throws InterruptedException {
    assertThat(
            new FilesystemValueChecker(
                    tsgm, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    /* trustRemoteArtifacts= */ false))
        .containsExactly(actionKey);
    assertThat(
            new FilesystemValueChecker(
                    tsgm, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    ModifiedFileSet.EVERYTHING_DELETED,
                    /* trustRemoteArtifacts= */ false))
        .containsExactly(actionKey);
    assertThat(
            new FilesystemValueChecker(
                    tsgm, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    new ModifiedFileSet.Builder().modify(file.getExecPath()).build(),
                    /* trustRemoteArtifacts= */ false))
        .containsExactly(actionKey);
    assertThat(
            new FilesystemValueChecker(
                    tsgm, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    new ModifiedFileSet.Builder()
                        .modify(file.getExecPath().getParentDirectory())
                        .build(),
                    /* trustRemoteArtifacts= */ false))
        .isEmpty();
    assertThat(
            new FilesystemValueChecker(
                    tsgm, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    ModifiedFileSet.NOTHING_MODIFIED,
                    /* trustRemoteArtifacts= */ false))
        .isEmpty();
  }

  enum ModifiedSetReporting {
    EVERYTHING_MODIFIED {
      @Override
      ModifiedFileSet getModifiedFileSet(PathFragment path) {
        return ModifiedFileSet.EVERYTHING_MODIFIED;
      }
    },
    EVERYTHING_DELETED {
      @Override
      ModifiedFileSet getModifiedFileSet(PathFragment path) {
        return ModifiedFileSet.EVERYTHING_DELETED;
      }
    },
    SINGLE_PATH {
      @Override
      ModifiedFileSet getModifiedFileSet(PathFragment path) {
        return ModifiedFileSet.builder().modify(path).build();
      }
    };

    abstract ModifiedFileSet getModifiedFileSet(PathFragment path);
  }

  @Test
  public void getDirtyActionValues_touchedTreeDirectory_returnsEmptyDiff(
      @TestParameter BatchStatMode batchStatMode,
      @TestParameter({"", "subdir"}) String touchedTreePath,
      @TestParameter ModifiedSetReporting modifiedSet)
      throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    TreeFileArtifact treeFile = TreeFileArtifact.createTreeOutput(tree, "subdir/file");
    FileSystemUtils.writeIsoLatin1(treeFile.getPath(), "text");
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    differencer.inject(
        ImmutableMap.of(actionKey, actionValueWithTreeArtifacts(ImmutableList.of(treeFile))));
    evaluate();

    FileSystemUtils.touchFile(tree.getPath().getRelative(touchedTreePath));
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(tree.getExecPath()),
                    /*trustRemoteArtifacts=*/ false))
        .isEmpty();
  }

  @Test
  public void getDirtyActionValues_deleteEmptyTreeDirectory_returnsTreeKey(
      @TestParameter BatchStatMode batchStatMode, @TestParameter ModifiedSetReporting modifiedSet)
      throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    tree.getPath().createDirectoryAndParents();
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    differencer.inject(
        ImmutableMap.of(actionKey, actionValueWithTreeArtifact(tree, TreeArtifactValue.empty())));
    evaluate();

    assertThat(tree.getPath().delete()).isTrue();
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(tree.getExecPath()),
                    /*trustRemoteArtifacts=*/ false))
        .containsExactly(actionKey);
  }

  @Test
  public void getDirtyActionValues_treeDirectoryReplacedWithSymlink_returnsTreeKey(
      @TestParameter BatchStatMode batchStatMode) throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    tree.getPath().createDirectoryAndParents();
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    differencer.inject(
        ImmutableMap.of(actionKey, actionValueWithTreeArtifact(tree, TreeArtifactValue.empty())));
    evaluate();

    Path dummyEmptyDir = fs.getPath("/bin").getRelative("dir");
    dummyEmptyDir.createDirectoryAndParents();
    assertThat(tree.getPath().delete()).isTrue();
    tree.getPath().createSymbolicLink(dummyEmptyDir);

    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    /* trustRemoteArtifacts= */ false))
        .containsExactly(actionKey); // Symbolic links should count as dirty
  }

  @Test
  public void getDirtyActionValues_modifiedTreeFile_returnsTreeKey(
      @TestParameter BatchStatMode batchStatMode, @TestParameter ModifiedSetReporting modifiedSet)
      throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    TreeFileArtifact treeFile = TreeFileArtifact.createTreeOutput(tree, "file");
    FileSystemUtils.writeIsoLatin1(treeFile.getPath(), "text");
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    differencer.inject(
        ImmutableMap.of(actionKey, actionValueWithTreeArtifacts(ImmutableList.of(treeFile))));
    evaluate();

    FileSystemUtils.writeIsoLatin1(treeFile.getPath(), "other text");
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(treeFile.getExecPath()),
                    /*trustRemoteArtifacts=*/ false))
        .containsExactly(actionKey);
  }

  @Test
  public void getDirtyActionValues_addedTreeFile_returnsTreeKey(
      @TestParameter BatchStatMode batchStatMode, @TestParameter ModifiedSetReporting modifiedSet)
      throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    TreeFileArtifact treeFile = TreeFileArtifact.createTreeOutput(tree, "file1");
    FileSystemUtils.writeIsoLatin1(treeFile.getPath());
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    differencer.inject(
        ImmutableMap.of(actionKey, actionValueWithTreeArtifacts(ImmutableList.of(treeFile))));
    evaluate();

    TreeFileArtifact newFile = TreeFileArtifact.createTreeOutput(tree, "file2");
    FileSystemUtils.writeIsoLatin1(newFile.getPath());
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(newFile.getExecPath()),
                    /*trustRemoteArtifacts=*/ false))
        .containsExactly(actionKey);
  }

  @Test
  public void getDirtyActionValues_addedTreeFileToEmptyTree_returnsTreeKey(
      @TestParameter BatchStatMode batchStatMode, @TestParameter ModifiedSetReporting modifiedSet)
      throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    tree.getPath().createDirectoryAndParents();
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    differencer.inject(
        ImmutableMap.of(actionKey, actionValueWithTreeArtifact(tree, TreeArtifactValue.empty())));
    evaluate();

    TreeFileArtifact newFile = TreeFileArtifact.createTreeOutput(tree, "file");
    FileSystemUtils.writeIsoLatin1(newFile.getPath());
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(newFile.getExecPath()),
                    /*trustRemoteArtifacts=*/ false))
        .containsExactly(actionKey);
  }

  @Test
  public void getDirtyActionValues_deletedTreeFile_returnsTreeKey(
      @TestParameter BatchStatMode batchStatMode, @TestParameter ModifiedSetReporting modifiedSet)
      throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    TreeFileArtifact treeFile = TreeFileArtifact.createTreeOutput(tree, "file");
    FileSystemUtils.writeIsoLatin1(treeFile.getPath());
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    differencer.inject(
        ImmutableMap.of(actionKey, actionValueWithTreeArtifacts(ImmutableList.of(treeFile))));
    evaluate();

    assertThat(treeFile.getPath().delete()).isTrue();
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(treeFile.getExecPath()),
                    /*trustRemoteArtifacts=*/ false))
        .containsExactly(actionKey);
  }

  @Test
  public void getDirtyActionValues_everythingModified_returnsAllKeys(
      @TestParameter BatchStatMode batchStatMode) throws Exception {
    SpecialArtifact tree1 = createTreeArtifact("tree1");
    TreeFileArtifact tree1File = TreeFileArtifact.createTreeOutput(tree1, "file");
    FileSystemUtils.writeIsoLatin1(tree1File.getPath(), "text");
    SpecialArtifact tree2 = createTreeArtifact("tree2");
    TreeFileArtifact tree2File = TreeFileArtifact.createTreeOutput(tree2, "file");
    FileSystemUtils.writeIsoLatin1(tree2File.getPath());
    SkyKey actionKey1 = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    SkyKey actionKey2 = ActionLookupData.create(ACTION_LOOKUP_KEY, 1);
    differencer.inject(
        ImmutableMap.of(
            actionKey1,
            actionValueWithTreeArtifacts(ImmutableList.of(tree1File)),
            actionKey2,
            actionValueWithTreeArtifacts(ImmutableList.of(tree1File))));
    evaluate();

    FileSystemUtils.writeIsoLatin1(tree1File.getPath(), "new text");
    assertThat(tree2File.getPath().delete()).isTrue();
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    /*trustRemoteArtifacts=*/ false))
        .containsExactly(actionKey1, actionKey2);
  }

  @Test
  public void getDirtyActionValues_changedFileNotInModifiedSet_returnsKeysFromSetOnly(
      @TestParameter BatchStatMode batchStatMode, @TestParameter boolean reportFirst)
      throws Exception {
    SpecialArtifact tree1 = createTreeArtifact("tree1");
    TreeFileArtifact tree1File = TreeFileArtifact.createTreeOutput(tree1, "file");
    FileSystemUtils.writeIsoLatin1(tree1File.getPath(), "text");
    SpecialArtifact tree2 = createTreeArtifact("tree2");
    TreeFileArtifact tree2File = TreeFileArtifact.createTreeOutput(tree2, "file");
    FileSystemUtils.writeIsoLatin1(tree2File.getPath());
    SkyKey actionKey1 = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    SkyKey actionKey2 = ActionLookupData.create(ACTION_LOOKUP_KEY, 1);
    differencer.inject(
        ImmutableMap.of(
            actionKey1,
            actionValueWithTreeArtifacts(ImmutableList.of(tree1File)),
            actionKey2,
            actionValueWithTreeArtifacts(ImmutableList.of(tree2File))));
    evaluate();

    FileSystemUtils.writeIsoLatin1(tree1File.getPath(), "new text");
    assertThat(tree2File.getPath().delete()).isTrue();
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    ModifiedFileSet.builder()
                        .modify((reportFirst ? tree1File : tree2File).getExecPath())
                        .build(),
                    /*trustRemoteArtifacts=*/ false))
        .containsExactly(reportFirst ? actionKey1 : actionKey2);
  }

  @Test
  public void getDirtyActionValues_middleFileSkippedInModifiedFileSet_returnsKeysFromSetOnly(
      @TestParameter BatchStatMode batchStatMode) throws Exception {
    SpecialArtifact treeA = createTreeArtifact("a_tree");
    TreeFileArtifact treeAFile = TreeFileArtifact.createTreeOutput(treeA, "file");
    FileSystemUtils.writeIsoLatin1(treeAFile.getPath());
    SpecialArtifact treeB = createTreeArtifact("b_tree");
    TreeFileArtifact treeBFile = TreeFileArtifact.createTreeOutput(treeB, "file");
    FileSystemUtils.writeIsoLatin1(treeBFile.getPath());
    SpecialArtifact treeC = createTreeArtifact("c_tree");
    TreeFileArtifact treeCFile = TreeFileArtifact.createTreeOutput(treeC, "file");
    FileSystemUtils.writeIsoLatin1(treeCFile.getPath());
    SkyKey actionKey1 = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    SkyKey actionKey2 = ActionLookupData.create(ACTION_LOOKUP_KEY, 1);
    SkyKey actionKey3 = ActionLookupData.create(ACTION_LOOKUP_KEY, 2);
    differencer.inject(
        ImmutableMap.of(
            actionKey1,
            actionValueWithTreeArtifacts(ImmutableList.of(treeAFile)),
            actionKey2,
            actionValueWithTreeArtifacts(ImmutableList.of(treeBFile)),
            actionKey3,
            actionValueWithTreeArtifacts(ImmutableList.of(treeCFile))));
    evaluate();

    assertThat(treeAFile.getPath().delete()).isTrue();
    assertThat(treeBFile.getPath().delete()).isTrue();
    assertThat(treeCFile.getPath().delete()).isTrue();
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    ModifiedFileSet.builder()
                        .modify(treeAFile.getExecPath())
                        .modify(treeCFile.getExecPath())
                        .build(),
                    /*trustRemoteArtifacts=*/ false))
        .containsExactly(actionKey1, actionKey3);
  }

  @Test
  public void getDirtyActionValues_nothingModified_returnsEmptyDiff(
      @TestParameter BatchStatMode batchStatMode) throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    TreeFileArtifact treeFile = TreeFileArtifact.createTreeOutput(tree, "file");
    FileSystemUtils.writeIsoLatin1(treeFile.getPath());
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    differencer.inject(
        ImmutableMap.of(actionKey, actionValueWithTreeArtifacts(ImmutableList.of(treeFile))));
    evaluate();

    assertThat(treeFile.getPath().delete()).isTrue();
    assertThat(
            new FilesystemValueChecker(
                    /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatMode.getBatchStat(fs),
                    ModifiedFileSet.NOTHING_MODIFIED,
                    /*trustRemoteArtifacts=*/ false))
        .isEmpty();
  }

  private void evaluate() throws InterruptedException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(1)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    assertThat(driver.evaluate(ImmutableList.of(), evaluationContext).hasError()).isFalse();
  }

  private Artifact createDerivedArtifact(String relPath) throws IOException {
    String outSegment = "bin";
    Path outputPath = fs.getPath("/" + outSegment);
    outputPath.createDirectory();
    return ActionsTestUtil.createArtifact(
        ArtifactRoot.asDerivedRoot(fs.getPath("/"), RootType.Output, outSegment),
        outputPath.getRelative(relPath));
  }

  @Test
  // TODO(b/154337187): Remove the following annotation to re-enable once this test is de-flaked.
  @Ignore
  public void testDirtyActions() throws Exception {
    checkDirtyActions(null);
  }

  @Test
  // TODO(b/154337187): Remove the following annotation to re-enable once this test is de-flaked.
  @Ignore
  public void testDirtyActionsBatchStat() throws Exception {
    checkDirtyActions(
        new BatchStat() {
          @Override
          public List<FileStatusWithDigest> batchStat(
              boolean useDigest, boolean includeLinks, Iterable<PathFragment> paths)
              throws IOException {
            List<FileStatusWithDigest> stats = new ArrayList<>();
            for (PathFragment pathFrag : paths) {
              stats.add(
                  FileStatusWithDigestAdapter.adapt(
                      fs.getPath("/").getRelative(pathFrag).statIfFound(Symlinks.NOFOLLOW)));
            }
            return stats;
          }
        });
  }

  @Test
  // TODO(b/154337187): Remove the following annotation to re-enable once this test is de-flaked.
  @Ignore
  public void testDirtyActionsBatchStatWithDigest() throws Exception {
    checkDirtyActions(
        new BatchStat() {
          @Override
          public List<FileStatusWithDigest> batchStat(
              boolean useDigest, boolean includeLinks, Iterable<PathFragment> paths)
              throws IOException {
            List<FileStatusWithDigest> stats = new ArrayList<>();
            for (PathFragment pathFrag : paths) {
              final Path path = fs.getPath("/").getRelative(pathFrag);
              stats.add(statWithDigest(path, path.statIfFound(Symlinks.NOFOLLOW)));
            }
            return stats;
          }
        });
  }

  @Test
  // TODO(b/154337187): Remove the following annotation to re-enable once this test is de-flaked.
  @Ignore
  public void testDirtyActionsBatchStatFallback() throws Exception {
    checkDirtyActions(
        new BatchStat() {
          @Override
          public List<FileStatusWithDigest> batchStat(
              boolean useDigest, boolean includeLinks, Iterable<PathFragment> paths)
              throws IOException {
            throw new IOException("try again");
          }
        });
  }

  // TODO(bazel-team): Add some tests for FileSystemValueChecker#changedKeys*() methods.
  // Presently these appear to be untested.

  private static ActionExecutionValue actionValue(Action action) {
    Map<Artifact, FileArtifactValue> artifactData = new HashMap<>();
    for (Artifact output : action.getOutputs()) {
      try {
        Path path = output.getPath();
        FileArtifactValue noDigest =
            ActionMetadataHandler.fileArtifactValueFromArtifact(
                output,
                FileStatusWithDigestAdapter.adapt(path.statIfFound(Symlinks.NOFOLLOW)),
                null);
        FileArtifactValue withDigest =
            FileArtifactValue.createFromInjectedDigest(noDigest, path.getDigest());
        artifactData.put(output, withDigest);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return ActionExecutionValue.createForTesting(
        ImmutableMap.copyOf(artifactData),
        /*treeArtifactData=*/ ImmutableMap.of(),
        /*outputSymlinks=*/ null);
  }

  private static ActionExecutionValue actionValueWithTreeArtifact(
      SpecialArtifact output, TreeArtifactValue tree) {
    return ActionExecutionValue.createForTesting(
        ImmutableMap.of(), ImmutableMap.of(output, tree), /*outputSymlinks=*/ null);
  }

  private static ActionExecutionValue actionValueWithRemoteArtifact(
      Artifact output, RemoteFileArtifactValue value) {
    return ActionExecutionValue.createForTesting(
        ImmutableMap.of(output, value), ImmutableMap.of(), /*outputSymlinks=*/ null);
  }

  private RemoteFileArtifactValue createRemoteFileArtifactValue(String contents) {
    byte[] data = contents.getBytes();
    DigestHashFunction hashFn = fs.getDigestFunction();
    HashCode hash = hashFn.getHashFunction().hashBytes(data);
    return new RemoteFileArtifactValue(hash.asBytes(), data.length, -1, "action-id");
  }

  @Test
  public void testRemoteAndLocalArtifacts() throws Exception {
    // Test that injected remote artifacts are trusted by the FileSystemValueChecker
    // if it is configured to trust remote artifacts, and that local files always take precedence
    // over remote files.
    SkyKey actionKey1 = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);
    SkyKey actionKey2 = ActionLookupData.create(ACTION_LOOKUP_KEY, 1);

    Artifact out1 = createDerivedArtifact("foo");
    Artifact out2 = createDerivedArtifact("bar");
    Map<SkyKey, SkyValue> metadataToInject = new HashMap<>();
    metadataToInject.put(
        actionKey1,
        actionValueWithRemoteArtifact(out1, createRemoteFileArtifactValue("foo-content")));
    metadataToInject.put(
        actionKey2,
        actionValueWithRemoteArtifact(out2, createRemoteFileArtifactValue("bar-content")));
    differencer.inject(metadataToInject);

    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(1)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    assertThat(
            driver.evaluate(ImmutableList.of(actionKey1, actionKey2), evaluationContext).hasError())
        .isFalse();
    assertThat(
            new FilesystemValueChecker(
                    /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    /* batchStatter= */ null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    /* trustRemoteArtifacts= */ true))
        .isEmpty();

    // Create the "out1" artifact on the filesystem and test that it invalidates the generating
    // action's SkyKey.
    FileSystemUtils.writeContentAsLatin1(out1.getPath(), "new-foo-content");
    assertThat(
            new FilesystemValueChecker(
                    /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    /* batchStatter= */ null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    /* trustRemoteArtifacts= */ true))
        .containsExactly(actionKey1);
  }

  @Test
  public void testRemoteAndLocalTreeArtifacts() throws Exception {
    // Test that injected remote tree artifacts are trusted by the FileSystemValueChecker
    // and that local files always takes preference over remote files.
    SkyKey actionKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0);

    SpecialArtifact treeArtifact = createTreeArtifact("dir");
    treeArtifact.getPath().createDirectoryAndParents();
    TreeArtifactValue tree =
        TreeArtifactValue.newBuilder(treeArtifact)
            .putChild(
                TreeFileArtifact.createTreeOutput(treeArtifact, "foo"),
                createRemoteFileArtifactValue("foo-content"))
            .putChild(
                TreeFileArtifact.createTreeOutput(treeArtifact, "bar"),
                createRemoteFileArtifactValue("bar-content"))
            .build();

    differencer.inject(ImmutableMap.of(actionKey, actionValueWithTreeArtifact(treeArtifact, tree)));

    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(1)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    assertThat(driver.evaluate(ImmutableList.of(actionKey), evaluationContext).hasError())
        .isFalse();
    assertThat(
            new FilesystemValueChecker(
                    /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    /* batchStatter= */ null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    /* trustRemoteArtifacts= */ false))
        .isEmpty();

    // Create dir/foo on the local disk and test that it invalidates the associated sky key.
    TreeFileArtifact fooArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "foo");
    FileSystemUtils.writeContentAsLatin1(fooArtifact.getPath(), "new-foo-content");
    assertThat(
            new FilesystemValueChecker(
                    /* tsgm= */ null, /* lastExecutionTimeRange= */ null, FSVC_THREADS_FOR_TEST)
                .getDirtyActionValues(
                    evaluator.getValues(),
                    /* batchStatter= */ null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    /* trustRemoteArtifacts= */ false))
        .containsExactly(actionKey);
  }

  @Test
  public void testPropagatesRuntimeExceptions() throws Exception {
    Collection<SkyKey> values =
        ImmutableList.of(
            FileValue.key(
                RootedPath.toRootedPath(Root.fromPath(pkgRoot), PathFragment.create("foo"))));
    driver.evaluate(values, EVALUATION_OPTIONS);
    AtomicReference<Throwable> uncaughtRef = new AtomicReference<>();
    CountDownLatch throwableCaught = new CountDownLatch(1);
    Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
        (t, e) -> {
          uncaughtRef.compareAndSet(null, e);
          throwableCaught.countDown();
        };
    Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
    FilesystemValueChecker checker =
        new FilesystemValueChecker(
            /*tsgm=*/ null, /*lastExecutionTimeRange=*/ null, FSVC_THREADS_FOR_TEST);

    assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker));

    fs.statThrowsRuntimeException = true;
    getDirtyFilesystemKeys(evaluator, checker);
    // Wait for exception handler to trigger (FVC doesn't clean up crashing threads on its own).
    assertThat(throwableCaught.await(TestUtils.WAIT_TIMEOUT_SECONDS, SECONDS)).isTrue();
    Throwable thrown = uncaughtRef.get();
    assertThat(thrown).isNotNull();
    assertThat(thrown).hasMessageThat().isEqualTo("bork");
    assertThat(thrown).isInstanceOf(RuntimeException.class);
  }

  private static void assertEmptyDiff(Diff diff) {
    assertDiffWithNewValues(diff);
  }

  private static void assertDiffWithNewValues(Diff diff, SkyKey... keysWithNewValues) {
    assertThat(diff.changedKeysWithoutNewValues()).isEmpty();
    assertThat(diff.changedKeysWithNewValues().keySet())
        .containsExactlyElementsIn(Arrays.asList(keysWithNewValues));
  }

  private static FileStatusWithDigest statWithDigest(final Path path, final FileStatus stat) {
    return new FileStatusWithDigest() {
      @Nullable
      @Override
      public byte[] getDigest() throws IOException {
        return path.getDigest();
      }

      @Override
      public boolean isFile() {
        return stat.isFile();
      }

      @Override
      public boolean isSpecialFile() {
        return stat.isSpecialFile();
      }

      @Override
      public boolean isDirectory() {
        return stat.isDirectory();
      }

      @Override
      public boolean isSymbolicLink() {
        return stat.isSymbolicLink();
      }

      @Override
      public long getSize() throws IOException {
        return stat.getSize();
      }

      @Override
      public long getLastModifiedTime() throws IOException {
        return stat.getLastModifiedTime();
      }

      @Override
      public long getLastChangeTime() throws IOException {
        return stat.getLastChangeTime();
      }

      @Override
      public long getNodeId() throws IOException {
        return stat.getNodeId();
      }
    };
  }

  private static Diff getDirtyFilesystemKeys(MemoizingEvaluator evaluator,
      FilesystemValueChecker checker) throws InterruptedException {
    return checker.getDirtyKeys(evaluator.getValues(), new BasicFilesystemDirtinessChecker());
  }
}
