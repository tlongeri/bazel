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

package com.google.devtools.build.lib.worker;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.worker.TestUtils.createWorkerKey;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.lib.worker.WorkerPool.WorkerPoolConfig;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.apache.commons.pool2.PooledObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for the WorkerSpawnRunner. */
// @RunWith(JUnitParamsRunner.class)
@RunWith(Parameterized.class)
public class WorkerSpawnRunnerTest {
  final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock ExtendedEventHandler reporter;
  @Mock LocalEnvProvider localEnvProvider;
  @Mock ResourceManager resourceManager;
  @Mock SpawnMetrics.Builder spawnMetrics;
  @Mock Spawn spawn;
  @Mock SpawnExecutionContext context;
  @Mock MetadataProvider inputFileCache;
  @Mock Worker worker;
  @Mock WorkerOptions options;
  @Mock WorkerMetricsCollector metricsCollector;
  @Mock ResourceManager.ResourceHandle resourceHandle;

  @Parameters
  public static ImmutableList<Object[]> createInputValues() {
    return ImmutableList.of(
        // workerAsResourceFlags variables
        new Object[] {true}, new Object[] {false});
  }

  private final boolean workerAsResource;

  public WorkerSpawnRunnerTest(boolean workerAsResource) {
    this.workerAsResource = workerAsResource;
  }

  @Before
  public void setUp() throws InterruptedException, IOException, ExecException {
    when(spawn.getInputFiles()).thenReturn(NestedSetBuilder.emptySet(Order.COMPILE_ORDER));
    when(context.getArtifactExpander()).thenReturn((artifact, output) -> {});
    doNothing().when(metricsCollector).registerWorker(any());
    when(spawn.getLocalResources()).thenReturn(ResourceSet.createWithRamCpu(100, 1));
    when(resourceManager.acquireResources(any(), any(), any())).thenReturn(resourceHandle);
    when(resourceHandle.getWorker()).thenReturn(worker);
  }

  private WorkerPool createWorkerPool() {
    return new WorkerPool(
        new WorkerPoolConfig(
            new WorkerFactory(fs.getPath("/workerBase")) {
              @Override
              public Worker create(WorkerKey key) {
                return worker;
              }

              @Override
              public boolean validateObject(WorkerKey key, PooledObject<Worker> p) {
                return true;
              }
            },
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of()));
  }

