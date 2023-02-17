// Copyright 2022 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.worker.WorkerFilesHash.MissingInputException;
import java.io.IOException;
import java.util.SortedMap;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link WorkerFilesHash}. */
@RunWith(JUnit4.class)
public final class WorkerFilesHashTest {

  private final ArtifactRoot outputRoot =
      ArtifactRoot.asDerivedRoot(new Scratch().resolve("/execroot"), RootType.Output, "bazel-out");

  @Test
  public void getWorkerFilesWithDigests_returnsToolsWithCorrectDigests() throws Exception {
    byte[] tool1Digest = "text1".getBytes(UTF_8);
    byte[] tool2Digest = "text2".getBytes(UTF_8);
    MetadataProvider metadataProvider =
        createMetadataProvider(
            ImmutableMap.of(
                "tool1", fileArtifactValue(tool1Digest), "tool2", fileArtifactValue(tool2Digest)));
    Spawn spawn =
        new SpawnBuilder()
            .withTool(ActionInputHelper.fromPath("tool1"))
            .withTool(ActionInputHelper.fromPath("tool2"))
            .build();

    SortedMap<PathFragment, byte[]> filesWithDigests =
        WorkerFilesHash.getWorkerFilesWithDigests(
            spawn, (ignored1, ignored2) -> {}, metadataProvider);

    assertThat(filesWithDigests)
        .containsExactly(
            PathFragment.create("tool1"), tool1Digest, PathFragment.create("tool2"), tool2Digest)
        .inOrder();
  }

  @Test
  public void getWorkerFilesWithDigests_treeArtifactTool_returnsExpanded() throws Exception {
    SpecialArtifact tree = createTreeArtifact("tree");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(tree, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(tree, "child2");
    byte[] child1Digest = "text1".getBytes(UTF_8);
    byte[] child2Digest = "text2".getBytes(UTF_8);
    MetadataProvider metadataProvider =
        createMetadataProvider(
            ImmutableMap.of(
                child1.getExecPathString(),
                fileArtifactValue(child1Digest),
                child2.getExecPathString(),
                fileArtifactValue(child2Digest)));
    Spawn spawn = new SpawnBuilder().withTool(tree).build();
    ArtifactExpander expander =
        (artifact, output) -> {
          if (artifact.equals(tree)) {
            output.add(TreeFileArtifact.createTreeOutput(tree, "child1"));
            output.add(TreeFileArtifact.createTreeOutput(tree, "child2"));
          }
        };

    SortedMap<PathFragment, byte[]> filesWithDigests =
        WorkerFilesHash.getWorkerFilesWithDigests(spawn, expander, metadataProvider);

    assertThat(filesWithDigests)
        .containsExactly(child1.getExecPath(), child1Digest, child2.getExecPath(), child2Digest)
        .inOrder();
  }

  @Test
  public void getWorkerFilesWithDigests_spawnWithInputsButNoTools_returnsEmpty() throws Exception {
    MetadataProvider metadataProvider = createMetadataProvider(ImmutableMap.of());
    Spawn spawn = new SpawnBuilder().withInputs("file1", "file2").build();

    SortedMap<PathFragment, byte[]> filesWithDigests =
        WorkerFilesHash.getWorkerFilesWithDigests(
            spawn, (ignored1, ignored2) -> {}, metadataProvider);

    assertThat(filesWithDigests).isEmpty();
  }

  @Test
  public void getWorkerFilesWithDigests_missingDigestForTool_fails() {
    MetadataProvider metadataProvider = createMetadataProvider(ImmutableMap.of());
    Spawn spawn = new SpawnBuilder().withTool(ActionInputHelper.fromPath("tool")).build();

    assertThrows(
        MissingInputException.class,
        () ->
            WorkerFilesHash.getWorkerFilesWithDigests(
                spawn, (ignored1, ignored2) -> {}, metadataProvider));
  }

  @Test
  public void getWorkerFilesWithDigests_ioExceptionForToolMetadata_fails() {
    IOException injected = new IOException("oh no");
    MetadataProvider metadataProvider = createMetadataProvider(ImmutableMap.of("tool", injected));
    Spawn spawn = new SpawnBuilder().withTool(ActionInputHelper.fromPath("tool")).build();

    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                WorkerFilesHash.getWorkerFilesWithDigests(
                    spawn, (ignored1, ignored2) -> {}, metadataProvider));

    assertThat(thrown).isSameInstanceAs(injected);
  }

  private static MetadataProvider createMetadataProvider(
      ImmutableMap<String, Object> inputMetadataOrExceptions) {
    return new MetadataProvider() {
      @Nullable
      @Override
      public FileArtifactValue getMetadata(ActionInput input) throws IOException {
        @Nullable
        Object metadataOrException = inputMetadataOrExceptions.get(input.getExecPathString());
        if (metadataOrException == null) {
          return null;
        }
        if (metadataOrException instanceof IOException) {
          throw (IOException) metadataOrException;
        }
        if (metadataOrException instanceof FileArtifactValue) {
          return (FileArtifactValue) metadataOrException;
        }
        throw new AssertionError("Unexpected value: " + metadataOrException);
      }

      @Nullable
      @Override
      public ActionInput getInput(String execPath) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static FileArtifactValue fileArtifactValue(byte[] digest) {
    FileArtifactValue value = mock(FileArtifactValue.class);
    when(value.getDigest()).thenReturn(digest);
    return value;
  }

  private SpecialArtifact createTreeArtifact(String rootRelativePath) {
    return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
        outputRoot, outputRoot.getExecPath().getRelative(rootRelativePath));
  }
}
