// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.OwnerlessArtifactWrapper;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.rules.cpp.IncludeScannable;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue.ArchivedRepresentation;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/** A value representing an executed action. */
@Immutable
@ThreadSafe
public final class ActionExecutionValue implements SkyValue {

  /** A map from each output artifact of this action to their {@link FileArtifactValue}s. */
  private final ImmutableMap<Artifact, FileArtifactValue> artifactData;

  /** The TreeArtifactValue of all TreeArtifacts output by this Action. */
  private final ImmutableMap<Artifact, TreeArtifactValue> treeArtifactData;

  @Nullable private final ImmutableList<FilesetOutputSymlink> outputSymlinks;

  @Nullable private final NestedSet<Artifact> discoveredModules;

  /**
   * @param artifactData Map from Artifacts to corresponding {@link FileArtifactValue}.
   * @param treeArtifactData All tree artifact data.
   * @param outputSymlinks This represents the SymlinkTree which is the output of a fileset action.
   * @param discoveredModules cpp modules discovered
   */
  private ActionExecutionValue(
      ImmutableMap<Artifact, FileArtifactValue> artifactData,
      ImmutableMap<Artifact, TreeArtifactValue> treeArtifactData,
      @Nullable ImmutableList<FilesetOutputSymlink> outputSymlinks,
      @Nullable NestedSet<Artifact> discoveredModules) {
    for (Map.Entry<Artifact, FileArtifactValue> entry : artifactData.entrySet()) {
      Preconditions.checkArgument(
          !entry.getKey().isChildOfDeclaredDirectory(),
          "%s should only be stored in a TreeArtifactValue",
          entry.getKey());
      if (entry.getValue().getType().isFile()) {
        Preconditions.checkNotNull(
            entry.getValue().getDigest(), "missing digest for %s", entry.getKey());
      }
    }

    for (Map.Entry<Artifact, TreeArtifactValue> tree : treeArtifactData.entrySet()) {
      TreeArtifactValue treeArtifact = tree.getValue();
      if (TreeArtifactValue.OMITTED_TREE_MARKER.equals(treeArtifact)) {
        continue;
      }
      for (Map.Entry<TreeFileArtifact, FileArtifactValue> file :
          treeArtifact.getChildValues().entrySet()) {
        // Tree artifacts can contain symlinks to directories, which don't have a digest.
        if (file.getValue().getType().isFile()) {
          Preconditions.checkNotNull(
              file.getValue().getDigest(),
              "missing digest for file %s in tree artifact %s",
              file.getKey(),
              tree.getKey());
        }
      }
    }

    this.artifactData = artifactData;
    this.treeArtifactData = treeArtifactData;
    this.outputSymlinks = outputSymlinks;
    this.discoveredModules = discoveredModules;
  }

  static ActionExecutionValue createFromOutputStore(
      OutputStore outputStore,
      @Nullable ImmutableList<FilesetOutputSymlink> outputSymlinks,
      Action action) {
    return new ActionExecutionValue(
        outputStore.getAllArtifactData(),
        outputStore.getAllTreeArtifactData(),
        outputSymlinks,
        action instanceof IncludeScannable
            ? ((IncludeScannable) action).getDiscoveredModules()
            : null);
  }

  @VisibleForTesting
  public static ActionExecutionValue createForTesting(
      ImmutableMap<Artifact, FileArtifactValue> artifactData,
      ImmutableMap<Artifact, TreeArtifactValue> treeArtifactData,
      @Nullable ImmutableList<FilesetOutputSymlink> outputSymlinks) {
    return new ActionExecutionValue(
        artifactData, treeArtifactData, outputSymlinks, /*discoveredModules=*/ null);
  }

  /**
   * Retrieves a {@link FileArtifactValue} for a regular (non-tree) derived artifact.
   *
   * <p>The value for the given artifact must be present, or else {@link NullPointerException} will
   * be thrown.
   */
  public FileArtifactValue getExistingFileArtifactValue(Artifact artifact) {
    Preconditions.checkArgument(
        artifact instanceof DerivedArtifact && !artifact.isTreeArtifact(),
        "Cannot request %s from %s",
        artifact,
        this);

    FileArtifactValue result;
    if (artifact.isChildOfDeclaredDirectory()) {
      TreeArtifactValue tree = treeArtifactData.get(artifact.getParent());
      result = tree == null ? null : tree.getChildValues().get(artifact);
    } else if (artifact instanceof ArchivedTreeArtifact) {
      TreeArtifactValue tree = treeArtifactData.get(artifact.getParent());
      ArchivedRepresentation archivedRepresentation =
          tree.getArchivedRepresentation()
              .orElseThrow(
                  () -> new NoSuchElementException("Missing archived representation in: " + tree));
      Preconditions.checkArgument(
          archivedRepresentation.archivedTreeFileArtifact().equals(artifact),
          "Multiple archived tree artifacts for: %s",
          artifact.getParent());
      result = archivedRepresentation.archivedFileValue();
    } else {
      result = artifactData.get(artifact);
    }

    return Preconditions.checkNotNull(
        result,
        "Missing artifact %s (generating action key %s) in %s",
        artifact,
        ((DerivedArtifact) artifact).getGeneratingActionKey(),
        this);
  }

