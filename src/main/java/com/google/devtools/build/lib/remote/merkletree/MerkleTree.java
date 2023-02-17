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
package com.google.devtools.build.lib.remote.merkletree;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nullable;

/** A merkle tree representation as defined by the remote execution api. */
public class MerkleTree {

  /** A path or contents */
  public static class PathOrBytes {

    private final Path path;
    private final ByteString bytes;

    public PathOrBytes(Path path) {
      this.path = Preconditions.checkNotNull(path, "path");
      this.bytes = null;
    }

    public PathOrBytes(ByteString bytes) {
      this.bytes = Preconditions.checkNotNull(bytes, "bytes");
      this.path = null;
    }

    @Nullable
    public Path getPath() {
      return path;
    }

    @Nullable
    public ByteString getBytes() {
      return bytes;
    }
  }

  private interface MerkleTreeDirectoryVisitor {

    /**
     * Visits each directory in a {@code MerkleTree}.
     *
     * <p>The order of the iteration is undefined.
     *
     * @param dir a directory in the {@code MerkleTree}.
     */
    void visitDirectory(MerkleTree dir);
  }

  private Map<Digest, Directory> digestDirectoryMap;
  private Map<Digest, PathOrBytes> digestFileMap;
  @Nullable private final Directory rootProto;
  private final Digest rootDigest;
  private final SortedSet<DirectoryTree.FileNode> files;
  private final SortedMap<String, MerkleTree> directories;
  private final long inputFiles;
  private final long inputBytes;

  private MerkleTree(
      @Nullable Directory rootProto,
      Digest rootDigest,
      SortedSet<DirectoryTree.FileNode> files,
      SortedMap<String, MerkleTree> directories,
      long inputFiles,
      long inputBytes) {
    this.digestDirectoryMap = null;
    this.digestFileMap = null;
    this.rootProto = rootProto;
    this.rootDigest = Preconditions.checkNotNull(rootDigest, "rootDigest");
    this.files = Preconditions.checkNotNull(files, "files");
    this.directories = Preconditions.checkNotNull(directories, "directories");
    this.inputFiles = inputFiles;
    this.inputBytes = inputBytes;
  }

  /** Returns the digest of the Merkle tree's root. */
  @Nullable
  public Directory getRootProto() {
    return rootProto;
  }

  /** Returns the protobuf representation of the Merkle tree's root. */
  public Digest getRootDigest() {
    return rootDigest;
  }

  private SortedSet<DirectoryTree.FileNode> getFiles() {
    return files;
  }

  private SortedMap<String, MerkleTree> getDirectories() {
    return directories;
  }

  private void visitTree(MerkleTreeDirectoryVisitor visitor) {
    visitor.visitDirectory(this);
    for (MerkleTree dir : getDirectories().values()) {
      dir.visitTree(visitor);
    }
  }

  /** Returns the number of files represented by this merkle tree */
  public long getInputFiles() {
    return inputFiles;
  }

  /** Returns the sum of file sizes plus protobuf sizes used to represent this merkle tree */
  public long getInputBytes() {
    return inputBytes;
  }

  private Map<Digest, Directory> getDigestDirectoryMap() {
    if (this.digestDirectoryMap == null) {
      Map<Digest, Directory> newDigestMap = Maps.newHashMap();
      visitTree(
          (dir) -> {
            if (dir.getRootProto() != null) {
              newDigestMap.put(dir.getRootDigest(), dir.getRootProto());
            }
          });
      this.digestDirectoryMap = newDigestMap;
    }
    return this.digestDirectoryMap;
  }

  private Map<Digest, PathOrBytes> getDigestFileMap() {
    if (this.digestFileMap == null) {
      Map<Digest, PathOrBytes> newDigestMap = Maps.newHashMap();
      visitTree(
          (dir) -> {
            for (DirectoryTree.FileNode file : dir.getFiles()) {
              newDigestMap.put(file.getDigest(), toPathOrBytes(file));
            }
          });
      this.digestFileMap = newDigestMap;
    }
    return this.digestFileMap;
  }

  @Nullable
  public Directory getDirectoryByDigest(Digest digest) {
    return getDigestDirectoryMap().get(digest);
  }

  @Nullable
  public PathOrBytes getFileByDigest(Digest digest) {
    return getDigestFileMap().get(digest);
  }

  /**
   * Returns the hashes of all nodes and leafs of the merkle tree. That is, the hashes of the {@link
   * Directory} protobufs and {@link ActionInput} files.
   */
  public Iterable<Digest> getAllDigests() {
    return Iterables.concat(getDigestDirectoryMap().keySet(), getDigestFileMap().keySet());
  }

  /**
   * Constructs a merkle tree from a lexicographically sorted map of inputs (files).
   *
   * @param inputs a map of path to input. The map is required to be sorted lexicographically by
   *     paths. Inputs of type tree artifacts are not supported and are expected to have been
   *     expanded before.
   * @param metadataProvider provides metadata for all {@link ActionInput}s in {@code inputs}, as
   *     well as any {@link ActionInput}s being discovered via directory expansion.
   * @param execRoot all paths in {@code inputs} need to be relative to this {@code execRoot}.
   * @param digestUtil a hashing utility
   */
  public static MerkleTree build(
      SortedMap<PathFragment, ActionInput> inputs,
      MetadataProvider metadataProvider,
      Path execRoot,
      DigestUtil digestUtil)
      throws IOException {
    try (SilentCloseable c = Profiler.instance().profile("MerkleTree.build(ActionInput)")) {
      DirectoryTree tree =
          DirectoryTreeBuilder.fromActionInputs(inputs, metadataProvider, execRoot, digestUtil);
      return build(tree, digestUtil);
    }
  }

