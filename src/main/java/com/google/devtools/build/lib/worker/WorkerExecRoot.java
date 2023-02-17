// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Creates and manages the contents of a working directory of a persistent worker. */
final class WorkerExecRoot {
  private final Path workDir;

  public WorkerExecRoot(Path workDir) {
    this.workDir = workDir;
  }

  public void createFileSystem(
      Set<PathFragment> workerFiles, SandboxInputs inputs, SandboxOutputs outputs)
      throws IOException {
    workDir.createDirectoryAndParents();

    // First compute all the inputs and directories that we need. This is based only on
    // `workerFiles`, `inputs` and `outputs` and won't do any I/O.
    Set<PathFragment> inputsToCreate = new LinkedHashSet<>();
    LinkedHashSet<PathFragment> dirsToCreate = new LinkedHashSet<>();
    SandboxHelpers.populateInputsAndDirsToCreate(
        ImmutableSet.of(),
        inputsToCreate,
        dirsToCreate,
        Iterables.concat(workerFiles, inputs.getFiles().keySet(), inputs.getSymlinks().keySet()),
        outputs.files(),
        outputs.dirs());

    // Then do a full traversal of the parent directory of `workDir`. This will use what we computed
    // above, delete anything unnecessary and update `inputsToCreate`/`dirsToCreate` if something is
    // can be left without changes (e.g., a symlink that already points to the right destination).
    // We're traversing from workDir's parent directory because external repositories can now be
    // symlinked as siblings of workDir when --experimental_sibling_repository_layout is in effect.
    SandboxHelpers.cleanExisting(
        workDir.getParentDirectory(), inputs, inputsToCreate, dirsToCreate, workDir);

    // Finally, create anything that is still missing. This is non-strict only for historical
    // reasons,
    // we haven't seen what would break if we make it strict.
    SandboxHelpers.createDirectories(dirsToCreate, workDir, /* strict=*/ false);
    createInputs(inputsToCreate, inputs, workDir);

    inputs.materializeVirtualInputs(workDir);
  }

  static void createInputs(Iterable<PathFragment> inputsToCreate, SandboxInputs inputs, Path dir)
      throws IOException {
    for (PathFragment fragment : inputsToCreate) {
      Path key = dir.getRelative(fragment);
      if (inputs.getFiles().containsKey(fragment)) {
        Path fileDest = inputs.getFiles().get(fragment);
        if (fileDest != null) {
          key.createSymbolicLink(fileDest);
        } else {
          FileSystemUtils.createEmptyFile(key);
        }
      } else if (inputs.getSymlinks().containsKey(fragment)) {
        PathFragment symlinkDest = inputs.getSymlinks().get(fragment);
        if (symlinkDest != null) {
          key.createSymbolicLink(symlinkDest);
        }
      }
    }
  }

  public void copyOutputs(Path execRoot, SandboxOutputs outputs) throws IOException {
    SandboxHelpers.moveOutputs(outputs, workDir, execRoot);
  }
}
