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
package com.google.devtools.build.lib.exec;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.MissingExpansionException;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetManifest;
import com.google.devtools.build.lib.actions.FilesetManifest.ForbiddenRelativeSymlinkException;
import com.google.devtools.build.lib.actions.FilesetManifest.RelativeSymlinkBehavior;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A helper class for spawn strategies to turn runfiles suppliers into input mappings. This class
 * performs no I/O operations, but only rearranges the files according to how the runfiles should be
 * laid out.
 */
public class SpawnInputExpander {
  private final Path execRoot;
  private final boolean strict;
  private final RelativeSymlinkBehavior relSymlinkBehavior;

  /**
   * Creates a new instance. If strict is true, then the expander checks for directories in runfiles
   * and throws an exception if it finds any. Otherwise it silently ignores directories in runfiles
   * and adds a mapping for them. At this time, directories in filesets are always silently added as
   * mappings.
   *
   * <p>Directories in inputs are a correctness issue: Bazel only tracks dependencies at the action
   * level, and it does not track dependencies on directories. Making a directory available to a
   * spawn even though it's contents are not tracked as dependencies leads to incorrect incremental
   * builds, since changes to the contents do not trigger action invalidation.
   *
   * <p>As such, all spawn strategies should always be strict and not make directories available to
   * the subprocess. However, that's a breaking change, and therefore we make it depend on this flag
   * for now.
   */
  public SpawnInputExpander(Path execRoot, boolean strict) {
    this(execRoot, strict, RelativeSymlinkBehavior.ERROR);
  }

  /**
   * Creates a new instance. If strict is true, then the expander checks for directories in runfiles
   * and throws an exception if it finds any. Otherwise it silently ignores directories in runfiles
   * and adds a mapping for them. At this time, directories in filesets are always silently added as
   * mappings.
   *
   * <p>Directories in inputs are a correctness issue: Bazel only tracks dependencies at the action
   * level, and it does not track dependencies on directories. Making a directory available to a
   * spawn even though it's contents are not tracked as dependencies leads to incorrect incremental
   * builds, since changes to the contents do not trigger action invalidation.
   *
   * <p>As such, all spawn strategies should always be strict and not make directories available to
   * the subprocess. However, that's a breaking change, and therefore we make it depend on this flag
   * for now.
   */
  public SpawnInputExpander(
      Path execRoot, boolean strict, RelativeSymlinkBehavior relSymlinkBehavior) {
    this.execRoot = execRoot;
    this.strict = strict;
    this.relSymlinkBehavior = relSymlinkBehavior;
  }

  private static void addMapping(
      Map<PathFragment, ActionInput> inputMappings,
      PathFragment targetLocation,
      ActionInput input,
      PathFragment baseDirectory) {
    Preconditions.checkArgument(!targetLocation.isAbsolute(), targetLocation);
    inputMappings.put(baseDirectory.getRelative(targetLocation), input);
  }

  /** Adds runfiles inputs from runfilesSupplier to inputMappings. */
  @VisibleForTesting
  void addRunfilesToInputs(
      Map<PathFragment, ActionInput> inputMap,
      RunfilesSupplier runfilesSupplier,
      MetadataProvider actionFileCache,
      ArtifactExpander artifactExpander,
      PathFragment baseDirectory)
      throws IOException, ForbiddenActionInputException {
    Map<PathFragment, Map<PathFragment, Artifact>> rootsAndMappings =
        runfilesSupplier.getMappings();

    for (Map.Entry<PathFragment, Map<PathFragment, Artifact>> rootAndMappings :
        rootsAndMappings.entrySet()) {
      PathFragment root = rootAndMappings.getKey();
      Preconditions.checkState(!root.isAbsolute(), root);
      for (Map.Entry<PathFragment, Artifact> mapping : rootAndMappings.getValue().entrySet()) {
        PathFragment location = root.getRelative(mapping.getKey());
        Artifact localArtifact = mapping.getValue();
        if (localArtifact != null) {
          Preconditions.checkState(!localArtifact.isMiddlemanArtifact());
          if (localArtifact.isTreeArtifact()) {
            List<ActionInput> expandedInputs =
                ActionInputHelper.expandArtifacts(
                    NestedSetBuilder.create(Order.STABLE_ORDER, localArtifact), artifactExpander);
            for (ActionInput input : expandedInputs) {
              addMapping(
                  inputMap,
                  location.getRelative(((TreeFileArtifact) input).getParentRelativePath()),
                  input,
                  baseDirectory);
            }
          } else if (localArtifact.isFileset()) {
            ImmutableList<FilesetOutputSymlink> filesetLinks;
            try {
              filesetLinks = artifactExpander.getFileset(localArtifact);
            } catch (MissingExpansionException e) {
              throw new IllegalStateException(e);
            }
            addFilesetManifest(location, localArtifact, filesetLinks, inputMap, baseDirectory);
          } else {
            if (strict) {
              failIfDirectory(actionFileCache, localArtifact);
            }
            addMapping(inputMap, location, localArtifact, baseDirectory);
          }
        } else {
          addMapping(inputMap, location, VirtualActionInput.EMPTY_MARKER, baseDirectory);
        }
      }
    }
  }

