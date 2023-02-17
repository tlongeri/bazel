// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.actions.ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS;
import static com.google.devtools.build.lib.remote.util.DigestUtil.toBinaryDigest;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.readContent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.OutputSymlink;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionUploadFinishedEvent;
import com.google.devtools.build.lib.actions.ActionUploadStartedEvent;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.cache.MetadataInjector;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.util.FakeOwner;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.remote.RemoteExecutionService.RemoteAction;
import com.google.devtools.build.lib.remote.RemoteExecutionService.RemoteActionResult;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.CachedActionResult;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.common.RemotePathResolver.DefaultRemotePathResolver;
import com.google.devtools.build.lib.remote.common.RemotePathResolver.SiblingRepositoryLayoutResolver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.FakeSpawnExecutionContext;
import com.google.devtools.build.lib.remote.util.RxNoGlobalErrorsRule;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils.InMemoryOutput;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteExecutionService}. */
@RunWith(JUnit4.class)
public class RemoteExecutionServiceTest {
  @Rule public final RxNoGlobalErrorsRule rxNoGlobalErrorsRule = new RxNoGlobalErrorsRule();

  private final DigestUtil digestUtil = new DigestUtil(DigestHashFunction.SHA256);
  private final Reporter reporter = new Reporter(new EventBus());
  private final StoredEventHandler eventHandler = new StoredEventHandler();

  RemoteOptions remoteOptions;
  private Path execRoot;
  private ArtifactRoot artifactRoot;
  private FakeActionInputFileCache fakeFileCache;
  private RemotePathResolver remotePathResolver;
  private FileOutErr outErr;
  private InMemoryRemoteCache cache;
  private RemoteExecutionClient executor;
  private RemoteActionExecutionContext remoteActionExecutionContext;

  @Before
  public final void setUp() throws Exception {
    reporter.addHandler(eventHandler);

    remoteOptions = Options.getDefaults(RemoteOptions.class);

    FileSystem fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/execroot");
    execRoot.createDirectoryAndParents();
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "outputs");
    checkNotNull(artifactRoot.getRoot().asPath()).createDirectoryAndParents();
    fakeFileCache = new FakeActionInputFileCache(execRoot);

    remotePathResolver = new DefaultRemotePathResolver(execRoot);

    Path stdout = fs.getPath("/tmp/stdout");
    Path stderr = fs.getPath("/tmp/stderr");
    checkNotNull(stdout.getParentDirectory()).createDirectoryAndParents();
    checkNotNull(stderr.getParentDirectory()).createDirectoryAndParents();
    outErr = new FileOutErr(stdout, stderr);

