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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.DiffAwareness.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.ImmutableDiff;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * A helper class to find dirty {@link FileStateValue} and {@link DirectoryListingStateValue} nodes
 * based on a potentially incomplete diffs.
 *
 * <p>Infers directories from files meaning that it will work for diffs which exclude entries for
 * affected ancestor entries of nodes. It is also resilient to diffs which report only a root of
 * deleted subtree.
 */
final class FileSystemValueCheckerInferringAncestors {
  @Nullable private final TimestampGranularityMonitor tsgm;
  private final Map<SkyKey, SkyValue> graphValues;
  private final Map<SkyKey, SkyValue> graphDoneValues;
  private final Map<RootedPath, NodeVisitState> nodeStates;
  private final SyscallCache syscallCache;
  private final Set<SkyKey> valuesToInvalidate = Sets.newConcurrentHashSet();
  private final ConcurrentMap<SkyKey, SkyValue> valuesToInject = new ConcurrentHashMap<>();

  private static final class NodeVisitState {

    private NodeVisitState(boolean collectMaybeDeletedChildren) {
      if (collectMaybeDeletedChildren) {
        maybeDeletedChildren = ConcurrentHashMap.newKeySet();
      }
    }

    private final AtomicInteger childrenToProcess = new AtomicInteger();
    // non-volatile since childrenToProcess ensures happens-before relationship.
    private boolean needsToBeVisited;

    private volatile boolean isInferredDirectory;
    private volatile Set<String> maybeDeletedChildren;

    void markInferredDirectory() {
      isInferredDirectory = true;
      // maybeTypeChangedChildren is used to figure out if the entry is a directory, since we
      // already inferred it, we can stop collecting those.
      maybeDeletedChildren = null;
    }

    void addMaybeDeletedChild(String child) {
      Set<String> localMaybeDeletedChildren = maybeDeletedChildren;
      if (localMaybeDeletedChildren != null) {
        localMaybeDeletedChildren.add(child);
      }
    }

    boolean signalFinishedChild(boolean needsToBeVisited) {
      // The order is important, we must update this.needsToBeVisited before decrementing
      // childrenToProcess -- that operation ensures this change is visible to other threads doing
      // the same (including this thread picking up a true set by another one).
      if (needsToBeVisited) {
        this.needsToBeVisited = true;
      }
      int childrenLeft = childrenToProcess.decrementAndGet();
      // If we hit 0, we know that all other threads have set and propagated needsToBeVisited.
      return childrenLeft == 0 && this.needsToBeVisited;
    }
  }

  private FileSystemValueCheckerInferringAncestors(
      @Nullable TimestampGranularityMonitor tsgm,
      Map<SkyKey, SkyValue> graphValues,
      Map<SkyKey, SkyValue> graphDoneValues,
      Map<RootedPath, NodeVisitState> nodeStates,
      SyscallCache syscallCache) {
    this.tsgm = tsgm;
    this.graphValues = graphValues;
    this.graphDoneValues = graphDoneValues;
    this.nodeStates = nodeStates;
    this.syscallCache = syscallCache;
  }

  @SuppressWarnings("ReferenceEquality")
  static ImmutableDiff getDiffWithInferredAncestors(
      @Nullable TimestampGranularityMonitor tsgm,
      Map<SkyKey, SkyValue> graphValues,
      Map<SkyKey, SkyValue> graphDoneValues,
      Iterable<FileStateKey> modifiedKeys,
      int nThreads,
      SyscallCache syscallCache)
      throws InterruptedException, AbruptExitException {
    Map<RootedPath, NodeVisitState> nodeStates = new HashMap<>();
    for (FileStateKey fileStateKey : modifiedKeys) {
      RootedPath top = fileStateKey.argument();
      // Start with false since the reported diff does not mean we are adding a child.
      boolean lastCreated = false;
      for (RootedPath path = top; path != null; path = path.getParentDirectory()) {
        @Nullable NodeVisitState existingState = nodeStates.get(path);
        NodeVisitState state;
        // We disable the optimization which detects whether directory still exists based on the
        // list of deleted children and listing. It is possible for the diff to report a deleted
        // directory without listing all of the files under it as deleted.
        if (existingState == null) {
          state = new NodeVisitState(/*collectMaybeDeletedChildren=*/ path != top);
          nodeStates.put(path, state);
        } else {
          state = existingState;
          if (path == top) {
            state.maybeDeletedChildren = null;
          }
        }
        if (lastCreated) {
          state.childrenToProcess.incrementAndGet();
        }
        lastCreated = existingState == null;
      }
    }

    return new FileSystemValueCheckerInferringAncestors(
            tsgm,
            graphValues,
            graphDoneValues,
            Collections.unmodifiableMap(nodeStates),
            syscallCache)
        .processEntries(nThreads);
  }

