// Copyright 2017 The Bazel Authors. All rights reserved.
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
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;

import build.bazel.remote.execution.v2.Digest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamFutureStub;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusRequest;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.remote.RemoteRetrier.ProgressiveBackoff;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import io.grpc.Channel;
import io.grpc.Context;
import io.grpc.Context.CancellableContext;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.netty.util.ReferenceCounted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;

/**
 * A client implementing the {@code Write} method of the {@code ByteStream} gRPC service.
 *
 * <p>The uploader supports reference counting to easily be shared between components with different
 * lifecyles. After instantiation the reference count is {@code 1}.
 *
 * <p>See {@link ReferenceCounted} for more information on reference counting.
 */
class ByteStreamUploader {
  private final String instanceName;
  private final ReferenceCountedChannel channel;
  private final CallCredentialsProvider callCredentialsProvider;
  private final long callTimeoutSecs;
  private final RemoteRetrier retrier;

  @Nullable private final Semaphore openedFilePermits;

  /**
   * Creates a new instance.
   *
   * @param instanceName the instance name to be prepended to resource name of the {@code Write}
   *     call. See the {@code ByteStream} service definition for details
   * @param channel the {@link io.grpc.Channel} to use for calls
   * @param callCredentialsProvider the credentials provider to use for authentication.
   * @param callTimeoutSecs the timeout in seconds after which a {@code Write} gRPC call must be
   *     complete. The timeout resets between retries
   * @param retrier the {@link RemoteRetrier} whose backoff strategy to use for retry timings.
   */
  ByteStreamUploader(
      @Nullable String instanceName,
      ReferenceCountedChannel channel,
      CallCredentialsProvider callCredentialsProvider,
      long callTimeoutSecs,
      RemoteRetrier retrier,
      int maximumOpenFiles) {
    checkArgument(callTimeoutSecs > 0, "callTimeoutSecs must be gt 0.");
    this.instanceName = instanceName;
    this.channel = channel;
    this.callCredentialsProvider = callCredentialsProvider;
    this.callTimeoutSecs = callTimeoutSecs;
    this.retrier = retrier;
    this.openedFilePermits = maximumOpenFiles != -1 ? new Semaphore(maximumOpenFiles) : null;
  }

  @VisibleForTesting
  ReferenceCountedChannel getChannel() {
    return channel;
  }

  @VisibleForTesting
  RemoteRetrier getRetrier() {
    return retrier;
  }

  /**
   * Uploads a BLOB, as provided by the {@link Chunker}, to the remote {@code ByteStream} service.
   * The call blocks until the upload is complete, or throws an {@link Exception} in case of error.
   *
   * <p>Uploads are retried according to the specified {@link RemoteRetrier}. Retrying is
   * transparent to the user of this API.
   *
   * @param digest the digest of the data to upload.
   * @param chunker the data to upload.
   * @throws IOException when reading of the {@link Chunker}s input source fails
   */
  public void uploadBlob(RemoteActionExecutionContext context, Digest digest, Chunker chunker)
      throws IOException, InterruptedException {
    getFromFuture(uploadBlobAsync(context, digest, chunker));
  }

  /**
   * Uploads a list of BLOBs concurrently to the remote {@code ByteStream} service. The call blocks
   * until the upload of all BLOBs is complete, or throws an {@link
   * com.google.devtools.build.lib.remote.common.BulkTransferException} if there are errors.
   *
   * <p>Uploads are retried according to the specified {@link RemoteRetrier}. Retrying is
   * transparent to the user of this API.
   *
   * @param chunkers the data to upload.
   * @throws IOException when reading of the {@link Chunker}s input source or uploading fails
   */
  public void uploadBlobs(RemoteActionExecutionContext context, Map<Digest, Chunker> chunkers)
      throws IOException, InterruptedException {
    List<ListenableFuture<Void>> uploads = new ArrayList<>();

    for (Map.Entry<Digest, Chunker> chunkerEntry : chunkers.entrySet()) {
      uploads.add(uploadBlobAsync(context, chunkerEntry.getKey(), chunkerEntry.getValue()));
    }

    waitForBulkTransfer(uploads, /* cancelRemainingOnInterrupt= */ true);
  }