    cache = spy(new InMemoryRemoteCache(remoteOptions, digestUtil));
    executor = mock(RemoteExecutionClient.class);

    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata("none", "none", "action-id", null);
    remoteActionExecutionContext = RemoteActionExecutionContext.create(metadata);
  }

  @Test
  public void buildRemoteAction_differentiateWorkspace_generateActionSalt() throws Exception {
    Spawn spawn =
        new SpawnBuilder("dummy")
            .withExecutionInfo(ExecutionRequirements.DIFFERENTIATE_WORKSPACE_CACHE, "aa")
            .build();
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();

    RemoteAction remoteAction = service.buildRemoteAction(spawn, context);

    Platform expected =
        Platform.newBuilder()
            .addProperties(Platform.Property.newBuilder().setName("workspace").setValue("aa"))
            .build();
    assertThat(remoteAction.getAction().getSalt()).isEqualTo(expected.toByteString());
  }

  @Test
  public void downloadOutputs_outputFiles_executableBitIgnored() throws Exception {
    // Test that executable bit of downloaded output files are ignored since it will be chmod 555
    // after action
    // execution.

    // arrange
    Digest fooDigest = cache.addContents(remoteActionExecutionContext, "foo-contents");
    Digest barDigest = cache.addContents(remoteActionExecutionContext, "bar-contents");
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputFilesBuilder().setPath("outputs/foo").setDigest(fooDigest);
    builder
        .addOutputFilesBuilder()
        .setPath("outputs/bar")
        .setDigest(barDigest)
        .setIsExecutable(true);
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    service.downloadOutputs(action, result);

    // assert
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/foo"))).isEqualTo(fooDigest);
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/bar"))).isEqualTo(barDigest);
    assertThat(execRoot.getRelative("outputs/foo").isExecutable()).isFalse();
    assertThat(execRoot.getRelative("outputs/bar").isExecutable()).isFalse();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_siblingLayoutAndRelativeToInputRoot_works() throws Exception {
    // arrange
    remotePathResolver = new SiblingRepositoryLayoutResolver(execRoot, true);

    Digest fooDigest = cache.addContents(remoteActionExecutionContext, "foo-contents");
    Digest barDigest = cache.addContents(remoteActionExecutionContext, "bar-contents");
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputFilesBuilder().setPath("execroot/outputs/foo").setDigest(fooDigest);
    builder.addOutputFilesBuilder().setPath("execroot/outputs/bar").setDigest(barDigest);
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    service.downloadOutputs(action, result);

    // assert
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/foo"))).isEqualTo(fooDigest);
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/bar"))).isEqualTo(barDigest);
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_outputDirectories_works() throws Exception {
    // Test that downloading an output directory works.

    // arrange
    Digest fooDigest = cache.addContents(remoteActionExecutionContext, "foo-contents");
    Digest quxDigest = cache.addContents(remoteActionExecutionContext, "qux-contents");
    Tree barTreeMessage =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("qux")
                            .setDigest(quxDigest)
                            .setIsExecutable(true)))
            .build();
    Digest barTreeDigest =
        cache.addContents(remoteActionExecutionContext, barTreeMessage.toByteArray());
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputFilesBuilder().setPath("outputs/a/foo").setDigest(fooDigest);
    builder.addOutputDirectoriesBuilder().setPath("outputs/a/bar").setTreeDigest(barTreeDigest);
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    service.downloadOutputs(action, result);

    // assert
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/a/foo"))).isEqualTo(fooDigest);
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/a/bar/qux"))).isEqualTo(quxDigest);
    assertThat(execRoot.getRelative("outputs/a/bar/qux").isExecutable()).isFalse();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_emptyOutputDirectories_works() throws Exception {
    // Test that downloading an empty output directory works.

    // arrange
    Tree barTreeMessage = Tree.newBuilder().setRoot(Directory.getDefaultInstance()).build();
    Digest barTreeDigest =
        cache.addContents(remoteActionExecutionContext, barTreeMessage.toByteArray());
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputDirectoriesBuilder().setPath("outputs/a/bar").setTreeDigest(barTreeDigest);
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    service.downloadOutputs(action, result);

    // assert
    assertThat(execRoot.getRelative("outputs/a/bar").isDirectory()).isTrue();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_nestedOutputDirectories_works() throws Exception {
    // Test that downloading a nested output directory works.

    // arrange
    Digest fooDigest = cache.addContents(remoteActionExecutionContext, "foo-contents");
    Digest quxDigest = cache.addContents(remoteActionExecutionContext, "qux-contents");
    Directory wobbleDirMessage =
        Directory.newBuilder()
            .addFiles(FileNode.newBuilder().setName("qux").setDigest(quxDigest))
            .build();
    Digest wobbleDirDigest =
        cache.addContents(remoteActionExecutionContext, wobbleDirMessage.toByteArray());
    Tree barTreeMessage =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("qux")
                            .setDigest(quxDigest)
                            .setIsExecutable(true))
                    .addDirectories(
                        DirectoryNode.newBuilder().setName("wobble").setDigest(wobbleDirDigest)))
            .addChildren(wobbleDirMessage)
            .build();
    Digest barTreeDigest =
        cache.addContents(remoteActionExecutionContext, barTreeMessage.toByteArray());
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputFilesBuilder().setPath("outputs/a/foo").setDigest(fooDigest);
    builder.addOutputDirectoriesBuilder().setPath("outputs/a/bar").setTreeDigest(barTreeDigest);
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    service.downloadOutputs(action, result);

    // assert
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/a/foo"))).isEqualTo(fooDigest);
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/a/bar/wobble/qux")))
        .isEqualTo(quxDigest);
    assertThat(execRoot.getRelative("outputs/a/bar/wobble/qux").isExecutable()).isFalse();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_outputDirectoriesWithSameHash_works() throws Exception {
    // Test that downloading an output directory works when two Directory
    // protos have the same hash i.e. because they have the same name and contents or are empty.

    /*
     * /bar/foo/file
     * /foo/file
     */

    // arrange
    Digest fileDigest = cache.addContents(remoteActionExecutionContext, "file");
    FileNode file = FileNode.newBuilder().setName("file").setDigest(fileDigest).build();
    Directory fooDir = Directory.newBuilder().addFiles(file).build();
    Digest fooDigest = cache.addContents(remoteActionExecutionContext, fooDir.toByteArray());
    DirectoryNode fooDirNode =
        DirectoryNode.newBuilder().setName("foo").setDigest(fooDigest).build();
    Directory barDir = Directory.newBuilder().addDirectories(fooDirNode).build();
    Digest barDigest = cache.addContents(remoteActionExecutionContext, barDir.toByteArray());
    DirectoryNode barDirNode =
        DirectoryNode.newBuilder().setName("bar").setDigest(barDigest).build();
    Directory rootDir =
        Directory.newBuilder().addDirectories(fooDirNode).addDirectories(barDirNode).build();
    Tree tree =
        Tree.newBuilder()
            .setRoot(rootDir)
            .addChildren(barDir)
            .addChildren(fooDir)
            .addChildren(fooDir)
            .build();
    Digest treeDigest = cache.addContents(remoteActionExecutionContext, tree.toByteArray());
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputDirectoriesBuilder().setPath("outputs/a/").setTreeDigest(treeDigest);
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    service.downloadOutputs(action, result);

    // assert
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/a/bar/foo/file")))
        .isEqualTo(fileDigest);
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/a/foo/file")))
        .isEqualTo(fileDigest);
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_relativeFileSymlink_success() throws Exception {
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputFileSymlinksBuilder().setPath("outputs/a/b/link").setTarget("../../foo");
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // Doesn't check for dangling links, hence download succeeds.
    service.downloadOutputs(action, result);

    Path path = execRoot.getRelative("outputs/a/b/link");
    assertThat(path.isSymbolicLink()).isTrue();
    assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("../../foo"));
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_relativeDirectorySymlink_success() throws Exception {
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputDirectorySymlinksBuilder().setPath("outputs/a/b/link").setTarget("foo");
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // Doesn't check for dangling links, hence download succeeds.
    service.downloadOutputs(action, result);

    Path path = execRoot.getRelative("outputs/a/b/link");
    assertThat(path.isSymbolicLink()).isTrue();
    assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("foo"));
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_relativeSymlinkInDirectory_success() throws Exception {
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("../foo")))
            .build();
    Digest treeDigest = cache.addContents(remoteActionExecutionContext, tree.toByteArray());
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputDirectoriesBuilder().setPath("outputs/dir").setTreeDigest(treeDigest);
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // Doesn't check for dangling links, hence download succeeds.
    service.downloadOutputs(action, result);

    Path path = execRoot.getRelative("outputs/dir/link");
    assertThat(path.isSymbolicLink()).isTrue();
    assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("../foo"));
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_absoluteFileSymlink_error() throws Exception {
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputFileSymlinksBuilder().setPath("outputs/foo").setTarget("/abs/link");
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    IOException expected =
        assertThrows(IOException.class, () -> service.downloadOutputs(action, result));

    assertThat(expected).hasMessageThat().contains("/abs/link");
    assertThat(expected).hasMessageThat().contains("absolute path");
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_absoluteDirectorySymlink_error() throws Exception {
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputDirectorySymlinksBuilder().setPath("outputs/foo").setTarget("/abs/link");
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    IOException expected =
        assertThrows(IOException.class, () -> service.downloadOutputs(action, result));

    assertThat(expected).hasMessageThat().contains("/abs/link");
    assertThat(expected).hasMessageThat().contains("absolute path");
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_absoluteSymlinkInDirectory_error() throws Exception {
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("/foo")))
            .build();
    Digest treeDigest = cache.addContents(remoteActionExecutionContext, tree.toByteArray());
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputDirectoriesBuilder().setPath("outputs/dir").setTreeDigest(treeDigest);
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    IOException expected =
        assertThrows(IOException.class, () -> service.downloadOutputs(action, result));

    assertThat(expected.getSuppressed()).isEmpty();
    assertThat(expected).hasMessageThat().contains("outputs/dir/link");
    assertThat(expected).hasMessageThat().contains("/foo");
    assertThat(expected).hasMessageThat().contains("absolute path");
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_onFailure_maintainDirectories() throws Exception {
    // Test that output directories are not deleted on download failure. See
    // https://github.com/bazelbuild/bazel/issues/6260.
    Tree tree = Tree.newBuilder().setRoot(Directory.getDefaultInstance()).build();
    Digest treeDigest = cache.addContents(remoteActionExecutionContext, tree.toByteArray());
    Digest outputFileDigest =
        cache.addException("outputdir/outputfile", new IOException("download failed"));
    Digest otherFileDigest = cache.addContents(remoteActionExecutionContext, "otherfile");
    ActionResult.Builder builder = ActionResult.newBuilder();
    builder.addOutputDirectoriesBuilder().setPath("outputs/outputdir").setTreeDigest(treeDigest);
    builder.addOutputFiles(
        OutputFile.newBuilder()
            .setPath("outputs/outputdir/outputfile")
            .setDigest(outputFileDigest));
    builder.addOutputFiles(
        OutputFile.newBuilder().setPath("outputs/otherfile").setDigest(otherFileDigest));
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    assertThrows(BulkTransferException.class, () -> service.downloadOutputs(action, result));

    assertThat(cache.getNumFailedDownloads()).isEqualTo(1);
    assertThat(execRoot.getRelative("outputs/outputdir").exists()).isTrue();
    assertThat(execRoot.getRelative("outputs/outputdir/outputfile").exists()).isFalse();
    assertThat(execRoot.getRelative("outputs/otherfile").exists()).isFalse();
    assertThat(context.isLockOutputFilesCalled()).isFalse();
  }

  @Test
  public void downloadOutputs_onError_waitForRemainingDownloadsToComplete() throws Exception {
    // If one or more downloads of output files / directories fail then the code should
    // wait for all downloads to have been completed before it tries to clean up partially
    // downloaded files.
    Digest digest1 = cache.addContents(remoteActionExecutionContext, "file1");
    Digest digest2 = cache.addException("file2", new IOException("download failed"));
    Digest digest3 = cache.addContents(remoteActionExecutionContext, "file3");
    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(digest1))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(digest2))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file3").setDigest(digest3))
            .build();
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    BulkTransferException downloadException =
        assertThrows(BulkTransferException.class, () -> service.downloadOutputs(action, result));

    assertThat(downloadException.getSuppressed()).hasLength(1);
    assertThat(cache.getNumSuccessfulDownloads()).isEqualTo(2);
    assertThat(cache.getNumFailedDownloads()).isEqualTo(1);
    assertThat(downloadException.getSuppressed()[0]).isInstanceOf(IOException.class);
    IOException e = (IOException) downloadException.getSuppressed()[0];
    assertThat(Throwables.getRootCause(e)).hasMessageThat().isEqualTo("download failed");
    assertThat(context.isLockOutputFilesCalled()).isFalse();
  }

  @Test
  public void downloadOutputs_withMultipleErrors_addsThemAsSuppressed() throws Exception {
    Digest digest1 = cache.addContents(remoteActionExecutionContext, "file1");
    Digest digest2 = cache.addException("file2", new IOException("file2 failed"));
    Digest digest3 = cache.addException("file3", new IOException("file3 failed"));
    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(digest1))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(digest2))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file3").setDigest(digest3))
            .build();
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    BulkTransferException e =
        assertThrows(BulkTransferException.class, () -> service.downloadOutputs(action, result));

    assertThat(e.getSuppressed()).hasLength(2);
    assertThat(e.getSuppressed()[0]).isInstanceOf(IOException.class);
    assertThat(e.getSuppressed()[0]).hasMessageThat().isAnyOf("file2 failed", "file3 failed");
    assertThat(e.getSuppressed()[1]).isInstanceOf(IOException.class);
    assertThat(e.getSuppressed()[1]).hasMessageThat().isAnyOf("file2 failed", "file3 failed");
  }

  @Test
  public void downloadOutputs_withDuplicateIOErrors_doesNotSuppress() throws Exception {
    Digest digest1 = cache.addContents(remoteActionExecutionContext, "file1");
    IOException reusedException = new IOException("reused io exception");
    Digest digest2 = cache.addException("file2", reusedException);
    Digest digest3 = cache.addException("file3", reusedException);
    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(digest1))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(digest2))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file3").setDigest(digest3))
            .build();
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    BulkTransferException downloadException =
        assertThrows(BulkTransferException.class, () -> service.downloadOutputs(action, result));

    for (Throwable t : downloadException.getSuppressed()) {
      assertThat(t).isInstanceOf(IOException.class);
      IOException e = (IOException) t;
      assertThat(Throwables.getRootCause(e)).hasMessageThat().isEqualTo("reused io exception");
    }
  }

  @Test
  public void downloadOutputs_withDuplicateInterruptions_doesNotSuppress() throws Exception {
    Digest digest1 = cache.addContents(remoteActionExecutionContext, "file1");
    InterruptedException reusedInterruption = new InterruptedException("reused interruption");
    Digest digest2 = cache.addException("file2", reusedInterruption);
    Digest digest3 = cache.addException("file3", reusedInterruption);
    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(digest1))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(digest2))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file3").setDigest(digest3))
            .build();
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    InterruptedException e =
        assertThrows(InterruptedException.class, () -> service.downloadOutputs(action, result));

    assertThat(e.getSuppressed()).isEmpty();
    assertThat(Throwables.getRootCause(e)).hasMessageThat().isEqualTo("reused interruption");
  }

  @Test
  public void downloadOutputs_withStdoutStderrOnSuccess_writable() throws Exception {
    // Tests that fetching stdout/stderr as a digest works and that OutErr is still
    // writable afterwards.
    FileOutErr childOutErr = outErr.childOutErr();
    FileOutErr spyOutErr = spy(outErr);
    FileOutErr spyChildOutErr = spy(childOutErr);
    when(spyOutErr.childOutErr()).thenReturn(spyChildOutErr);
    Digest digestStdout = cache.addContents(remoteActionExecutionContext, "stdout");
    Digest digestStderr = cache.addContents(remoteActionExecutionContext, "stderr");
    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .setStdoutDigest(digestStdout)
            .setStderrDigest(digestStderr)
            .build();
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, spyOutErr);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    service.downloadOutputs(action, result);

    verify(spyOutErr, times(2)).childOutErr();
    verify(spyChildOutErr).clearOut();
    verify(spyChildOutErr).clearErr();
    assertThat(outErr.getOutputPath().exists()).isTrue();
    assertThat(outErr.getErrorPath().exists()).isTrue();
    try {
      outErr.getOutputStream().write(0);
      outErr.getErrorStream().write(0);
    } catch (IOException err) {
      throw new AssertionError("outErr should still be writable after download finished.", err);
    }
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_withStdoutStderrOnFailure_writableAndEmpty() throws Exception {
    // Test that when downloading stdout/stderr fails the OutErr is still writable
    // and empty.
    FileOutErr childOutErr = outErr.childOutErr();
    FileOutErr spyOutErr = spy(outErr);
    FileOutErr spyChildOutErr = spy(childOutErr);
    when(spyOutErr.childOutErr()).thenReturn(spyChildOutErr);
    // Don't add stdout/stderr as a known blob to the remote cache so that downloading it will fail
    Digest digestStdout = digestUtil.computeAsUtf8("stdout");
    Digest digestStderr = digestUtil.computeAsUtf8("stderr");
    ActionResult actionResult =
        ActionResult.newBuilder()
            .setExitCode(0)
            .setStdoutDigest(digestStdout)
            .setStderrDigest(digestStderr)
            .build();
    RemoteActionResult result =
        RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, spyOutErr);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    assertThrows(BulkTransferException.class, () -> service.downloadOutputs(action, result));

    verify(spyOutErr, times(2)).childOutErr();
    verify(spyChildOutErr).clearOut();
    verify(spyChildOutErr).clearErr();
    assertThat(outErr.getOutputPath().exists()).isFalse();
    assertThat(outErr.getErrorPath().exists()).isFalse();
    try {
      outErr.getOutputStream().write(0);
      outErr.getErrorStream().write(0);
    } catch (IOException err) {
      throw new AssertionError("outErr should still be writable after download failed.", err);
    }
    assertThat(context.isLockOutputFilesCalled()).isFalse();
  }

  @Test
  public void downloadOutputs_outputNameClashesWithTempName_success() throws Exception {
    Digest d1 = cache.addContents(remoteActionExecutionContext, "content1");
    Digest d2 = cache.addContents(remoteActionExecutionContext, "content2");
    ActionResult r =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/foo.tmp").setDigest(d1))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/foo").setDigest(d2))
            .build();
    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    Spawn spawn = newSpawnFromResult(result);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteExecutionService service = newRemoteExecutionService();
    RemoteAction action = service.buildRemoteAction(spawn, context);

    service.downloadOutputs(action, result);

    assertThat(readContent(execRoot.getRelative("outputs/foo.tmp"), StandardCharsets.UTF_8))
        .isEqualTo("content1");
    assertThat(readContent(execRoot.getRelative("outputs/foo"), StandardCharsets.UTF_8))
        .isEqualTo("content2");
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_outputFilesWithTopLevel_download() throws Exception {
    // arrange
    Digest d1 = cache.addContents(remoteActionExecutionContext, "content1");
    ActionResult r =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(d1))
            .build();

    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    Spawn spawn = newSpawnFromResult(result);
    MetadataInjector injector = mock(MetadataInjector.class);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, injector);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.TOPLEVEL;
    RemoteExecutionService service =
        newRemoteExecutionService(remoteOptions, spawn.getOutputFiles());
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    service.downloadOutputs(action, result);

    // assert
    verify(injector, never()).injectFile(any(), any());
    assertThat(digestUtil.compute(execRoot.getRelative("outputs/file1"))).isEqualTo(d1);
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_outputFilesWithoutTopLevel_inject() throws Exception {
    // arrange
    Digest d1 = cache.addContents(remoteActionExecutionContext, "content1");
    ActionResult r =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(d1))
            .build();

    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    Spawn spawn = newSpawnFromResult(result);
    MetadataInjector injector = mock(MetadataInjector.class);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, injector);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.TOPLEVEL;
    RemoteExecutionService service = newRemoteExecutionService(remoteOptions);
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    InMemoryOutput inMemoryOutput = service.downloadOutputs(action, result);

    // assert
    assertThat(inMemoryOutput).isNull();
    Artifact a1 = ActionsTestUtil.createArtifact(artifactRoot, "file1");
    verify(injector).injectFile(eq(a1), remoteFileMatchingDigest(d1));
    Path outputBase = checkNotNull(artifactRoot.getRoot().asPath());
    assertThat(outputBase.readdir(Symlinks.NOFOLLOW)).isEmpty();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_outputFilesWithMinimal_injectingMetadata() throws Exception {
    // Test that injecting the metadata for a remote output file works

    // arrange
    Digest d1 = cache.addContents(remoteActionExecutionContext, "content1");
    Digest d2 = cache.addContents(remoteActionExecutionContext, "content2");
    ActionResult r =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(d1))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(d2))
            .build();

    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    Spawn spawn = newSpawnFromResult(result);
    MetadataInjector injector = mock(MetadataInjector.class);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, injector);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;
    RemoteExecutionService service = newRemoteExecutionService(remoteOptions);
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    InMemoryOutput inMemoryOutput = service.downloadOutputs(action, result);

    // assert
    assertThat(inMemoryOutput).isNull();
    Artifact a1 = ActionsTestUtil.createArtifact(artifactRoot, "file1");
    Artifact a2 = ActionsTestUtil.createArtifact(artifactRoot, "file2");
    verify(injector).injectFile(eq(a1), remoteFileMatchingDigest(d1));
    verify(injector).injectFile(eq(a2), remoteFileMatchingDigest(d2));
    Path outputBase = checkNotNull(artifactRoot.getRoot().asPath());
    assertThat(outputBase.readdir(Symlinks.NOFOLLOW)).isEmpty();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_outputDirectoriesWithMinimal_injectingMetadata() throws Exception {
    // Test that injecting the metadata for a tree artifact / remote output directory works

    // arrange

    // Output Directory:
    // dir/file1
    // dir/a/file2
    Digest d1 = cache.addContents(remoteActionExecutionContext, "content1");
    Digest d2 = cache.addContents(remoteActionExecutionContext, "content2");
    FileNode file1 = FileNode.newBuilder().setName("file1").setDigest(d1).build();
    FileNode file2 = FileNode.newBuilder().setName("file2").setDigest(d2).build();
    Directory a = Directory.newBuilder().addFiles(file2).build();
    Digest da = cache.addContents(remoteActionExecutionContext, a);
    Directory root =
        Directory.newBuilder()
            .addFiles(file1)
            .addDirectories(DirectoryNode.newBuilder().setName("a").setDigest(da))
            .build();
    Tree t = Tree.newBuilder().setRoot(root).addChildren(a).build();
    Digest dt = cache.addContents(remoteActionExecutionContext, t);
    ActionResult r =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputDirectories(
                OutputDirectory.newBuilder().setPath("outputs/dir").setTreeDigest(dt))
            .build();

    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    Spawn spawn = newSpawnFromResult(result);
    MetadataInjector injector = mock(MetadataInjector.class);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, injector);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;
    RemoteExecutionService service = newRemoteExecutionService(remoteOptions);
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    InMemoryOutput inMemoryOutput = service.downloadOutputs(action, result);

    // assert
    assertThat(inMemoryOutput).isNull();
    SpecialArtifact dir =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            artifactRoot, PathFragment.create("outputs/dir"));
    TreeArtifactValue tree =
        TreeArtifactValue.newBuilder(dir)
            .putChild(
                TreeFileArtifact.createTreeOutput(dir, "file1"),
                new RemoteFileArtifactValue(
                    toBinaryDigest(d1), d1.getSizeBytes(), 1, action.getActionId()))
            .putChild(
                TreeFileArtifact.createTreeOutput(dir, "a/file2"),
                new RemoteFileArtifactValue(
                    toBinaryDigest(d2), d2.getSizeBytes(), 1, action.getActionId()))
            .build();
    verify(injector).injectTree(dir, tree);
    Path outputBase = checkNotNull(artifactRoot.getRoot().asPath());
    assertThat(outputBase.readdir(Symlinks.NOFOLLOW)).isEmpty();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_outputDirectoriesWithMinimalOnFailure_failProperly()
      throws Exception {
    // Test that we properly fail when downloading the metadata of an output
    // directory fails

    // arrange

    // Output Directory:
    // dir/file1
    // dir/a/file2
    Digest d1 = cache.addContents(remoteActionExecutionContext, "content1");
    Digest d2 = cache.addContents(remoteActionExecutionContext, "content2");
    FileNode file1 = FileNode.newBuilder().setName("file1").setDigest(d1).build();
    FileNode file2 = FileNode.newBuilder().setName("file2").setDigest(d2).build();
    Directory a = Directory.newBuilder().addFiles(file2).build();
    Digest da = cache.addContents(remoteActionExecutionContext, a);
    Directory root =
        Directory.newBuilder()
            .addFiles(file1)
            .addDirectories(DirectoryNode.newBuilder().setName("a").setDigest(da))
            .build();
    Tree t = Tree.newBuilder().setRoot(root).addChildren(a).build();
    // Downloading the tree will fail
    IOException downloadTreeException = new IOException("entry not found");
    Digest dt = cache.addException(t, downloadTreeException);
    ActionResult r =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputDirectories(
                OutputDirectory.newBuilder().setPath("outputs/dir").setTreeDigest(dt))
            .build();

    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    Spawn spawn = newSpawnFromResult(result);
    MetadataInjector injector = mock(MetadataInjector.class);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, injector);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;
    RemoteExecutionService service = newRemoteExecutionService(remoteOptions);
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    BulkTransferException e =
        assertThrows(BulkTransferException.class, () -> service.downloadOutputs(action, result));

    // assert
    assertThat(e.getSuppressed()).hasLength(1);
    assertThat(e.getSuppressed()[0]).isEqualTo(downloadTreeException);
    assertThat(context.isLockOutputFilesCalled()).isFalse();
  }

  @Test
  public void downloadOutputs_stdoutAndStdErrWithMinimal_works() throws Exception {
    // Test that downloading of non-embedded stdout and stderr works

    // arrange
    Digest dOut = cache.addContents(remoteActionExecutionContext, "stdout");
    Digest dErr = cache.addContents(remoteActionExecutionContext, "stderr");
    ActionResult r =
        ActionResult.newBuilder()
            .setExitCode(0)
            .setStdoutDigest(dOut)
            .setStderrDigest(dErr)
            .build();

    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    Spawn spawn = newSpawnFromResult(result);
    MetadataInjector injector = mock(MetadataInjector.class);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, injector);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;
    RemoteExecutionService service = newRemoteExecutionService(remoteOptions);
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    InMemoryOutput inMemoryOutput = service.downloadOutputs(action, result);

    // assert
    assertThat(inMemoryOutput).isNull();
    assertThat(outErr.outAsLatin1()).isEqualTo("stdout");
    assertThat(outErr.errAsLatin1()).isEqualTo("stderr");
    Path outputBase = checkNotNull(artifactRoot.getRoot().asPath());
    assertThat(outputBase.readdir(Symlinks.NOFOLLOW)).isEmpty();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_inMemoryOutputWithMinimal_downloadIt() throws Exception {
    // Test that downloading an in memory output works

    // arrange
    Digest d1 = cache.addContents(remoteActionExecutionContext, "content1");
    Digest d2 = cache.addContents(remoteActionExecutionContext, "content2");
    ActionResult r =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(d1))
            .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(d2))
            .build();

    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    // a1 should be provided as an InMemoryOutput
    PathFragment inMemoryOutputPathFragment = PathFragment.create("outputs/file1");
    Spawn spawn = newSpawnFromResultWithInMemoryOutput(result, inMemoryOutputPathFragment);
    MetadataInjector injector = mock(MetadataInjector.class);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, injector);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;
    RemoteExecutionService service = newRemoteExecutionService(remoteOptions);
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    InMemoryOutput inMemoryOutput = service.downloadOutputs(action, result);

    // assert
    assertThat(inMemoryOutput).isNotNull();
    ByteString expectedContents = ByteString.copyFrom("content1", UTF_8);
    assertThat(inMemoryOutput.getContents()).isEqualTo(expectedContents);
    Artifact a1 = ActionsTestUtil.createArtifact(artifactRoot, "file1");
    Artifact a2 = ActionsTestUtil.createArtifact(artifactRoot, "file2");
    assertThat(inMemoryOutput.getOutput()).isEqualTo(a1);
    // The in memory file also needs to be injected as an output
    verify(injector).injectFile(eq(a1), remoteFileMatchingDigest(d1));
    verify(injector).injectFile(eq(a2), remoteFileMatchingDigest(d2));
    Path outputBase = checkNotNull(artifactRoot.getRoot().asPath());
    assertThat(outputBase.readdir(Symlinks.NOFOLLOW)).isEmpty();
    assertThat(context.isLockOutputFilesCalled()).isTrue();
  }

  @Test
  public void downloadOutputs_missingInMemoryOutputWithMinimal_returnsNull() throws Exception {
    // Test that downloadOutputs returns null if a declared in-memory output is missing from action
    // result.

    // arrange
    Digest d1 = cache.addContents(remoteActionExecutionContext, "in-memory output");
    ActionResult r = ActionResult.newBuilder().setExitCode(0).build();
    RemoteActionResult result = RemoteActionResult.createFromCache(CachedActionResult.remote(r));
    Artifact a1 = ActionsTestUtil.createArtifact(artifactRoot, "file1");
    Spawn spawn =
        newSpawn(
            ImmutableMap.of(REMOTE_EXECUTION_INLINE_OUTPUTS, "outputs/file1"), ImmutableSet.of(a1));
    MetadataInjector injector = mock(MetadataInjector.class);
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn, injector);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;
    RemoteExecutionService service = newRemoteExecutionService(remoteOptions);
    RemoteAction action = service.buildRemoteAction(spawn, context);

    // act
    InMemoryOutput inMemoryOutput = service.downloadOutputs(action, result);

    // assert
    assertThat(inMemoryOutput).isNull();
    // The in memory file metadata also should not have been injected.
    verify(injector, never()).injectFile(eq(a1), remoteFileMatchingDigest(d1));
  }

  @Test
  public void uploadOutputs_uploadDirectory_works() throws Exception {
    // Test that uploading a directory works.

    // arrange
    Digest fooDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/a/foo"), "xyz");
    Digest quxDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/bar/qux"), "abc");
    Digest barDigest =
        fakeFileCache.createScratchInputDirectory(
            ActionInputHelper.fromPath("outputs/bar"),
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setIsExecutable(true)
                                .setName("qux")
                                .setDigest(quxDigest)
                                .build())
                        .build())
                .build());
    Path fooFile = execRoot.getRelative("outputs/a/foo");
    Path quxFile = execRoot.getRelative("outputs/bar/qux");
    quxFile.setExecutable(true);
    Path barDir = execRoot.getRelative("outputs/bar");
    Artifact outputFile = ActionsTestUtil.createArtifact(artifactRoot, fooFile);
    Artifact outputDirectory =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            artifactRoot, barDir.relativeTo(execRoot));
    RemoteExecutionService service = newRemoteExecutionService();
    Spawn spawn = newSpawn(ImmutableMap.of(), ImmutableSet.of(outputFile, outputDirectory));
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteAction action = service.buildRemoteAction(spawn, context);
    SpawnResult spawnResult =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(SpawnResult.Status.SUCCESS)
            .setRunnerName("test")
            .build();

    // act
    UploadManifest manifest = service.buildUploadManifest(action, spawnResult);
    service.uploadOutputs(action, spawnResult);

    // assert
    ActionResult.Builder expectedResult = ActionResult.newBuilder();
    expectedResult
        .addOutputFilesBuilder()
        .setPath("outputs/a/foo")
        .setDigest(fooDigest)
        .setIsExecutable(true);
    expectedResult.addOutputDirectoriesBuilder().setPath("outputs/bar").setTreeDigest(barDigest);
    assertThat(manifest.getActionResult()).isEqualTo(expectedResult.build());

    ImmutableList<Digest> toQuery = ImmutableList.of(fooDigest, quxDigest, barDigest);
    assertThat(getFromFuture(cache.findMissingDigests(remoteActionExecutionContext, toQuery)))
        .isEmpty();
  }

  @Test
  public void uploadOutputs_uploadEmptyDirectory_works() throws Exception {
    // Test that uploading an empty directory works.

    // arrange
    Digest barDigest =
        fakeFileCache.createScratchInputDirectory(
            ActionInputHelper.fromPath("outputs/bar"),
            Tree.newBuilder().setRoot(Directory.getDefaultInstance()).build());
    Path barDir = execRoot.getRelative("outputs/bar");
    Artifact outputDirectory =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            artifactRoot, barDir.relativeTo(execRoot));
    RemoteExecutionService service = newRemoteExecutionService();
    Spawn spawn = newSpawn(ImmutableMap.of(), ImmutableSet.of(outputDirectory));
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteAction action = service.buildRemoteAction(spawn, context);
    SpawnResult spawnResult =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(SpawnResult.Status.SUCCESS)
            .setRunnerName("test")
            .build();

    // act
    UploadManifest manifest = service.buildUploadManifest(action, spawnResult);
    service.uploadOutputs(action, spawnResult);

    // assert
    ActionResult.Builder expectedResult = ActionResult.newBuilder();
    expectedResult.addOutputDirectoriesBuilder().setPath("outputs/bar").setTreeDigest(barDigest);
    assertThat(manifest.getActionResult()).isEqualTo(expectedResult.build());
    assertThat(
            getFromFuture(
                cache.findMissingDigests(
                    remoteActionExecutionContext, ImmutableList.of(barDigest))))
        .isEmpty();
  }

  @Test
  public void uploadOutputs_uploadNestedDirectory_works() throws Exception {
    // Test that uploading a nested directory works.

    // arrange
    final Digest wobbleDigest =
        fakeFileCache.createScratchInput(
            ActionInputHelper.fromPath("outputs/bar/test/wobble"), "xyz");
    final Digest quxDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/bar/qux"), "abc");
    final Directory testDirMessage =
        Directory.newBuilder()
            .addFiles(FileNode.newBuilder().setName("wobble").setDigest(wobbleDigest).build())
            .build();
    final Digest testDigest = digestUtil.compute(testDirMessage);
    final Tree barTree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setIsExecutable(true)
                            .setName("qux")
                            .setDigest(quxDigest))
                    .addDirectories(
                        DirectoryNode.newBuilder().setName("test").setDigest(testDigest)))
            .addChildren(testDirMessage)
            .build();
    final Digest barDigest =
        fakeFileCache.createScratchInputDirectory(
            ActionInputHelper.fromPath("outputs/bar"), barTree);

    final Path quxFile = execRoot.getRelative("outputs/bar/qux");
    quxFile.setExecutable(true);
    final Path barDir = execRoot.getRelative("outputs/bar");

    Artifact outputDirectory =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            artifactRoot, barDir.relativeTo(execRoot));
    RemoteExecutionService service = newRemoteExecutionService();
    Spawn spawn = newSpawn(ImmutableMap.of(), ImmutableSet.of(outputDirectory));
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteAction action = service.buildRemoteAction(spawn, context);
    SpawnResult spawnResult =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(SpawnResult.Status.SUCCESS)
            .setRunnerName("test")
            .build();

    // act
    UploadManifest manifest = service.buildUploadManifest(action, spawnResult);
    service.uploadOutputs(action, spawnResult);

    // assert
    ActionResult.Builder expectedResult = ActionResult.newBuilder();
    expectedResult.addOutputDirectoriesBuilder().setPath("outputs/bar").setTreeDigest(barDigest);
    assertThat(manifest.getActionResult()).isEqualTo(expectedResult.build());

    ImmutableList<Digest> toQuery = ImmutableList.of(wobbleDigest, quxDigest, barDigest);
    assertThat(getFromFuture(cache.findMissingDigests(remoteActionExecutionContext, toQuery)))
        .isEmpty();
  }

  @Test
  public void uploadOutputs_emptyOutputs_doNotPerformUpload() throws Exception {
    // Test that uploading an empty output does not try to perform an upload.

    // arrange
    Digest emptyDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/bar/test/wobble"), "");
    Path file = execRoot.getRelative("outputs/bar/test/wobble");
    Artifact outputFile = ActionsTestUtil.createArtifact(artifactRoot, file);
    RemoteExecutionService service = newRemoteExecutionService();
    Spawn spawn = newSpawn(ImmutableMap.of(), ImmutableSet.of(outputFile));
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteAction action = service.buildRemoteAction(spawn, context);
    SpawnResult spawnResult =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(SpawnResult.Status.SUCCESS)
            .setRunnerName("test")
            .build();

    // act
    service.uploadOutputs(action, spawnResult);

    // assert
    assertThat(
            getFromFuture(
                cache.findMissingDigests(
                    remoteActionExecutionContext, ImmutableSet.of(emptyDigest))))
        .containsExactly(emptyDigest);
  }

  @Test
  public void uploadOutputs_uploadFails_printWarning() throws Exception {
    RemoteExecutionService service = newRemoteExecutionService();
    Spawn spawn = newSpawn(ImmutableMap.of(), ImmutableSet.of());
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteAction action = service.buildRemoteAction(spawn, context);
    SpawnResult spawnResult =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(Status.SUCCESS)
            .setRunnerName("test")
            .build();
    doReturn(Futures.immediateFailedFuture(new IOException("cache down")))
        .when(cache)
        .uploadActionResult(any(), any(), any());

    service.uploadOutputs(action, spawnResult);

    assertThat(eventHandler.getEvents()).hasSize(1);
    Event evt = eventHandler.getEvents().get(0);
    assertThat(evt.getKind()).isEqualTo(EventKind.WARNING);
    assertThat(evt.getMessage()).contains("cache down");
  }

  @Test
  public void uploadOutputs_firesUploadEvents() throws Exception {
    Digest digest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/file"), "content");
    Path file = execRoot.getRelative("outputs/file");
    Artifact outputFile = ActionsTestUtil.createArtifact(artifactRoot, file);
    RemoteExecutionService service = newRemoteExecutionService();
    Spawn spawn = newSpawn(ImmutableMap.of(), ImmutableSet.of(outputFile));
    FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
    RemoteAction action = service.buildRemoteAction(spawn, context);
    SpawnResult spawnResult =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(SpawnResult.Status.SUCCESS)
            .setRunnerName("test")
            .build();

    service.uploadOutputs(action, spawnResult);

    assertThat(eventHandler.getPosts())
        .containsAtLeast(
            ActionUploadStartedEvent.create(spawn.getResourceOwner(), "cas/" + digest.getHash()),
            ActionUploadFinishedEvent.create(spawn.getResourceOwner(), "cas/" + digest.getHash()),
            ActionUploadStartedEvent.create(spawn.getResourceOwner(), "ac/" + action.getActionId()),
            ActionUploadFinishedEvent.create(
                spawn.getResourceOwner(), "ac/" + action.getActionId()));
  }

  @Test
  public void uploadInputsIfNotPresent_deduplicateFindMissingBlobCalls() throws Exception {
    int taskCount = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(taskCount);
    AtomicReference<Throwable> error = new AtomicReference<>(null);
    Semaphore semaphore = new Semaphore(0);
    ActionInput input = ActionInputHelper.fromPath("inputs/foo");
    Digest inputDigest = fakeFileCache.createScratchInput(input, "input-foo");
    RemoteExecutionService service = newRemoteExecutionService();

    for (int i = 0; i < taskCount; ++i) {
      executorService.execute(
          () -> {
            try {
              Spawn spawn =
                  newSpawn(
                      ImmutableMap.of(),
                      ImmutableSet.of(),
                      NestedSetBuilder.create(Order.STABLE_ORDER, input));
              FakeSpawnExecutionContext context = newSpawnExecutionContext(spawn);
              RemoteAction action = service.buildRemoteAction(spawn, context);

              service.uploadInputsIfNotPresent(action, /*force=*/ false);
            } catch (Throwable e) {
              if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
              }
              error.set(e);
            } finally {
              semaphore.release();
            }
          });
    }
    semaphore.acquire(taskCount);

    assertThat(error.get()).isNull();
    assertThat(cache.getNumFindMissingDigests()).containsEntry(inputDigest, 1);
    for (Integer num : cache.getNumFindMissingDigests().values()) {
      assertThat(num).isEqualTo(1);
    }
  }

  @Test
  public void buildMerkleTree_withMemoization_works() throws Exception {
    // Test that Merkle tree building can be memoized.

    // TODO: Would like to check that NestedSet.getNonLeaves() is only called once per node, but
    //       cannot Mockito.spy on NestedSet as it is final.

    // arrange
    /*
     * First:
     *   /bar/file
     *   /foo1/file
     * Second:
     *   /bar/file
     *   /foo2/file
     */

    // arrange
    // Single node NestedSets are folded, so always add a dummy file everywhere.
    ActionInput dummyFile = ActionInputHelper.fromPath("dummy");
    fakeFileCache.createScratchInput(dummyFile, "dummy");

    ActionInput barFile = ActionInputHelper.fromPath("bar/file");
    NestedSet<ActionInput> nodeBar =
        NestedSetBuilder.create(Order.STABLE_ORDER, dummyFile, barFile);
    fakeFileCache.createScratchInput(barFile, "bar");

    ActionInput foo1File = ActionInputHelper.fromPath("foo1/file");
    NestedSet<ActionInput> nodeFoo1 =
        NestedSetBuilder.create(Order.STABLE_ORDER, dummyFile, foo1File);
    fakeFileCache.createScratchInput(foo1File, "foo1");

    ActionInput foo2File = ActionInputHelper.fromPath("foo2/file");
    NestedSet<ActionInput> nodeFoo2 =
        NestedSetBuilder.create(Order.STABLE_ORDER, dummyFile, foo2File);
    fakeFileCache.createScratchInput(foo2File, "foo2");

    NestedSet<ActionInput> nodeRoot1 =
        new NestedSetBuilder<ActionInput>(Order.STABLE_ORDER)
            .add(dummyFile)
            .addTransitive(nodeBar)
            .addTransitive(nodeFoo1)
            .build();
    NestedSet<ActionInput> nodeRoot2 =
        new NestedSetBuilder<ActionInput>(Order.STABLE_ORDER)
            .add(dummyFile)
            .addTransitive(nodeBar)
            .addTransitive(nodeFoo2)
            .build();

    Spawn spawn1 =
        new SimpleSpawn(
            new FakeOwner("foo", "bar", "//dummy:label"),
            /*arguments=*/ ImmutableList.of(),
            /*environment=*/ ImmutableMap.of(),
            /*executionInfo=*/ ImmutableMap.of(),
            /*inputs=*/ nodeRoot1,
            /*outputs=*/ ImmutableSet.of(),
            ResourceSet.ZERO);
    Spawn spawn2 =
        new SimpleSpawn(
            new FakeOwner("foo", "bar", "//dummy:label"),
            /*arguments=*/ ImmutableList.of(),
            /*environment=*/ ImmutableMap.of(),
            /*executionInfo=*/ ImmutableMap.of(),
            /*inputs=*/ nodeRoot2,
            /*outputs=*/ ImmutableSet.of(),
            ResourceSet.ZERO);

    FakeSpawnExecutionContext context1 = newSpawnExecutionContext(spawn1);
    FakeSpawnExecutionContext context2 = newSpawnExecutionContext(spawn2);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteMerkleTreeCache = true;
    remoteOptions.remoteMerkleTreeCacheSize = 0;
    RemoteExecutionService service = spy(newRemoteExecutionService(remoteOptions));

    // act first time
    service.buildRemoteAction(spawn1, context1);

    // assert first time
    // Called for: manifests, runfiles, nodeRoot1, nodeFoo1 and nodeBar.
    verify(service, times(5)).uncachedBuildMerkleTreeVisitor(any(), any());

    // act second time
    service.buildRemoteAction(spawn2, context2);

    // assert second time
    // Called again for: manifests, runfiles, nodeRoot2 and nodeFoo2 but not nodeBar (cached).
    verify(service, times(5 + 4)).uncachedBuildMerkleTreeVisitor(any(), any());
  }

  private Spawn newSpawnFromResult(RemoteActionResult result) {
    return newSpawnFromResult(ImmutableMap.of(), result);
  }

  private Spawn newSpawnFromResult(
      ImmutableMap<String, String> executionInfo, RemoteActionResult result) {
    ImmutableSet.Builder<Artifact> outputs = ImmutableSet.builder();
    for (OutputFile file : result.getOutputFiles()) {
      Path path = remotePathResolver.outputPathToLocalPath(file.getPath());
      Artifact output = ActionsTestUtil.createArtifact(artifactRoot, path);
      outputs.add(output);
    }

    for (OutputDirectory directory : result.getOutputDirectories()) {
      Path path = remotePathResolver.outputPathToLocalPath(directory.getPath());
      Artifact output =
          ActionsTestUtil.createTreeArtifactWithGeneratingAction(
              artifactRoot, path.relativeTo(execRoot));
      outputs.add(output);
    }

    for (OutputSymlink fileSymlink : result.getOutputFileSymlinks()) {
      Path path = remotePathResolver.outputPathToLocalPath(fileSymlink.getPath());
      Artifact output = ActionsTestUtil.createArtifact(artifactRoot, path);
      outputs.add(output);
    }

    for (OutputSymlink directorySymlink : result.getOutputDirectorySymlinks()) {
      Path path = remotePathResolver.outputPathToLocalPath(directorySymlink.getPath());
      Artifact output =
          ActionsTestUtil.createTreeArtifactWithGeneratingAction(
              artifactRoot, path.relativeTo(execRoot));
      outputs.add(output);
    }

    return newSpawn(executionInfo, outputs.build());
  }

  private Spawn newSpawnFromResultWithInMemoryOutput(
      RemoteActionResult result, PathFragment inMemoryOutput) {
    return newSpawnFromResult(
        ImmutableMap.of(REMOTE_EXECUTION_INLINE_OUTPUTS, inMemoryOutput.getPathString()), result);
  }

  private Spawn newSpawn(
      ImmutableMap<String, String> executionInfo, ImmutableSet<Artifact> outputs) {
    return newSpawn(executionInfo, outputs, NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  private Spawn newSpawn(
      ImmutableMap<String, String> executionInfo,
      ImmutableSet<Artifact> outputs,
      NestedSet<? extends ActionInput> inputs) {
    return new SimpleSpawn(
        new FakeOwner("foo", "bar", "//dummy:label"),
        /*arguments=*/ ImmutableList.of(),
        /*environment=*/ ImmutableMap.of(),
        /*executionInfo=*/ executionInfo,
        /*inputs=*/ inputs,
        /*outputs=*/ outputs,
        ResourceSet.ZERO);
  }

  private FakeSpawnExecutionContext newSpawnExecutionContext(Spawn spawn) {
    return new FakeSpawnExecutionContext(spawn, fakeFileCache, execRoot, outErr);
  }

  private FakeSpawnExecutionContext newSpawnExecutionContext(Spawn spawn, FileOutErr outErr) {
    return new FakeSpawnExecutionContext(spawn, fakeFileCache, execRoot, outErr);
  }

  private FakeSpawnExecutionContext newSpawnExecutionContext(
      Spawn spawn, MetadataInjector metadataInjector) {
    return new FakeSpawnExecutionContext(
        spawn, fakeFileCache, execRoot, outErr, ImmutableClassToInstanceMap.of(), metadataInjector);
  }

  private RemoteExecutionService newRemoteExecutionService() {
    return newRemoteExecutionService(remoteOptions);
  }

  private RemoteExecutionService newRemoteExecutionService(RemoteOptions remoteOptions) {
    return newRemoteExecutionService(remoteOptions, ImmutableList.of());
  }

  private RemoteExecutionService newRemoteExecutionService(
      RemoteOptions remoteOptions, Collection<? extends ActionInput> topLevelOutputs) {
    return new RemoteExecutionService(
        directExecutor(),
        reporter,
        /*verboseFailures=*/ true,
        execRoot,
        remotePathResolver,
        "none",
        "none",
        digestUtil,
        remoteOptions,
        cache,
        executor,
        ImmutableSet.copyOf(topLevelOutputs),
        null);
  }

  private static RemoteFileArtifactValue remoteFileMatchingDigest(Digest expectedDigest) {
    return argThat(
        metadata ->
            Arrays.equals(metadata.getDigest(), toBinaryDigest(expectedDigest))
                && metadata.getSize() == expectedDigest.getSizeBytes());
  }
}