  /**
   * Constructs a merkle tree from a lexicographically sorted map of files.
   *
   * @param inputFiles a map of path to files. The map is required to be sorted lexicographically by
   *     paths.
   * @param digestUtil a hashing utility
   */
  public static MerkleTree build(SortedMap<PathFragment, Path> inputFiles, DigestUtil digestUtil)
      throws IOException {
    try (SilentCloseable c = Profiler.instance().profile("MerkleTree.build(Path)")) {
      DirectoryTree tree = DirectoryTreeBuilder.fromPaths(inputFiles, digestUtil);
      return build(tree, digestUtil);
    }
  }

  private static MerkleTree build(DirectoryTree tree, DigestUtil digestUtil) {
    Preconditions.checkNotNull(tree);
    if (tree.isEmpty()) {
      return new MerkleTree(
          null,
          digestUtil.compute(new byte[0]),
          ImmutableSortedSet.of(),
          ImmutableSortedMap.of(),
          0,
          0);
    }
    Map<PathFragment, MerkleTree> m = new HashMap<>();
    tree.visit(
        (dirname, files, dirs) -> {
          SortedMap<String, MerkleTree> subDirs = new TreeMap<>();
          for (DirectoryTree.DirectoryNode dir : dirs) {
            PathFragment subDirname = dirname.getRelative(dir.getPathSegment());
            MerkleTree subMerkleTree =
                Preconditions.checkNotNull(m.remove(subDirname), "subMerkleTree was null");
            subDirs.put(dir.getPathSegment(), subMerkleTree);
          }
          MerkleTree mt = buildMerkleTree(new TreeSet<>(files), subDirs, digestUtil);
          m.put(dirname, mt);
        });
    MerkleTree rootMerkleTree = m.get(PathFragment.EMPTY_FRAGMENT);
    Preconditions.checkState(
        rootMerkleTree.getInputFiles() == tree.numFiles(),
        "rootMerkleTree.getInputFiles() %s != tree.numFiles() %s",
        rootMerkleTree.getInputFiles(),
        tree.numFiles());
    return rootMerkleTree;
  }

  public static MerkleTree merge(Collection<MerkleTree> merkleTrees, DigestUtil digestUtil) {
    if (merkleTrees.isEmpty()) {
      return build(new DirectoryTree(ImmutableMap.of(), 0), digestUtil);
    }

    MerkleTree firstMerkleTree = merkleTrees.iterator().next();
    Digest firstRootDigest = firstMerkleTree.getRootDigest();
    if (merkleTrees.stream()
        .allMatch((mt) -> Objects.equals(mt.getRootDigest(), firstRootDigest))) {
      // All are the same, pick the first one.
      return firstMerkleTree;
    }

    // Some differ, do a full merge.
    SortedSet<DirectoryTree.FileNode> files = Sets.newTreeSet();
    for (MerkleTree merkleTree : merkleTrees) {
      files.addAll(merkleTree.getFiles());
    }

    // Group all Merkle trees per path.
    Multimap<String, MerkleTree> allDirsToMerge = ArrayListMultimap.create();
    for (MerkleTree merkleTree : merkleTrees) {
      merkleTree.getDirectories().forEach(allDirsToMerge::put);
    }
    // Merge the Merkle trees for each path.
    SortedMap<String, MerkleTree> directories = new TreeMap<>();
    allDirsToMerge
        .asMap()
        .forEach(
            (baseName, dirsToMerge) -> directories.put(baseName, merge(dirsToMerge, digestUtil)));

    return buildMerkleTree(files, directories, digestUtil);
  }

  private static MerkleTree buildMerkleTree(
      SortedSet<DirectoryTree.FileNode> files,
      SortedMap<String, MerkleTree> directories,
      DigestUtil digestUtil) {
    Directory.Builder b = Directory.newBuilder();
    for (DirectoryTree.FileNode file : files) {
      b.addFiles(buildProto(file));
    }
    for (Map.Entry<String, MerkleTree> nameAndDir : directories.entrySet()) {
      b.addDirectories(buildProto(nameAndDir.getKey(), nameAndDir.getValue()));
    }
    Directory protoDir = b.build();
    Digest protoDirDigest = digestUtil.compute(protoDir);

    long inputFiles = files.size();
    for (MerkleTree dir : directories.values()) {
      inputFiles += dir.getInputFiles();
    }

    long inputBytes = protoDirDigest.getSizeBytes();
    for (DirectoryTree.FileNode file : files) {
      inputBytes += file.getDigest().getSizeBytes();
    }
    for (MerkleTree dir : directories.values()) {
      inputBytes += dir.getInputBytes();
    }

    return new MerkleTree(protoDir, protoDirDigest, files, directories, inputFiles, inputBytes);
  }

  private static FileNode buildProto(DirectoryTree.FileNode file) {
    return FileNode.newBuilder()
        .setName(file.getPathSegment())
        .setDigest(file.getDigest())
        .setIsExecutable(file.isExecutable())
        .build();
  }

  private static DirectoryNode buildProto(String baseName, MerkleTree dir) {
    return DirectoryNode.newBuilder().setName(baseName).setDigest(dir.getRootDigest()).build();
  }

  private static PathOrBytes toPathOrBytes(DirectoryTree.FileNode file) {
    return file.getPath() != null
        ? new PathOrBytes(file.getPath())
        : new PathOrBytes(file.getBytes());
  }
}