  /**
   * Uploads a BLOB asynchronously to the remote {@code ByteStream} service. The call returns
   * immediately and one can listen to the returned future for the success/failure of the upload.
   *
   * <p>Uploads are retried according to the specified {@link RemoteRetrier}. Retrying is
   * transparent to the user of this API.
   *
   * <p>Trying to upload the same BLOB multiple times concurrently, results in only one upload being
   * performed. This is transparent to the user of this API.
   *
   * @param digest the {@link Digest} of the data to upload.
   * @param chunker the data to upload.
   */
  public ListenableFuture<Void> uploadBlobAsync(
      RemoteActionExecutionContext context, Digest digest, Chunker chunker) {
    return Futures.catchingAsync(
        startAsyncUpload(context, digest, chunker),
        StatusRuntimeException.class,
        (sre) ->
            Futures.immediateFailedFuture(
                new IOException(
                    String.format(
                        "Error while uploading artifact with digest '%s/%s'",
                        digest.getHash(), digest.getSizeBytes()),
                    sre)),
        MoreExecutors.directExecutor());
  }

  private static String buildUploadResourceName(
      String instanceName, UUID uuid, Digest digest, boolean compressed) {
    String template =
        compressed ? "uploads/%s/compressed-blobs/zstd/%s/%d" : "uploads/%s/blobs/%s/%d";
    String resourceName = format(template, uuid, digest.getHash(), digest.getSizeBytes());
    if (!Strings.isNullOrEmpty(instanceName)) {
      resourceName = instanceName + "/" + resourceName;
    }
    return resourceName;
  }

  /** Starts a file upload and returns a future representing the upload. */
  private ListenableFuture<Void> startAsyncUpload(
      RemoteActionExecutionContext context, Digest digest, Chunker chunker) {
    try {
      chunker.reset();
    } catch (IOException e) {
      return Futures.immediateFailedFuture(e);
    }

    if (chunker.getSize() != digest.getSizeBytes()) {
      return Futures.immediateFailedFuture(
          new IllegalStateException(
              String.format(
                  "Expected chunker size of %d, got %d",
                  digest.getSizeBytes(), chunker.getSize())));
    }

    UUID uploadId = UUID.randomUUID();
    String resourceName =
        buildUploadResourceName(instanceName, uploadId, digest, chunker.isCompressed());
    if (openedFilePermits != null) {
      try {
        openedFilePermits.acquire();
      } catch (InterruptedException e) {
        return Futures.immediateFailedFuture(
            new InterruptedException(
                "Unexpected interrupt while acquiring open file permit. Original error message: "
                    + e.getMessage()));
      }
    }
    AsyncUpload newUpload =
        new AsyncUpload(
            context,
            channel,
            callCredentialsProvider,
            callTimeoutSecs,
            retrier,
            resourceName,
            chunker);
    ListenableFuture<Void> currUpload = newUpload.start();
    currUpload.addListener(
        () -> {
          newUpload.cancel();
          if (openedFilePermits != null) {
            openedFilePermits.release();
          }
        },
        MoreExecutors.directExecutor());
    return currUpload;
  }

  private static final class AsyncUpload implements AsyncCallable<Long> {
    private final RemoteActionExecutionContext context;
    private final ReferenceCountedChannel channel;
    private final CallCredentialsProvider callCredentialsProvider;
    private final long callTimeoutSecs;
    private final Retrier retrier;
    private final String resourceName;
    private final Chunker chunker;
    private final ProgressiveBackoff progressiveBackoff;
    private final CancellableContext grpcContext;

    private long lastCommittedOffset = -1;

