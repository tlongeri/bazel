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
package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionContinuationOrResult;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SpawnContinuation;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link AbstractFileWriteAction}. */
@RunWith(TestParameterInjector.class)
public final class AbstractFileWriteActionTest {
  @Test
  public void beginExecution_delegatesToFileWriteActionContext(
      @TestParameter boolean executable, @TestParameter boolean isRemotable) throws Exception {
    DeterministicWriter writer = ignored -> {};
    AbstractFileWriteAction action = new TestFileWriteAction(writer, executable, isRemotable);
    FileWriteActionContext fileWriteContext = mock(FileWriteActionContext.class);
    ActionExecutionContext actionExecutionContext =
        createMockActionExecutionContext(fileWriteContext);

    action.beginExecution(actionExecutionContext);

    verify(fileWriteContext)
        .beginWriteOutputToFile(action, actionExecutionContext, writer, executable, isRemotable);
  }

  @Test
  public void executeAction_successfulWrite_callsAfterWrite() throws Exception {
    DeterministicWriter writer = ignored -> {};
    AbstractFileWriteAction action =
        spy(new TestFileWriteAction(writer, /*executable=*/ false, /*isRemotable=*/ false));
    FileWriteActionContext fileWriteContext = mock(FileWriteActionContext.class);
    ActionExecutionContext actionExecutionContext =
        createMockActionExecutionContext(fileWriteContext);
    SpawnResult success =
        new SpawnResult.Builder().setRunnerName("test").setStatus(Status.SUCCESS).build();
    when(fileWriteContext.beginWriteOutputToFile(
            action,
            actionExecutionContext,
            writer,
            /*makeExecutable=*/ false,
            /*isRemotable=*/ false))
        .thenReturn(SpawnContinuation.immediate(success));

    ActionResult result = action.beginExecution(actionExecutionContext).execute().get();

    assertThat(result.spawnResults()).containsExactly(success);
    verify(action).afterWrite(actionExecutionContext);
  }

  @Test
  public void executeAction_failedWrite_doesNotCallAfterWrite() throws Exception {
    DeterministicWriter writer = ignored -> {};
    AbstractFileWriteAction action =
        spy(new TestFileWriteAction(writer, /*executable=*/ false, /*isRemotable=*/ false));
    FileWriteActionContext fileWriteContext = mock(FileWriteActionContext.class);
    ActionExecutionContext actionExecutionContext =
        createMockActionExecutionContext(fileWriteContext);
    ExecException failure =
        new EnvironmentalExecException(
            FailureDetail.newBuilder()
                .setExecution(Execution.newBuilder().setCode(Code.FILE_WRITE_IO_EXCEPTION).build())
                .build());
    when(fileWriteContext.beginWriteOutputToFile(
            action,
            actionExecutionContext,
            writer,
            /*makeExecutable=*/ false,
            /*isRemotable=*/ false))
        .thenReturn(
            new SpawnContinuation() {
              @Override
              public ListenableFuture<?> getFuture() {
                return Futures.immediateFuture("hello");
              }

              @Override
              public SpawnContinuation execute() throws ExecException {
                throw failure;
              }
            });

    ActionContinuationOrResult continuation = action.beginExecution(actionExecutionContext);
    ActionExecutionException exception =
        assertThrows(ActionExecutionException.class, continuation::execute);

    assertThat(exception).hasCauseThat().isSameInstanceAs(failure);
    verify(action, never()).afterWrite(actionExecutionContext);
  }

  ActionExecutionContext createMockActionExecutionContext(FileWriteActionContext fileWriteContext) {
    ActionExecutionContext actionExecutionContext = mock(ActionExecutionContext.class);
    when(actionExecutionContext.getContext(FileWriteActionContext.class))
        .thenReturn(fileWriteContext);
    return actionExecutionContext;
  }

  private static class TestFileWriteAction extends AbstractFileWriteAction {
    private final DeterministicWriter deterministicWriter;
    private final boolean isRemotable;

    TestFileWriteAction(
        DeterministicWriter deterministicWriter, boolean executable, boolean isRemotable) {
      super(
          ActionsTestUtil.NULL_ACTION_OWNER,
          /*inputs=*/ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          /*output=*/ ActionsTestUtil.DUMMY_ARTIFACT,
          executable);
      this.deterministicWriter = deterministicWriter;
      this.isRemotable = isRemotable;
    }

    @Override
    public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
      return deterministicWriter;
    }

    @Override
    public boolean isRemotable() {
      return isRemotable;
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      throw new UnsupportedOperationException();
    }
  }
}
