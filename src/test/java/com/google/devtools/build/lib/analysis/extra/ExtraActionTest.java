// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.extra;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.JavaCompileInfo;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.NullAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.exec.BlazeExecutor;
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Unit tests for ExtraAction class.
 */
@RunWith(JUnit4.class)
public class ExtraActionTest extends FoundationTestCase {

  private final ActionKeyContext actionKeyContext = new ActionKeyContext();

  private static class SpecifiedInfoAction extends NullAction {
    private final ExtraActionInfo info;

    private SpecifiedInfoAction(ExtraActionInfo info) {
      this.info = info;
    }

    @Override
    public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext) {
      return info.toBuilder();
    }
  }

  @Test
  public void testExtraActionInfoAffectsMnemonic() throws Exception {
    ExtraActionInfo infoOne = ExtraActionInfo.newBuilder()
        .setExtension(
            JavaCompileInfo.javaCompileInfo,
            JavaCompileInfo.newBuilder().addSourceFile("one").build())
        .build();

    ExtraActionInfo infoTwo = ExtraActionInfo.newBuilder()
        .setExtension(
            JavaCompileInfo.javaCompileInfo,
            JavaCompileInfo.newBuilder().addSourceFile("two").build())
        .build();

    Path execRoot = scratch.getFileSystem().getPath("/");
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");
    Artifact output = ActionsTestUtil.createArtifact(root, scratch.file("/out/test.out"));
    Action actionOne = new ExtraActionInfoFileWriteAction(ActionsTestUtil.NULL_ACTION_OWNER, output,
        new SpecifiedInfoAction(infoOne));
    Action actionTwo = new ExtraActionInfoFileWriteAction(ActionsTestUtil.NULL_ACTION_OWNER, output,
        new SpecifiedInfoAction(infoTwo));

    assertThat(actionOne.getKey(actionKeyContext, /*artifactExpander=*/ null))
        .isNotEqualTo(actionTwo.getKey(actionKeyContext, /*artifactExpander=*/ null));
  }

  /**
   * Regression test. The Spawn created for extra actions needs to pass the environment of the extra
   * action by getting the result of SpawnAction.getEnvironment() method instead of relying on the
   * default field value of BaseSpawn.environment.
   */
  @Test
  public void testEnvironmentPassedOnOverwrite() throws Exception {
    Path execRoot = scratch.getFileSystem().getPath("/");
    ArtifactRoot out = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");
    ExtraAction extraAction =
        new ExtraAction(
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            EmptyRunfilesSupplier.INSTANCE,
            ImmutableSet.of(
                (Artifact.DerivedArtifact)
                    ActionsTestUtil.createArtifact(out, scratch.file("/out/test.out"))),
            new NullAction(),
            false,
            CommandLine.of(Arrays.asList("one", "two", "thee")),
            ActionEnvironment.create(ImmutableMap.of("TEST", "TEST_VALUE")),
            ImmutableMap.of(),
            "Executing extra action bla bla",
            "bla bla");

    final Map<String, String> spawnEnvironment = new HashMap<>();
    SpawnStrategy fakeSpawnStrategy =
        new SpawnStrategy() {
          @Override
          public ImmutableList<SpawnResult> exec(
              Spawn spawn, ActionExecutionContext actionExecutionContext) {
            spawnEnvironment.putAll(spawn.getEnvironment());
            return ImmutableList.of();
          }

          @Override
          public boolean canExec(
              Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
            return true;
          }
        };

    BlazeExecutor testExecutor =
        new TestExecutorBuilder(fileSystem, execRoot, null)
            .addStrategy(fakeSpawnStrategy, "fake")
            .setDefaultStrategies("fake")
            .build();

    ActionResult actionResult =
        extraAction.execute(
            new ActionExecutionContext(
                testExecutor,
                /*actionInputFileCache=*/ null,
                ActionInputPrefetcher.NONE,
                actionKeyContext,
                /*metadataHandler=*/ null,
                /*rewindingEnabled=*/ false,
                LostInputsCheck.NONE,
                /*fileOutErr=*/ null,
                /*eventHandler=*/ null,
                /*clientEnv=*/ ImmutableMap.of(),
                /*topLevelFilesets=*/ ImmutableMap.of(),
                /*artifactExpander=*/ null,
                /*actionFileSystem=*/ null,
                /*skyframeDepsResult=*/ null,
                DiscoveredModulesPruner.DEFAULT,
                SyscallCache.NO_CACHE,
                ThreadStateReceiver.NULL_INSTANCE));
    assertThat(actionResult.spawnResults()).isEmpty();
    assertThat(spawnEnvironment.get("TEST")).isNotNull();
    assertThat(spawnEnvironment).containsEntry("TEST", "TEST_VALUE");
  }

  @Test
  public void testUpdateInputsNotPassedToShadowedAction() throws Exception {
    Path execRoot = scratch.getFileSystem().getPath("/");
    ArtifactRoot out = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");
    ArtifactRoot src = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/src")));
    Artifact extraIn = ActionsTestUtil.createArtifact(src, scratch.file("/src/extra.in"));
    Artifact discoveredIn = ActionsTestUtil.createArtifact(src, scratch.file("/src/discovered.in"));
    Action shadowedAction = mock(Action.class);
    when(shadowedAction.discoversInputs()).thenReturn(true);
    when(shadowedAction.getInputs()).thenReturn(NestedSetBuilder.emptySet(Order.STABLE_ORDER));
    when(shadowedAction.inputsDiscovered()).thenReturn(true);
    when(shadowedAction.getOwner()).thenReturn(NULL_ACTION_OWNER);
    when(shadowedAction.getRunfilesSupplier()).thenReturn(EmptyRunfilesSupplier.INSTANCE);
    ExtraAction extraAction =
        new ExtraAction(
            NestedSetBuilder.create(Order.STABLE_ORDER, extraIn),
            EmptyRunfilesSupplier.INSTANCE,
            ImmutableSet.of(
                (Artifact.DerivedArtifact)
                    ActionsTestUtil.createArtifact(out, scratch.file("/out/test.out"))),
            shadowedAction,
            false,
            CommandLine.of(ImmutableList.of()),
            ActionEnvironment.EMPTY,
            ImmutableMap.of(),
            "Executing extra action bla bla",
            "bla bla");
    extraAction.updateInputs(NestedSetBuilder.create(Order.STABLE_ORDER, extraIn, discoveredIn));
    verify(shadowedAction, Mockito.never()).updateInputs(ArgumentMatchers.any());
  }
}
