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

import static com.google.common.base.Preconditions.checkArgument;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput.EmptyActionInput;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TempPathGenerator;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import io.reactivex.rxjava3.core.Completable;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Stages output files that are stored remotely to the local filesystem.
 *
 * <p>This is necessary when a locally executed action consumes outputs produced by a remotely
 * executed action and {@code --experimental_remote_download_outputs=minimal} is specified.
 */
class RemoteActionInputFetcher extends AbstractActionInputPrefetcher {

  private final String buildRequestId;
  private final String commandId;
  private final RemoteCache remoteCache;

  RemoteActionInputFetcher(
      String buildRequestId,
      String commandId,
      RemoteCache remoteCache,
      Path execRoot,
      TempPathGenerator tempPathGenerator,
      ImmutableList<Pattern> patternsToDownload) {
    super(execRoot, tempPathGenerator, patternsToDownload);
    this.buildRequestId = Preconditions.checkNotNull(buildRequestId);
    this.commandId = Preconditions.checkNotNull(commandId);
    this.remoteCache = Preconditions.checkNotNull(remoteCache);
  }

  @Override
  public boolean supportsPartialTreeArtifactInputs() {
    // This prefetcher is unable to fetch only individual files inside a tree artifact.
    return false;
  }

  @Override
  protected void prefetchVirtualActionInput(VirtualActionInput input) throws IOException {
    if (!(input instanceof EmptyActionInput)) {
      Path outputPath = execRoot.getRelative(input.getExecPath());
      SandboxHelpers.atomicallyWriteVirtualInput(input, outputPath, ".remote");
    }
  }

  @Override
  protected boolean canDownloadFile(Path path, FileArtifactValue metadata) {
    return metadata.isRemote();
  }

  @Override
  protected ListenableFuture<Void> doDownloadFile(
      Path tempPath, PathFragment execPath, FileArtifactValue metadata, Priority priority)
      throws IOException {
    checkArgument(metadata.isRemote(), "Cannot download file that is not a remote file.");
    RequestMetadata requestMetadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, metadata.getActionId(), null);
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(requestMetadata);

    Digest digest = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize());
    return remoteCache.downloadFile(context, tempPath, digest);
  }

  @Override
  protected Completable onErrorResumeNext(Throwable error) {
    if (error instanceof BulkTransferException) {
      if (((BulkTransferException) error).onlyCausedByCacheNotFoundException()) {
        BulkTransferException bulkAnnotatedException = new BulkTransferException();
        for (Throwable t : error.getSuppressed()) {
          IOException annotatedException =
              new IOException(
                  String.format(
                      "Failed to fetch file with hash '%s' because it does not"
                          + " exist remotely. --remote_download_outputs=minimal"
                          + " does not work if your remote cache evicts files"
                          + " during builds.",
                      ((CacheNotFoundException) t).getMissingDigest().getHash()));
          bulkAnnotatedException.add(annotatedException);
        }
        error = bulkAnnotatedException;
      }
    }
    return Completable.error(error);
  }
}