  /** Adds runfiles inputs from runfilesSupplier to inputMappings. */
  public Map<PathFragment, ActionInput> addRunfilesToInputs(
      RunfilesSupplier runfilesSupplier,
      MetadataProvider actionFileCache,
      ArtifactExpander artifactExpander,
      PathFragment baseDirectory)
      throws IOException, ForbiddenActionInputException {
    Map<PathFragment, ActionInput> inputMap = new HashMap<>();
    addRunfilesToInputs(
        inputMap, runfilesSupplier, actionFileCache, artifactExpander, baseDirectory);
    return inputMap;
  }

  private static void failIfDirectory(MetadataProvider actionFileCache, ActionInput input)
      throws IOException, ForbiddenActionInputException {
    FileArtifactValue metadata = actionFileCache.getMetadata(input);
    if (metadata != null && !metadata.getType().isFile()) {
      throw new ForbiddenNonFileException(input);
    }
  }

  @VisibleForTesting
  void addFilesetManifests(
      Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings,
      Map<PathFragment, ActionInput> inputMappings,
      PathFragment baseDirectory)
      throws ForbiddenRelativeSymlinkException {
    for (Artifact fileset : filesetMappings.keySet()) {
      addFilesetManifest(
          fileset.getExecPath(),
          fileset,
          filesetMappings.get(fileset),
          inputMappings,
          baseDirectory);
    }
  }

  void addFilesetManifest(
      PathFragment location,
      Artifact filesetArtifact,
      ImmutableList<FilesetOutputSymlink> filesetLinks,
      Map<PathFragment, ActionInput> inputMappings,
      PathFragment baseDirectory)
      throws ForbiddenRelativeSymlinkException {
    Preconditions.checkState(filesetArtifact.isFileset(), filesetArtifact);
    FilesetManifest filesetManifest =
        FilesetManifest.constructFilesetManifest(filesetLinks, location, relSymlinkBehavior);

      for (Map.Entry<PathFragment, String> mapping : filesetManifest.getEntries().entrySet()) {
        String value = mapping.getValue();
      ActionInput artifact =
          value == null
              ? VirtualActionInput.EMPTY_MARKER
              : ActionInputHelper.fromPath(execRoot.getRelative(value).asFragment());
      addMapping(inputMappings, mapping.getKey(), artifact, baseDirectory);
      }
  }

  private static void addInputs(
      Map<PathFragment, ActionInput> inputMap,
      NestedSet<? extends ActionInput> inputFiles,
      ArtifactExpander artifactExpander,
      PathFragment baseDirectory) {
    List<ActionInput> inputs = ActionInputHelper.expandArtifacts(inputFiles, artifactExpander);
    for (ActionInput input : inputs) {
      addMapping(inputMap, input.getExecPath(), input, baseDirectory);
    }
  }

  /**
   * Convert the inputs and runfiles of the given spawn to a map from exec-root relative paths to
   * {@link ActionInput}s. The returned map does not contain tree artifacts as they are expanded to
   * file artifacts.
   *
   * <p>The returned map never contains {@code null} values.
   *
   * <p>The returned map contains all runfiles, but not the {@code MANIFEST}.
   */
  public SortedMap<PathFragment, ActionInput> getInputMapping(
      Spawn spawn,
      ArtifactExpander artifactExpander,
      PathFragment baseDirectory,
      MetadataProvider actionInputFileCache)
      throws IOException, ForbiddenActionInputException {
    TreeMap<PathFragment, ActionInput> inputMap = new TreeMap<>();
    addInputs(inputMap, spawn.getInputFiles(), artifactExpander, baseDirectory);
    addRunfilesToInputs(
        inputMap,
        spawn.getRunfilesSupplier(),
        actionInputFileCache,
        artifactExpander,
        baseDirectory);
    addFilesetManifests(spawn.getFilesetMappings(), inputMap, baseDirectory);
    return inputMap;
  }

  /** The interface for accessing part of the input hierarchy. */
  public interface InputWalker {
    SortedMap<PathFragment, ActionInput> getLeavesInputMapping()
        throws IOException, ForbiddenActionInputException;

