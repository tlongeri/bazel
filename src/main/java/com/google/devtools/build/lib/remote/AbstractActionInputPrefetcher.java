// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.util.RxFutures.toCompletable;
import static com.google.devtools.build.lib.remote.util.RxFutures.toListenableFuture;
import static com.google.devtools.build.lib.remote.util.RxUtils.mergeBulkTransfer;
import static com.google.devtools.build.lib.remote.util.RxUtils.toTransferResult;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.util.AsyncTaskCache;
import com.google.devtools.build.lib.remote.util.RxUtils.TransferResult;
import com.google.devtools.build.lib.remote.util.TempPathGenerator;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution.Code;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract implementation of {@link ActionInputPrefetcher} which implements the orchestration of
 * prefeching multiple inputs so subclasses can focus on prefetching / downloading single input.
 */
public abstract class AbstractActionInputPrefetcher implements ActionInputPrefetcher {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final AsyncTaskCache.NoResult<Path> downloadCache = AsyncTaskCache.NoResult.create();
  private final TempPathGenerator tempPathGenerator;

  protected final Path execRoot;

  protected AbstractActionInputPrefetcher(Path execRoot, TempPathGenerator tempPathGenerator) {
    this.execRoot = execRoot;
    this.tempPathGenerator = tempPathGenerator;
  }

  public void startBuild(EventHandler eventHandler) throws AbruptExitException {
    Path tempDir = tempPathGenerator.getTempDir();
    if (tempDir.exists()) {
      eventHandler.handle(Event.warn("Found stale downloads from previous build, deleting..."));
      try {
        tempDir.deleteTree();
      } catch (IOException e) {
        throw new AbruptExitException(
            DetailedExitCode.of(
                ExitCode.LOCAL_ENVIRONMENTAL_ERROR,
                FailureDetail.newBuilder()
                    .setMessage(
                        String.format("Failed to delete stale downloads: %s", e.getMessage()))
                    .setRemoteExecution(
                        RemoteExecution.newBuilder()
                            .setCode(Code.DOWNLOADED_INPUTS_DELETION_FAILURE))
                    .build()));
      }
    }
  }

  public void finalizeBuild() {
    Path tempDir = tempPathGenerator.getTempDir();
    try {
      tempDir.deleteTree();
    } catch (IOException ignored) {
      // Intentionally left empty.
    }
  }

  protected abstract boolean shouldDownloadFile(Path path, FileArtifactValue metadata);

  /**
   * Downloads file to the given path via its metadata.
   *
   * @param tempPath the temporary path which the input should be written to.
   */
  protected abstract ListenableFuture<Void> doDownloadFile(
      Path tempPath, FileArtifactValue metadata) throws IOException;

  protected void prefetchVirtualActionInput(VirtualActionInput input) throws IOException {}

  /** Transforms the error encountered during the prefetch . */
  protected Completable onErrorResumeNext(Throwable error) {
    return Completable.error(error);
  }

  /**
   * Fetches remotely stored action outputs, that are inputs to this spawn, and stores them under
   * their path in the output base.
   *
   * <p>This method is safe to be called concurrently from spawn runners before running any local
   * spawn.
   *
   * @return a future that is completed once all downloads have finished.
   */
  @Override
  public ListenableFuture<Void> prefetchFiles(
      Iterable<? extends ActionInput> inputs, MetadataProvider metadataProvider) {
    Map<SpecialArtifact, List<TreeFileArtifact>> trees = new HashMap<>();
    List<ActionInput> files = new ArrayList<>();
    for (ActionInput input : inputs) {
      if (input instanceof Artifact && ((Artifact) input).isSourceArtifact()) {
        continue;
      }

      if (input instanceof TreeFileArtifact) {
        TreeFileArtifact treeFile = (TreeFileArtifact) input;
        SpecialArtifact treeArtifact = treeFile.getParent();
        trees.computeIfAbsent(treeArtifact, unusedKey -> new ArrayList<>()).add(treeFile);
        continue;
      }

      files.add(input);
    }

    Flowable<TransferResult> treeDownloads =
        Flowable.fromIterable(trees.entrySet())
            .flatMapSingle(
                entry ->
                    toTransferResult(
                        prefetchInputTree(metadataProvider, entry.getKey(), entry.getValue())));
    Flowable<TransferResult> fileDownloads =
        Flowable.fromIterable(files)
            .flatMapSingle(input -> toTransferResult(prefetchInputFile(metadataProvider, input)));
    Flowable<TransferResult> transfers = Flowable.merge(treeDownloads, fileDownloads);
    Completable prefetch = mergeBulkTransfer(transfers).onErrorResumeNext(this::onErrorResumeNext);
    Completable prefetchWithProfiler =
        Completable.using(
            () -> Profiler.instance().profile(ProfilerTask.REMOTE_DOWNLOAD, "stage remote inputs"),
            profiler -> prefetch,
            SilentCloseable::close);
    return toListenableFuture(prefetchWithProfiler);
  }

