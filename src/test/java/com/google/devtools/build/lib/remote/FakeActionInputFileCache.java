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

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.io.IOException;

/** A fake implementation of the {@link MetadataProvider} interface. */
final class FakeActionInputFileCache implements MetadataProvider {
  private final Path execRoot;
  private final BiMap<ActionInput, String> cas = HashBiMap.create();
  private final DigestUtil digestUtil;

  FakeActionInputFileCache(Path execRoot) {
    this.execRoot = execRoot;
    this.digestUtil =
        new DigestUtil(SyscallCache.NO_CACHE, execRoot.getFileSystem().getDigestFunction());
  }

  @Override
  public FileArtifactValue getMetadata(ActionInput input) throws IOException {
    String hexDigest = Preconditions.checkNotNull(cas.get(input), input);
    Path path = execRoot.getRelative(input.getExecPath());
    FileStatus stat = path.stat(Symlinks.FOLLOW);
    return FileArtifactValue.createForNormalFile(
        HashCode.fromString(hexDigest).asBytes(), FileContentsProxy.create(stat), stat.getSize());
  }

  @Override
  public ActionInput getInput(String execPath) {
    throw new UnsupportedOperationException();
  }

  private void setDigest(ActionInput input, String digest) {
    cas.put(input, digest);
  }

  public Digest createScratchInput(ActionInput input, String content) throws IOException {
    Path inputFile = execRoot.getRelative(input.getExecPath());
    inputFile.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(inputFile, content);
    Digest digest = digestUtil.compute(inputFile);
    setDigest(input, digest.getHash());
    return digest;
  }

  public Digest createScratchInputDirectory(ActionInput input, Tree content) throws IOException {
    Path inputFile = execRoot.getRelative(input.getExecPath());
    inputFile.createDirectoryAndParents();
    Digest digest = digestUtil.compute(content);
    setDigest(input, digest.getHash());
    return digest;
  }

  public Digest createScratchInputSymlink(ActionInput input, String target) throws IOException {
    Path inputFile = execRoot.getRelative(input.getExecPath());
    inputFile.getParentDirectory().createDirectoryAndParents();
    inputFile.createSymbolicLink(PathFragment.create(target));
    Digest digest = digestUtil.compute(inputFile);
    setDigest(input, digest.getHash());
    return digest;
  }
}