    void visitNonLeaves(InputVisitor visitor) throws IOException, ForbiddenActionInputException;
  }

  /** The interface for visiting part of the input hierarchy. */
  public interface InputVisitor {
    /**
     * Visits a part of the input hierarchy.
     *
     * <p>{@code nodeKey} can be used as key when memoizing visited parts of the hierarchy.
     */
    void visit(Object nodeKey, InputWalker walker)
        throws IOException, ForbiddenActionInputException;
  }

  /**
   * Visits the input files hierarchy in a depth first manner.
   *
   * <p>Similar to {@link #getInputMapping} but allows for early exit, by not visiting children,
   * when walking through the input hierarchy. By applying memoization, the retrieval process of the
   * inputs can be speeded up.
   *
   * <p>{@code baseDirectory} is prepended to every path in the input key. This is useful if the
   * mapping is used in a context where the directory relative to which the keys are interpreted is
   * not the same as the execroot.
   */
  public void walkInputs(
      Spawn spawn,
      ArtifactExpander artifactExpander,
      PathFragment baseDirectory,
      MetadataProvider actionInputFileCache,
      InputVisitor visitor)
      throws IOException, ForbiddenActionInputException {
    walkNestedSetInputs(baseDirectory, spawn.getInputFiles(), artifactExpander, visitor);

    RunfilesSupplier runfilesSupplier = spawn.getRunfilesSupplier();
    visitor.visit(
        // The list of variables affecting the functional expressions below.
        Arrays.asList(
            // Assuming that artifactExpander and actionInputFileCache, different for each spawn,
            // always expand the same way.
            this, // For accessing addRunfilesToInputs.
            runfilesSupplier,
            baseDirectory),
        new InputWalker() {
          @Override
          public SortedMap<PathFragment, ActionInput> getLeavesInputMapping()
              throws IOException, ForbiddenActionInputException {
            TreeMap<PathFragment, ActionInput> inputMap = new TreeMap<>();
            addRunfilesToInputs(
                inputMap, runfilesSupplier, actionInputFileCache, artifactExpander, baseDirectory);
            return inputMap;
          }

          @Override
          public void visitNonLeaves(InputVisitor childVisitor) {}
        });

    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings = spawn.getFilesetMappings();
    // filesetMappings is assumed to be very small, so no need to implement visitNonLeaves() for
    // improved runtime.
    visitor.visit(
        // The list of variables affecting the functional expressions below.
        Arrays.asList(
            this, // For accessing addFilesetManifests.
            filesetMappings,
            baseDirectory),
        new InputWalker() {
          @Override
          public SortedMap<PathFragment, ActionInput> getLeavesInputMapping()
              throws ForbiddenRelativeSymlinkException {
            TreeMap<PathFragment, ActionInput> inputMap = new TreeMap<>();
            addFilesetManifests(filesetMappings, inputMap, baseDirectory);
            return inputMap;
          }

          @Override
          public void visitNonLeaves(InputVisitor childVisitor) {}
        });
  }

  /** Walks through one level of a {@link NestedSet} of {@link ActionInput}s. */
  private void walkNestedSetInputs(
      PathFragment baseDirectory,
      NestedSet<? extends ActionInput> someInputFiles,
      ArtifactExpander artifactExpander,
      InputVisitor visitor)
      throws IOException, ForbiddenActionInputException {
    visitor.visit(
        // addInputs is static so no need to add 'this' as dependent key.
        Arrays.asList(
            // Assuming that artifactExpander, different for each spawn, always expands the same
            // way.
            someInputFiles.toNode(), baseDirectory),
        new InputWalker() {
          @Override
          public SortedMap<PathFragment, ActionInput> getLeavesInputMapping() {
            TreeMap<PathFragment, ActionInput> inputMap = new TreeMap<>();
            addInputs(
                inputMap,
                NestedSetBuilder.wrap(someInputFiles.getOrder(), someInputFiles.getLeaves()),
                artifactExpander,
                baseDirectory);
            return inputMap;
          }

          @Override
          public void visitNonLeaves(InputVisitor childVisitor)
              throws IOException, ForbiddenActionInputException {
            for (NestedSet<? extends ActionInput> subInputs : someInputFiles.getNonLeaves()) {
              walkNestedSetInputs(baseDirectory, subInputs, artifactExpander, childVisitor);
            }
          }
        });
  }

  /**
   * Exception signaling that an input was not a regular file: most likely a directory. This
   * exception is currently never thrown in practice since we do not enforce "strict" mode.
   */
  private static final class ForbiddenNonFileException extends ForbiddenActionInputException {
    ForbiddenNonFileException(ActionInput input) {
      super("Not a file: " + input.getExecPathString());
    }
  }
}