    AsyncUpload(
        RemoteActionExecutionContext context,
        ReferenceCountedChannel channel,
        CallCredentialsProvider callCredentialsProvider,
        long callTimeoutSecs,
        Retrier retrier,
        String resourceName,
        Chunker chunker) {
      this.context = context;
      this.channel = channel;
      this.callCredentialsProvider = callCredentialsProvider;
      this.callTimeoutSecs = callTimeoutSecs;
      this.retrier = retrier;
      this.progressiveBackoff = new ProgressiveBackoff(retrier::newBackoff);
      this.resourceName = resourceName;
      this.chunker = chunker;
      this.grpcContext = Context.current().withCancellation();
    }

    ListenableFuture<Void> start() {
      return Futures.transformAsync(
          Utils.refreshIfUnauthenticatedAsync(
              () -> retrier.executeAsync(this, progressiveBackoff), callCredentialsProvider),
          committedSize -> {
            try {
              checkCommittedSize(committedSize);
            } catch (IOException e) {
              return Futures.immediateFailedFuture(e);
            }
            return immediateVoidFuture();
          },
          MoreExecutors.directExecutor());
    }

    private void checkCommittedSize(long committedSize) throws IOException {
      // Only check for matching committed size if we have completed the upload.  If another client
      // did, they might have used a different compression level/algorithm, so we cannot know the
      // expected committed offset
      if (chunker.hasNext()) {
        return;
      }

      long expected = chunker.getOffset();

      if (committedSize == expected) {
        // Both compressed and uncompressed uploads can succeed with this result.
        return;
      }

      if (chunker.isCompressed()) {
        if (committedSize == -1) {
          // Returned early, blob already available.
          return;
        }

        throw new IOException(
            format(
                "compressed write incomplete: committed_size %d is" + " neither -1 nor total %d",
                committedSize, expected));
      }

      // Uncompressed upload failed.
      throw new IOException(
          format("write incomplete: committed_size %d for %d total", committedSize, expected));
    }

    /**
     * Make one attempt to upload. If this is the first attempt, uploading starts from the beginning
     * of the blob. On later attempts, the server is queried to see at which offset upload should
     * resume. The final committed size from the server is returned on success.
     */
    @Override
    public ListenableFuture<Long> call() {
      boolean firstAttempt = lastCommittedOffset == -1;
      return Futures.transformAsync(
          firstAttempt ? Futures.immediateFuture(0L) : query(),
          committedSize -> {
            if (!firstAttempt) {
              if (chunker.getSize() == committedSize) {
                return Futures.immediateFuture(committedSize);
              }
              if (committedSize > lastCommittedOffset) {
                // We have made progress on this upload in the last request. Reset the backoff so
                // that this request has a full deck of retries
                progressiveBackoff.reset();
              }
            }
            lastCommittedOffset = committedSize;
            return upload(committedSize);
          },
          MoreExecutors.directExecutor());
    }

    private ByteStreamFutureStub bsFutureStub(Channel channel) {
      return ByteStreamGrpc.newFutureStub(channel)
          .withInterceptors(
              TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()))
          .withCallCredentials(callCredentialsProvider.getCallCredentials())
          .withDeadlineAfter(callTimeoutSecs, SECONDS);
    }

