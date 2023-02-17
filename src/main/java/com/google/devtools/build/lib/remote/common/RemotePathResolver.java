// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.exec.SpawnInputExpander;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.SortedMap;

/**
 * A {@link RemotePathResolver} is used to resolve input/output paths for remote execution from
 * Bazel's internal path, or vice versa.
 */
public interface RemotePathResolver {

  /**
   * Returns the {@code workingDirectory} for a remote action. Empty string if working directory is
   * the input root.
   */
  String getWorkingDirectory();

  /**
   * Returns a {@link SortedMap} which maps from input paths for remote action to {@link
   * ActionInput}.
   */
  SortedMap<PathFragment, ActionInput> getInputMapping(SpawnExecutionContext context)
      throws IOException, ForbiddenActionInputException;

  void walkInputs(
      Spawn spawn, SpawnExecutionContext context, SpawnInputExpander.InputVisitor visitor)
      throws IOException, ForbiddenActionInputException;

  /** Resolves the output path relative to input root for the given {@link Path}. */
  String localPathToOutputPath(Path path);

  /**
   * Resolves the output path relative to input root for the given {@link PathFragment}.
   *
   * @param execPath a path fragment relative to {@code execRoot}.
   */
  String localPathToOutputPath(PathFragment execPath);

  /** Resolves the output path relative to input root for the {@link ActionInput}. */
  default String localPathToOutputPath(ActionInput actionInput) {
    return localPathToOutputPath(actionInput.getExecPath());
  }

  /**
   * Resolves the local {@link Path} of an output file.
   *
   * @param outputPath the return value of {@link #localPathToOutputPath(PathFragment)}.
   */
  Path outputPathToLocalPath(String outputPath);

  /** Resolves the local {@link Path} for the {@link ActionInput}. */
  default Path outputPathToLocalPath(ActionInput actionInput) {
    String outputPath = localPathToOutputPath(actionInput.getExecPath());
    return outputPathToLocalPath(outputPath);
  }

  /** Creates the default {@link RemotePathResolver}. */
  static RemotePathResolver createDefault(Path execRoot) {
    return new DefaultRemotePathResolver(execRoot);
  }

  /**
   * The default {@link RemotePathResolver} which use {@code execRoot} as input root and do NOT set
   * {@code workingDirectory} for remote actions.
   */
  class DefaultRemotePathResolver implements RemotePathResolver {

    private final Path execRoot;

    public DefaultRemotePathResolver(Path execRoot) {
      this.execRoot = execRoot;
    }

    @Override
    public String getWorkingDirectory() {
      return "";
    }

    @Override
    public SortedMap<PathFragment, ActionInput> getInputMapping(SpawnExecutionContext context)
        throws IOException, ForbiddenActionInputException {
      return context.getInputMapping(PathFragment.EMPTY_FRAGMENT);
    }

    @Override
    public void walkInputs(
        Spawn spawn, SpawnExecutionContext context, SpawnInputExpander.InputVisitor visitor)
        throws IOException, ForbiddenActionInputException {
      context
          .getSpawnInputExpander()
          .walkInputs(
              spawn,
              context.getArtifactExpander(),
              PathFragment.EMPTY_FRAGMENT,
              context.getMetadataProvider(),
              visitor);
    }

    @Override
    public String localPathToOutputPath(Path path) {
      return path.relativeTo(execRoot).getPathString();
    }

    @Override
    public String localPathToOutputPath(PathFragment execPath) {
      return execPath.getPathString();
    }

    @Override
    public Path outputPathToLocalPath(String outputPath) {
      return execRoot.getRelative(outputPath);
    }

    @Override
    public Path outputPathToLocalPath(ActionInput actionInput) {
      return ActionInputHelper.toInputPath(actionInput, execRoot);
    }
  }

  /**
   * A {@link RemotePathResolver} used when {@code --experimental_sibling_repository_layout} is set.
   * Use parent directory of {@code execRoot} and set {@code workingDirectory} to the base name of
   * {@code execRoot}.
   *
   * <p>The paths of outputs are relative to {@code workingDirectory} if {@code
   * --incompatible_remote_output_paths_relative_to_input_root} is not set, otherwise, relative to
   * input root.
   */
  class SiblingRepositoryLayoutResolver implements RemotePathResolver {

    private final Path execRoot;
    private final boolean incompatibleRemoteOutputPathsRelativeToInputRoot;

    public SiblingRepositoryLayoutResolver(Path execRoot) {
      this(execRoot, /* incompatibleRemoteOutputPathsRelativeToInputRoot= */ false);
    }

    public SiblingRepositoryLayoutResolver(
        Path execRoot, boolean incompatibleRemoteOutputPathsRelativeToInputRoot) {
      this.execRoot = execRoot;
      this.incompatibleRemoteOutputPathsRelativeToInputRoot =
          incompatibleRemoteOutputPathsRelativeToInputRoot;
    }

    @Override
    public String getWorkingDirectory() {
      return execRoot.getBaseName();
    }

    @Override
    public SortedMap<PathFragment, ActionInput> getInputMapping(SpawnExecutionContext context)
        throws IOException, ForbiddenActionInputException {
      // The "root directory" of the action from the point of view of RBE is the parent directory of
      // the execroot locally. This is so that paths of artifacts in external repositories don't
      // start with an uplevel reference.
      return context.getInputMapping(PathFragment.create(checkNotNull(getWorkingDirectory())));
    }

    @Override
    public void walkInputs(
        Spawn spawn, SpawnExecutionContext context, SpawnInputExpander.InputVisitor visitor)
        throws IOException, ForbiddenActionInputException {
      context
          .getSpawnInputExpander()
          .walkInputs(
              spawn,
              context.getArtifactExpander(),
              PathFragment.create(checkNotNull(getWorkingDirectory())),
              context.getMetadataProvider(),
              visitor);
    }

    private Path getBase() {
      if (incompatibleRemoteOutputPathsRelativeToInputRoot) {
        return execRoot.getParentDirectory();
      } else {
        return execRoot;
      }
    }

    @Override
    public String localPathToOutputPath(Path path) {
      return path.relativeTo(getBase()).getPathString();
    }

    @Override
    public String localPathToOutputPath(PathFragment execPath) {
      return localPathToOutputPath(execRoot.getRelative(execPath));
    }

    @Override
    public Path outputPathToLocalPath(String outputPath) {
      return getBase().getRelative(outputPath);
    }

    @Override
    public Path outputPathToLocalPath(ActionInput actionInput) {
      return ActionInputHelper.toInputPath(actionInput, execRoot);
    }
  }
}