  private ImmutableDiff processEntries(int nThreads)
      throws InterruptedException, AbruptExitException {
    ExecutorService executor = Executors.newFixedThreadPool(nThreads);

    // Materialize all leaves before scheduling them -- otherwise, we could race with the
    // processing code which decrements childrenToProcess.
    ImmutableList<Callable<Void>> leaves =
        nodeStates.entrySet().stream()
            .filter(e -> e.getValue().childrenToProcess.get() == 0)
            .<Callable<Void>>map(
                e ->
                    () -> {
                      processEntry(e.getKey(), e.getValue());
                      return null;
                    })
            .collect(toImmutableList());

    List<Future<Void>> futures = executor.invokeAll(leaves);

    if (ExecutorUtil.interruptibleShutdown(executor)) {
      throw new InterruptedException();
    }

    for (Future<?> future : futures) {
      try {
        Futures.getDone(future);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof StatFailedException) {
          throw new AbruptExitException(
              DetailedExitCode.of(
                  FailureDetail.newBuilder()
                      .setMessage(e.getCause().getMessage())
                      .setDiffAwareness(
                          FailureDetails.DiffAwareness.newBuilder().setCode(Code.DIFF_STAT_FAILED))
                      .build()),
              e);
        }
        throw new IllegalStateException(e);
      }
    }

