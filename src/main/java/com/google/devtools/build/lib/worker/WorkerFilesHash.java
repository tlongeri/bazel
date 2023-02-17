// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Calculates the hash based on the files, which should be unchanged on disk for a worker to get
 * reused.
 */
public class WorkerFilesHash {

  private WorkerFilesHash() {}

  public static HashCode getCombinedHash(SortedMap<PathFragment, byte[]> workerFilesMap) {
    Hasher hasher = Hashing.sha256().newHasher();
    workerFilesMap.forEach(
        (execPath, digest) -> {
          hasher.putString(execPath.getPathString(), Charset.defaultCharset());
          hasher.putBytes(digest);
        });
    return hasher.hash();
  }

  /**
   * Return a map that contains the execroot relative path and hash of each tool and runfiles
   * artifact of the given spawn.
   *
   * @throws MissingInputException if metadata is missing for any of the worker files.
   */
  public static SortedMap<PathFragment, byte[]> getWorkerFilesWithDigests(
      Spawn spawn, ArtifactExpander artifactExpander, MetadataProvider actionInputFileCache)
      throws IOException {
    TreeMap<PathFragment, byte[]> workerFilesMap = new TreeMap<>();

    List<ActionInput> tools =
        ActionInputHelper.expandArtifacts(
            spawn.getToolFiles(), artifactExpander, /* keepEmptyTreeArtifacts= */ false);
    for (ActionInput tool : tools) {
      @Nullable FileArtifactValue metadata = actionInputFileCache.getMetadata(tool);
      if (metadata == null) {
        throw new MissingInputException(tool);
      }
      workerFilesMap.put(tool.getExecPath(), actionInputFileCache.getMetadata(tool).getDigest());
    }

    for (Map.Entry<PathFragment, Map<PathFragment, Artifact>> rootAndMappings :
        spawn.getRunfilesSupplier().getMappings().entrySet()) {
      PathFragment root = rootAndMappings.getKey();
      Preconditions.checkState(!root.isAbsolute(), root);
      for (Map.Entry<PathFragment, Artifact> mapping : rootAndMappings.getValue().entrySet()) {
        Artifact localArtifact = mapping.getValue();
        if (localArtifact != null) {
          @Nullable FileArtifactValue metadata = actionInputFileCache.getMetadata(localArtifact);
          if (metadata == null) {
            throw new MissingInputException(localArtifact);
          }
          if (metadata.getType().isFile()) {
            workerFilesMap.put(root.getRelative(mapping.getKey()), metadata.getDigest());
          }
        }
      }
    }

    return workerFilesMap;
  }

  /** Exception thrown when the metadata for a tool/runfile is missing. */
  public static final class MissingInputException extends RuntimeException {
    private MissingInputException(ActionInput input) {
      super(String.format("Missing input metadata for: '%s'", input.getExecPathString()));
    }
  }
}