    private ByteStreamStub bsAsyncStub(Channel channel) {
      return ByteStreamGrpc.newStub(channel)
          .withInterceptors(
              TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()))
          .withCallCredentials(callCredentialsProvider.getCallCredentials())
          .withDeadlineAfter(callTimeoutSecs, SECONDS);
    }

    private ListenableFuture<Long> query() {
      ListenableFuture<Long> committedSizeFuture =
          Futures.transform(
              channel.withChannelFuture(
                  channel ->
                      grpcContext.call(
                          () ->
                              bsFutureStub(channel)
                                  .queryWriteStatus(
                                      QueryWriteStatusRequest.newBuilder()
                                          .setResourceName(resourceName)
                                          .build()))),
              QueryWriteStatusResponse::getCommittedSize,
              MoreExecutors.directExecutor());
      return Futures.catchingAsync(
          committedSizeFuture,
          Exception.class,
          (e) -> {
            Status status = Status.fromThrowable(e);
            if (status.getCode() == Code.UNIMPLEMENTED) {
              // if the bytestream server does not implement the query, insist
              // that we should reset the upload
              return Futures.immediateFuture(0L);
            }
            return Futures.immediateFailedFuture(e);
          },
          MoreExecutors.directExecutor());
    }

    private ListenableFuture<Long> upload(long pos) {
      return channel.withChannelFuture(
          channel -> {
            SettableFuture<Long> uploadResult = SettableFuture.create();
            grpcContext.run(
                () ->
                    bsAsyncStub(channel)
                        .write(new Writer(resourceName, chunker, pos, uploadResult)));
            return uploadResult;
          });
    }

    void cancel() {
      grpcContext.cancel(
          Status.CANCELLED.withDescription("Cancelled by user").asRuntimeException());
    }
  }

  private static final class Writer
      implements ClientResponseObserver<WriteRequest, WriteResponse>, Runnable {
    private final Chunker chunker;
    private final long pos;
    private final String resourceName;
    private final SettableFuture<Long> uploadResult;
    private long committedSize = -1;
    private ClientCallStreamObserver<WriteRequest> requestObserver;
    private boolean first = true;

    private Writer(
        String resourceName, Chunker chunker, long pos, SettableFuture<Long> uploadResult) {
      this.resourceName = resourceName;
      this.chunker = chunker;
      this.pos = pos;
      this.uploadResult = uploadResult;
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<WriteRequest> requestObserver) {
      this.requestObserver = requestObserver;
      requestObserver.setOnReadyHandler(this);
    }

    @Override
    public void run() {
      if (committedSize != -1) {
        requestObserver.cancel("server has returned early", null);
        return;
      }
      while (requestObserver.isReady()) {
        WriteRequest.Builder request = WriteRequest.newBuilder();
        if (first) {
          first = false;
          if (!seekChunker()) {
            return;
          }
          // Resource name only needs to be set on the first write for each file.
          request.setResourceName(resourceName);
        }
        Chunker.Chunk chunk;
        try {
          chunk = chunker.next();
        } catch (IOException e) {
          requestObserver.cancel("Failed to read next chunk.", e);
          return;
        }
        boolean isLastChunk = !chunker.hasNext();
        requestObserver.onNext(
            request
                .setData(chunk.getData())
                .setWriteOffset(chunk.getOffset())
                .setFinishWrite(isLastChunk)
                .build());
        if (isLastChunk) {
          requestObserver.onCompleted();
          return;
        }
      }
    }

    private boolean seekChunker() {
      try {
        chunker.seek(pos);
      } catch (IOException e) {
        try {
          chunker.reset();
        } catch (IOException resetException) {
          e.addSuppressed(resetException);
        }
        String tooManyOpenFilesError = "Too many open files";
        if (Ascii.toLowerCase(e.getMessage()).contains(Ascii.toLowerCase(tooManyOpenFilesError))) {
          String newMessage =
              "An IOException was thrown because the process opened too many files. We recommend"
                  + " setting --bep_maximum_open_remote_upload_files flag to a number lower than"
                  + " your system default (run 'ulimit -a' for *nix-based operating systems)."
                  + " Original error message: "
                  + e.getMessage();
          e = new IOException(newMessage, e);
        }
        uploadResult.setException(e);
        requestObserver.cancel("failed to seek chunk", e);
        return false;
      }
      return true;
    }

    @Override
    public void onNext(WriteResponse response) {
      committedSize = response.getCommittedSize();
    }

    @Override
    public void onCompleted() {
      uploadResult.set(committedSize);
    }

    @Override
    public void onError(Throwable t) {
      uploadResult.setException(t);
    }
  }

  @VisibleForTesting
  public Semaphore getOpenedFilePermits() {
    return openedFilePermits;
  }
}