  TreeArtifactValue getTreeArtifactValue(Artifact artifact) {
    Preconditions.checkArgument(artifact.isTreeArtifact());
    return treeArtifactData.get(artifact);
  }

  /**
   * Returns a map containing all artifacts output by the action, except for tree artifacts which
   * are accesible via {@link #getAllTreeArtifactValues}.
   *
   * <p>Primarily needed by {@link FilesystemValueChecker}. Also called by {@link ArtifactFunction}
   * when aggregating a {@link TreeArtifactValue} out of action template expansion outputs.
   */
  // Visible only for testing: should be package-private.
  public ImmutableMap<Artifact, FileArtifactValue> getAllFileValues() {
    return artifactData;
  }

  /**
   * Returns a map containing all tree artifacts output by the action.
   *
   * <p>Should only be needed by {@link FilesystemValueChecker}.
   */
  // Visible only for testing: should be package-private.
  public ImmutableMap<Artifact, TreeArtifactValue> getAllTreeArtifactValues() {
    return treeArtifactData;
  }

  @Nullable
  public ImmutableList<FilesetOutputSymlink> getOutputSymlinks() {
    return outputSymlinks;
  }

  @Nullable
  public NestedSet<Artifact> getDiscoveredModules() {
    return discoveredModules;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("artifactData", artifactData)
        .add("treeArtifactData", treeArtifactData)
        .toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ActionExecutionValue)) {
      return false;
    }
    ActionExecutionValue o = (ActionExecutionValue) obj;
    return artifactData.equals(o.artifactData)
        && treeArtifactData.equals(o.treeArtifactData)
        && Objects.equal(outputSymlinks, o.outputSymlinks);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(artifactData, treeArtifactData, outputSymlinks);
  }

  private static <V> ImmutableMap<Artifact, V> transformMap(
      ImmutableMap<Artifact, V> data,
      Map<OwnerlessArtifactWrapper, Artifact> newArtifactMap,
      Action action,
      BiFunction<Artifact, V, V> transform) {
    if (data.isEmpty()) {
      return data;
    }

    ImmutableMap.Builder<Artifact, V> result = ImmutableMap.builderWithExpectedSize(data.size());
    for (Map.Entry<Artifact, V> entry : data.entrySet()) {
      Artifact artifact = entry.getKey();
      Artifact newArtifact =
          Preconditions.checkNotNull(
              newArtifactMap.get(new OwnerlessArtifactWrapper(artifact)),
              "No output matching %s, cannot share with %s",
              artifact,
              action);
      result.put(newArtifact, transform.apply(newArtifact, entry.getValue()));
    }
    return result.build();
  }

  /** Transforms the children of a {@link TreeArtifactValue} so that owners are consistent. */
  private static TreeArtifactValue transformSharedTree(
      Artifact newArtifact, TreeArtifactValue tree) {
    Preconditions.checkState(
        newArtifact.isTreeArtifact(), "Expected tree artifact, got %s", newArtifact);

    if (TreeArtifactValue.OMITTED_TREE_MARKER.equals(tree)) {
      return TreeArtifactValue.OMITTED_TREE_MARKER;
    }

    SpecialArtifact newParent = (SpecialArtifact) newArtifact;
    TreeArtifactValue.Builder newTree = TreeArtifactValue.newBuilder(newParent);

    for (Map.Entry<TreeFileArtifact, FileArtifactValue> child : tree.getChildValues().entrySet()) {
      newTree.putChild(
          TreeFileArtifact.createTreeOutput(newParent, child.getKey().getParentRelativePath()),
          child.getValue());
    }

    tree.getArchivedRepresentation()
        .ifPresent(
            archivedRepresentation ->
                newTree.setArchivedRepresentation(
                    ArchivedTreeArtifact.createForTree(newParent),
                    archivedRepresentation.archivedFileValue()));

    return newTree.build();
  }

  /**
   * Creates a new {@code ActionExecutionValue} by transforming this one's outputs so that artifact
   * owners match the given action's outputs.
   *
   * <p>The given action must be {@linkplain
   * com.google.devtools.build.lib.actions.Actions#canBeShared shareable} with the action that
   * originally produced this {@code ActionExecutionValue}.
   */
  public ActionExecutionValue transformForSharedAction(Action action) {
    Preconditions.checkArgument(
        action.getOutputs().size() == artifactData.size() + treeArtifactData.size(),
        "Cannot share %s with %s",
        this,
        action);
    Map<OwnerlessArtifactWrapper, Artifact> newArtifactMap =
        Maps.uniqueIndex(action.getOutputs(), OwnerlessArtifactWrapper::new);
    return new ActionExecutionValue(
        transformMap(artifactData, newArtifactMap, action, (newArtifact, value) -> value),
        transformMap(
            treeArtifactData, newArtifactMap, action, ActionExecutionValue::transformSharedTree),
        outputSymlinks,
        // Discovered modules come from the action's inputs, and so don't need to be transformed.
        discoveredModules);
  }
}