  private Completable prefetchInputTree(
      MetadataProvider provider, SpecialArtifact tree, List<TreeFileArtifact> treeFiles) {
    Path treeRoot = execRoot.getRelative(tree.getExecPath());
    HashMap<TreeFileArtifact, Path> treeFileTmpPathMap = new HashMap<>();

    Flowable<TransferResult> transfers =
        Flowable.fromIterable(treeFiles)
            .flatMapSingle(
                treeFile -> {
                  Path path = treeRoot.getRelative(treeFile.getParentRelativePath());
                  FileArtifactValue metadata = provider.getMetadata(treeFile);
                  if (!shouldDownloadFile(path, metadata)) {
                    return Single.just(TransferResult.ok());
                  }

                  Path tempPath = tempPathGenerator.generateTempPath();
                  treeFileTmpPathMap.put(treeFile, tempPath);

                  return toTransferResult(
                      toCompletable(() -> doDownloadFile(tempPath, metadata), directExecutor()));
                });

    AtomicBoolean completed = new AtomicBoolean();
    Completable download =
        mergeBulkTransfer(transfers)
            .doOnComplete(
                () -> {
                  HashSet<Path> dirs = new HashSet<>();

                  // Tree root is created by Bazel before action execution, but the permission is
                  // changed to 0555 afterwards. We need to set it as writable in order to move
                  // files into it.
                  treeRoot.setWritable(true);
                  dirs.add(treeRoot);

                  for (Map.Entry<TreeFileArtifact, Path> entry : treeFileTmpPathMap.entrySet()) {
                    TreeFileArtifact treeFile = entry.getKey();
                    Path tempPath = entry.getValue();

                    Path path = treeRoot.getRelative(treeFile.getParentRelativePath());
                    Path dir = treeRoot;
                    for (String segment : treeFile.getParentRelativePath().segments()) {
                      dir = dir.getRelative(segment);
                      if (dir.equals(path)) {
                        break;
                      }
                      if (dirs.add(dir)) {
                        dir.createDirectory();
                        dir.setWritable(true);
                      }
                    }
                    checkState(dir.equals(path));
                    finalizeDownload(tempPath, path);
                  }

                  for (Path dir : dirs) {
                    // Change permission of all directories of a tree artifact to 0555 (files are
                    // changed inside {@code finalizeDownload}) in order to match the behaviour when
                    // the tree artifact is generated locally. In that case, permission of all files
                    // and directories inside a tree artifact is changed to 0555 within {@code
                    // checkOutputs()}.
                    dir.chmod(0555);
                  }

                  completed.set(true);
                })
            .doFinally(
                () -> {
                  if (!completed.get()) {
                    for (Map.Entry<TreeFileArtifact, Path> entry : treeFileTmpPathMap.entrySet()) {
                      deletePartialDownload(entry.getValue());
                    }
                  }
                });
    return downloadCache.executeIfNot(treeRoot, download);
  }

  private Completable prefetchInputFile(MetadataProvider metadataProvider, ActionInput input)
      throws IOException {
    if (input instanceof VirtualActionInput) {
      prefetchVirtualActionInput((VirtualActionInput) input);
      return Completable.complete();
    }

    FileArtifactValue metadata = metadataProvider.getMetadata(input);
    if (metadata == null) {
      return Completable.complete();
    }

    Path path = execRoot.getRelative(input.getExecPath());
    return downloadFileRx(path, metadata);
  }

  /**
   * Downloads file into the {@code path} with its metadata.
   *
   * <p>The file will be written into a temporary file and moved to the final destination after the
   * download finished.
   */
  public Completable downloadFileRx(Path path, FileArtifactValue metadata) {
    if (!shouldDownloadFile(path, metadata)) {
      return Completable.complete();
    }

    if (path.isSymbolicLink()) {
      try {
        path = path.getRelative(path.readSymbolicLink());
      } catch (IOException e) {
        return Completable.error(e);
      }
    }

    Path finalPath = path;

    AtomicBoolean completed = new AtomicBoolean(false);
    Completable download =
        Completable.using(
            tempPathGenerator::generateTempPath,
            tempPath ->
                toCompletable(() -> doDownloadFile(tempPath, metadata), directExecutor())
                    .doOnComplete(
                        () -> {
                          finalizeDownload(tempPath, finalPath);
                          completed.set(true);
                        }),
            tempPath -> {
              if (!completed.get()) {
                deletePartialDownload(tempPath);
              }
            },
            // Set eager=false here because we want cleanup the download *after* upstream is
            // disposed.
            /* eager= */ false);
    return downloadCache.executeIfNot(path, download);
  }

  /**
   * Downloads file into the {@code path} with its metadata.
   *
   * <p>The file will be written into a temporary file and moved to the final destination after the
   * download finished.
   */
  public ListenableFuture<Void> downloadFileAsync(Path path, FileArtifactValue metadata) {
    return toListenableFuture(downloadFileRx(path, metadata));
  }

  /**
   * Download file to the {@code path} with given metadata. Blocking await for the download to
   * complete.
   *
   * <p>The file will be written into a temporary file and moved to the final destination after the
   * download finished.
   */
  public void downloadFile(Path path, FileArtifactValue metadata)
      throws IOException, InterruptedException {
    getFromFuture(downloadFileAsync(path, metadata));
  }

  private void finalizeDownload(Path tmpPath, Path path) throws IOException {
    // The permission of output file is changed to 0555 after action execution. We manually change
    // the permission here for the downloaded file to keep this behaviour consistent.
    tmpPath.chmod(0555);
    FileSystemUtils.moveFile(tmpPath, path);
  }

  private void deletePartialDownload(Path path) {
    try {
      path.delete();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Failed to delete output file after incomplete download: %s", path);
    }
  }

  public ImmutableSet<Path> downloadedFiles() {
    return downloadCache.getFinishedTasks();
  }

  public ImmutableSet<Path> downloadsInProgress() {
    return downloadCache.getInProgressTasks();
  }

  @VisibleForTesting
  public AsyncTaskCache.NoResult<Path> getDownloadCache() {
    return downloadCache;
  }
}