    return new ImmutableDiff(valuesToInvalidate, valuesToInject);
  }

  private void processEntry(RootedPath path, NodeVisitState state) throws StatFailedException {
    NodeVisitState rootParentSentinel = new NodeVisitState(/*collectMaybeDeletedChildren=*/ false);

    while (state != rootParentSentinel) {
      RootedPath parentPath = path.getParentDirectory();
      NodeVisitState parentState =
          parentPath != null ? nodeStates.get(parentPath) : rootParentSentinel;
      boolean visitParent =
          visitEntry(path, state.isInferredDirectory, state.maybeDeletedChildren, parentState);
      boolean processParent = parentState.signalFinishedChild(visitParent);

      if (!processParent) {
        // This is a tree, only one child can trigger parent processing.
        return;
      }

      state = parentState;
      path = path.getParentDirectory();
    }
  }

  /**
   * Visits the given node and return whether the type of it may have changed.
   *
   * <p>Returns false if we know that the type has not changed. It may however return true if the
   * type has not changed.
   *
   * @param isInferredDirectory whether the node was already inferred as a directory from children.
   * @param maybeDeletedChildren if not null, exhaustive list of all children which may have their
   *     file system type changed (including deletions).
   */
  private boolean visitEntry(
      RootedPath path,
      boolean isInferredDirectory,
      @Nullable Set<String> maybeDeletedChildren,
      NodeVisitState parentState)
      throws StatFailedException {
    FileStateKey key = FileStateValue.key(path);
    @Nullable FileStateValue fsv = (FileStateValue) graphValues.get(key);
    if (fsv == null) {
      visitUnknownEntry(key, isInferredDirectory, parentState);
      parentState.addMaybeDeletedChild(path.getRootRelativePath().getBaseName());
      return true;
    }

    if (isInferredDirectory
        || (maybeDeletedChildren != null
            && listingHasEntriesOutsideOf(path, maybeDeletedChildren))) {
      parentState.markInferredDirectory();
      if (fsv.getType().isDirectory()) {
        return false;
      }
      valuesToInject.put(key, FileStateValue.DIRECTORY_FILE_STATE_NODE);
      parentListingKey(path).ifPresent(valuesToInvalidate::add);
      return true;
    }

    FileStateValue newFsv = getNewFileStateValueFromFileSystem(path);
    if (!newFsv.equals(fsv)) {
      valuesToInject.put(key, newFsv);
    }

    if (newFsv.getType().exists()) {
      parentState.markInferredDirectory();
    } else if (fsv.getType().exists()) {
      // exists -> not exists -- deletion.
      parentState.addMaybeDeletedChild(path.getRootRelativePath().getBaseName());
    }

    boolean typeChanged = newFsv.getType() != fsv.getType();
    if (typeChanged) {
      parentListingKey(path).ifPresent(valuesToInvalidate::add);
    }
    return typeChanged;
  }

  private void visitUnknownEntry(
      FileStateKey key, boolean isInferredDirectory, NodeVisitState parentState)
      throws StatFailedException {
    RootedPath path = key.argument();
    // Run stats on unknown files in order to preserve the parent listing if present unless we
    // already know it has changed.
    Optional<DirectoryListingStateValue.Key> parentListingKey = parentListingKey(path);
    @Nullable
    DirectoryListingStateValue parentListing =
        parentListingKey
            // Only look for done listings since already invalidated ones will be reevaluated
            // anyway.
            .map(k -> (DirectoryListingStateValue) graphDoneValues.get(k))
            .orElse(null);

    // No listing/we already know it has changed -- nothing to gain from stats anymore.
    if (parentListing == null || valuesToInvalidate.contains(parentListingKey.get())) {
      if (isInferredDirectory) {
        parentState.markInferredDirectory();
      }
      valuesToInvalidate.add(key);
      parentListingKey.ifPresent(valuesToInvalidate::add);
      return;
    }

    // We don't take advantage of isInferredDirectory because we set it only in cases of a present
    // descendant/done listing which normally cannot exist without having FileStateValue for
    // ancestors.
    FileStateValue value = getNewFileStateValueFromFileSystem(path);
    valuesToInject.put(key, value);
    if (isInferredDirectory || value.getType().exists()) {
      parentState.markInferredDirectory();
    }

    @Nullable
    Dirent dirent =
        parentListing.getDirents().maybeGetDirent(path.getRootRelativePath().getBaseName());
    @Nullable Dirent.Type typeInListing = dirent != null ? dirent.getType() : null;
    if (!Objects.equals(typeInListing, direntTypeFromFileStateType(value.getType()))) {
      valuesToInvalidate.add(parentListingKey.get());
    }
  }

  private FileStateValue getNewFileStateValueFromFileSystem(RootedPath path)
      throws StatFailedException {
    try {
      return FileStateValue.create(path, syscallCache, tsgm);
    } catch (IOException e) {
      throw new StatFailedException(path, e);
    }
  }

  private boolean listingHasEntriesOutsideOf(RootedPath path, Set<String> allAffectedEntries) {
    // TODO(192010830): Try looking up BUILD files if there is no listing -- this is a lookup we
    //  can speculatively try since those files are often checked against.
    @Nullable
    DirectoryListingStateValue listing =
        (DirectoryListingStateValue) graphDoneValues.get(DirectoryListingStateValue.key(path));
    if (listing == null) {
      return false;
    }
    for (Dirent entry : listing.getDirents()) {
      if (!allAffectedEntries.contains(entry.getName())) {
        return true;
      }
    }
    return false;
  }

  private static Optional<DirectoryListingStateValue.Key> parentListingKey(RootedPath path) {
    return Optional.ofNullable(path.getParentDirectory()).map(DirectoryListingStateValue::key);
  }

  @Nullable
  private static Dirent.Type direntTypeFromFileStateType(FileStateType type) {
    switch (type) {
      case NONEXISTENT:
        return null;
      case REGULAR_FILE:
        return Dirent.Type.FILE;
      case SPECIAL_FILE:
        return Dirent.Type.UNKNOWN;
      case SYMLINK:
        return Dirent.Type.SYMLINK;
      case DIRECTORY:
        return Dirent.Type.DIRECTORY;
    }
    throw new AssertionError();
  }

  private static class StatFailedException extends Exception {
    StatFailedException(RootedPath path, IOException cause) {
      super(String.format("Failed to stat: '%s' while computing diff", path.asPath()), cause);
    }
  }
}