  @Test
  public void testExecInWorker_happyPath() throws ExecException, InterruptedException, IOException {
    WorkerOptions options = new WorkerOptions();
    options.workerAsResource = workerAsResource;

    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools= */ null,
            resourceManager,
            /* runfilesTreeUpdater= */ null,
            options,
            metricsCollector,
            SyscallCache.NO_CACHE);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(0))
        .thenReturn(WorkResponse.newBuilder().setExitCode(0).setOutput("out").build());
    WorkResponse response =
        runner.execInWorker(
            spawn,
            key,
            context,
            new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
            SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
            ImmutableList.of(),
            inputFileCache,
            spawnMetrics);

    assertThat(response).isNotNull();
    assertThat(response.getExitCode()).isEqualTo(0);
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getOutput()).isEqualTo("out");
    assertThat(logFile.exists()).isFalse();
    verify(context, times(1)).report(SpawnExecutingEvent.create("worker"));
    if (workerAsResource) {
      verify(resourceHandle, times(1)).close();
      verify(resourceHandle, times(0)).invalidateAndClose();
    }
    verify(context, times(1)).lockOutputFiles(eq(0), eq("out"), ArgumentMatchers.isNull());
  }

  @Test
  public void testExecInWorker_virtualInputs_doesntQueryInputFileCache()
      throws ExecException, InterruptedException, IOException {
    WorkerOptions options = new WorkerOptions();
    options.workerAsResource = workerAsResource;

    Path execRoot = fs.getPath("/execRoot");
    Path workDir = execRoot.getRelative("workdir");

    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            execRoot,
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools= */ null,
            resourceManager,
            /* runfilesTreeUpdater= */ null,
            options,
            metricsCollector,
            SyscallCache.NO_CACHE);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");

    SandboxHelper sandboxHelper = new SandboxHelper(execRoot, workDir);
    sandboxHelper.addAndCreateVirtualInput("input", "content");

    VirtualActionInput virtualActionInput =
        Iterables.getOnlyElement(
            sandboxHelper.getSandboxInputs().getVirtualInputDigests().keySet());

    when(worker.getResponse(0))
        .thenReturn(WorkResponse.newBuilder().setExitCode(0).setOutput("out").build());
    when(spawn.getInputFiles())
        .thenAnswer(
            invocation ->
                NestedSetBuilder.create(Order.COMPILE_ORDER, (ActionInput) virtualActionInput));

    WorkResponse response =
        runner.execInWorker(
            spawn,
            key,
            context,
            sandboxHelper.getSandboxInputs(),
            sandboxHelper.getSandboxOutputs(),
            ImmutableList.of(),
            inputFileCache,
            spawnMetrics);

    assertThat(response).isNotNull();
    assertThat(response.getExitCode()).isEqualTo(0);
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getOutput()).isEqualTo("out");
    assertThat(logFile.exists()).isFalse();
    verify(inputFileCache, never()).getMetadata(virtualActionInput);
    if (workerAsResource) {
      verify(resourceHandle, times(1)).close();
      verify(resourceHandle, times(0)).invalidateAndClose();
    }
    verify(context, times(1)).lockOutputFiles(eq(0), startsWith("out"), ArgumentMatchers.isNull());
  }

  @Test
  public void testExecInWorker_finishesAsyncOnInterrupt()
      throws InterruptedException, IOException, ExecException {
    WorkerOptions options = new WorkerOptions();
    options.workerAsResource = workerAsResource;
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools= */ null,
            resourceManager,
            /* runfilesTreeUpdater=*/ null,
            options,
            metricsCollector,
            SyscallCache.NO_CACHE);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(anyInt()))
        .thenThrow(new InterruptedException())
        .thenReturn(WorkResponse.newBuilder().setRequestId(2).build());
    assertThrows(
        InterruptedException.class,
        () ->
            runner.execInWorker(
                spawn,
                key,
                context,
                new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
                SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                ImmutableList.of(),
                inputFileCache,
                spawnMetrics));
    assertThat(logFile.exists()).isFalse();
    verify(context, times(1)).report(SpawnExecutingEvent.create("worker"));
    verify(worker, times(1)).putRequest(WorkRequest.newBuilder().setRequestId(0).build());
    if (workerAsResource) {
      verify(resourceHandle, times(0)).close();
      verify(resourceHandle, times(1)).invalidateAndClose();
    }
  }

  @Test
  public void testExecInWorker_sendsCancelMessageOnInterrupt()
      throws ExecException, InterruptedException, IOException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerCancellation = true;
    workerOptions.workerSandboxing = true;
    workerOptions.workerAsResource = workerAsResource;
    when(spawn.getExecutionInfo())
        .thenReturn(ImmutableMap.of(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION, "1"));
    when(worker.isSandboxed()).thenReturn(true);
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools= */ null,
            resourceManager,
            /* runfilesTreeUpdater=*/ null,
            workerOptions,
            metricsCollector,
            SyscallCache.NO_CACHE);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    Semaphore secondResponseRequested = new Semaphore(0);
    // Fake that the getting the regular response gets interrupted and we then answer the cancel.
    when(worker.getResponse(anyInt()))
        .thenThrow(new InterruptedException())
        .thenAnswer(
            invocation -> {
              secondResponseRequested.release();
              return WorkResponse.newBuilder()
                  .setRequestId(invocation.getArgument(0))
                  .setWasCancelled(true)
                  .build();
            });
    assertThrows(
        InterruptedException.class,
        () ->
            runner.execInWorker(
                spawn,
                key,
                context,
                new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
                SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                ImmutableList.of(),
                inputFileCache,
                spawnMetrics));
    secondResponseRequested.acquire();
    assertThat(logFile.exists()).isFalse();
    verify(context, times(1)).report(SpawnExecutingEvent.create("worker"));
    ArgumentCaptor<WorkRequest> argumentCaptor = ArgumentCaptor.forClass(WorkRequest.class);
    verify(worker, times(2)).putRequest(argumentCaptor.capture());
    assertThat(argumentCaptor.getAllValues().get(0))
        .isEqualTo(WorkRequest.newBuilder().setRequestId(0).build());
    assertThat(argumentCaptor.getAllValues().get(1))
        .isEqualTo(WorkRequest.newBuilder().setRequestId(0).setCancel(true).build());
    if (workerAsResource) {
      Thread.sleep(10);
      verify(resourceHandle, times(1)).close();
      verify(resourceHandle, times(0)).invalidateAndClose();
    }
  }

  @Test
  public void testExecInWorker_unsandboxedDiesOnInterrupt()
      throws InterruptedException, IOException, ExecException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerCancellation = true;
    workerOptions.workerSandboxing = false;
    workerOptions.workerAsResource = workerAsResource;
    when(spawn.getExecutionInfo())
        .thenReturn(ImmutableMap.of(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION, "1"));
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools= */ null,
            resourceManager,
            /* runfilesTreeUpdater=*/ null,
            workerOptions,
            metricsCollector,
            SyscallCache.NO_CACHE);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(anyInt())).thenThrow(new InterruptedException());
    // Since this worker is not sandboxed, it will just get killed on interrupt.
    assertThrows(
        InterruptedException.class,
        () ->
            runner.execInWorker(
                spawn,
                key,
                context,
                new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
                SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                ImmutableList.of(),
                inputFileCache,
                spawnMetrics));

    assertThat(logFile.exists()).isFalse();
    verify(context, times(1)).report(SpawnExecutingEvent.create("worker"));
    ArgumentCaptor<WorkRequest> argumentCaptor = ArgumentCaptor.forClass(WorkRequest.class);
    verify(worker, times(1)).putRequest(argumentCaptor.capture());
    assertThat(argumentCaptor.getAllValues().get(0))
        .isEqualTo(WorkRequest.newBuilder().setRequestId(0).build());
    if (workerAsResource) {
      verify(resourceHandle, times(0)).close();
      verify(resourceHandle, times(1)).invalidateAndClose();
    } else {
      verify(worker, times(1)).destroy();
    }
  }

  @Test
  public void testExecInWorker_noMultiplexWithDynamic()
      throws ExecException, InterruptedException, IOException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerMultiplex = true;
    workerOptions.workerAsResource = workerAsResource;
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools= */ null,
            resourceManager,
            /* runfilesTreeUpdater= */ null,
            workerOptions,
            metricsCollector,
            SyscallCache.NO_CACHE);
    // This worker key just so happens to be multiplex and require sandboxing.
    WorkerKey key = createWorkerKey(WorkerProtocolFormat.JSON, fs, true);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(0))
        .thenReturn(
            WorkResponse.newBuilder().setExitCode(0).setRequestId(0).setOutput("out").build());
    WorkResponse response =
        runner.execInWorker(
            spawn,
            key,
            context,
            new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
            SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
            ImmutableList.of(),
            inputFileCache,
            spawnMetrics);

    assertThat(response).isNotNull();
    assertThat(response.getExitCode()).isEqualTo(0);
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getOutput()).isEqualTo("out");
    assertThat(logFile.exists()).isFalse();
    verify(context, times(1)).report(SpawnExecutingEvent.create("worker"));
    if (workerAsResource) {
      verify(resourceHandle, times(1)).close();
      verify(resourceHandle, times(0)).invalidateAndClose();
    }
    verify(context, times(1)).lockOutputFiles(eq(0), startsWith("out"), ArgumentMatchers.isNull());
  }

  private void assertRecordedResponsethrowsException(
      String recordedResponse, String exceptionText, boolean workerAsResource) throws Exception {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerAsResource = workerAsResource;
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools= */ null,
            resourceManager,
            /* runfilesTreeUpdater= */ null,
            workerOptions,
            metricsCollector,
            SyscallCache.NO_CACHE);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getLogFile()).thenReturn(logFile);
    when(worker.getResponse(0)).thenThrow(new IOException("Bad protobuf"));
    when(worker.getRecordingStreamMessage()).thenReturn(recordedResponse);
    when(worker.getExitValue()).thenReturn(Optional.of(2));
    String workerLog = "Log from worker\n";
    FileSystemUtils.writeIsoLatin1(logFile, workerLog);
    UserExecException execException =
        assertThrows(
            UserExecException.class,
            () ->
                runner.execInWorker(
                    spawn,
                    key,
                    context,
                    new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
                    SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                    ImmutableList.of(),
                    inputFileCache,
                    spawnMetrics));

    assertThat(execException).hasMessageThat().contains(exceptionText);
    if (!recordedResponse.isEmpty()) {
      assertThat(execException)
          .hasMessageThat()
          .contains(logMarker("Exception details") + "java.io.IOException: Bad protobuf");

      assertThat(execException)
          .hasMessageThat()
          .contains(
              logMarker("Start of response") + recordedResponse + logMarker("End of response"));
    }
    assertThat(execException)
        .hasMessageThat()
        .contains(logMarker("Start of log, file at " + logFile.getPathString()) + workerLog);
    verify(context, times(1))
        .lockOutputFiles(
            eq(2), ArgumentMatchers.contains(exceptionText), ArgumentMatchers.isNull());
  }

  @Test
  public void testExecInWorker_showsLogFileInException() throws Exception {
    assertRecordedResponsethrowsException(
        "Some text", "unparseable WorkResponse!\n", workerAsResource);
  }

  @Test
  public void testExecInWorker_throwsWithEmptyResponse() throws Exception {
    assertRecordedResponsethrowsException("", "did not return a WorkResponse", workerAsResource);
  }

  private static String logMarker(String text) {
    return "---8<---8<--- " + text + " ---8<---8<---\n";
  }
}
