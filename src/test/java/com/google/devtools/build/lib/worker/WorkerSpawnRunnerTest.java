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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.lib.worker.WorkerPool.WorkerPoolConfig;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.apache.commons.pool2.PooledObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for the WorkerSpawnRunner. */
@RunWith(JUnit4.class)
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
  @Mock EventBus eventBus;
  @Mock Runtime runtime;

  @Before
  public void setUp() {
    when(spawn.getInputFiles()).thenReturn(NestedSetBuilder.emptySet(Order.COMPILE_ORDER));
    when(context.getArtifactExpander()).thenReturn((artifact, output) -> {});
    doNothing().when(eventBus).register(any());
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
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(false),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools */ null,
            resourceManager,
            /* runfilestTreeUpdater */ null,
            new WorkerOptions(),
            eventBus,
            runtime);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(0))
        .thenReturn(WorkResponse.newBuilder().setExitCode(0).setOutput("out").build());
    WorkResponse response =
        runner.execInWorker(
            spawn,
            key,
            context,
            new SandboxInputs(ImmutableMap.of(), ImmutableSet.of(), ImmutableMap.of()),
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
  }

  @Test
  public void testExecInWorker_finishesAsyncOnInterrupt() throws InterruptedException, IOException {
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(false),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools */ null,
            resourceManager,
            /* runfilesTreeUpdater=*/ null,
            new WorkerOptions(),
            eventBus,
            runtime);
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
                new SandboxInputs(ImmutableMap.of(), ImmutableSet.of(), ImmutableMap.of()),
                SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                ImmutableList.of(),
                inputFileCache,
                spawnMetrics));
    assertThat(logFile.exists()).isFalse();
    verify(context, times(1)).report(SpawnExecutingEvent.create("worker"));
    verify(worker, times(1)).putRequest(WorkRequest.newBuilder().setRequestId(0).build());
  }

  @Test
  public void testExecInWorker_sendsCancelMessageOnInterrupt()
      throws ExecException, InterruptedException, IOException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerCancellation = true;
    workerOptions.workerSandboxing = true;
    when(spawn.getExecutionInfo())
        .thenReturn(ImmutableMap.of(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION, "1"));
    when(worker.isSandboxed()).thenReturn(true);
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(false),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools */ null,
            resourceManager,
            /* runfilesTreeUpdater=*/ null,
            workerOptions,
            eventBus,
            runtime);
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
                new SandboxInputs(ImmutableMap.of(), ImmutableSet.of(), ImmutableMap.of()),
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
  }

  @Test
  public void testExecInWorker_unsandboxedDiesOnInterrupt()
      throws InterruptedException, IOException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerCancellation = true;
    workerOptions.workerSandboxing = false;
    when(spawn.getExecutionInfo())
        .thenReturn(ImmutableMap.of(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION, "1"));
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(false),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools */ null,
            resourceManager,
            /* runfilesTreeUpdater=*/ null,
            workerOptions,
            eventBus,
            runtime);
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
                new SandboxInputs(ImmutableMap.of(), ImmutableSet.of(), ImmutableMap.of()),
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
    verify(worker, times(1)).destroy();
  }

  @Test
  public void testExecInWorker_noMultiplexWithDynamic()
      throws ExecException, InterruptedException, IOException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerMultiplex = true;
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(false),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools */ null,
            resourceManager,
            /* runfilestTreeUpdater */ null,
            workerOptions,
            eventBus,
            runtime);
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
            new SandboxInputs(ImmutableMap.of(), ImmutableSet.of(), ImmutableMap.of()),
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
  }

  private void assertRecordedResponsethrowsException(String recordedResponse, String exceptionText)
      throws Exception {
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(false),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools */ null,
            resourceManager,
            /* runfilestTreeUpdater */ null,
            new WorkerOptions(),
            eventBus,
            runtime);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getLogFile()).thenReturn(logFile);
    when(worker.getResponse(0)).thenThrow(new IOException("Bad protobuf"));
    when(worker.getRecordingStreamMessage()).thenReturn(recordedResponse);
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
                    new SandboxInputs(ImmutableMap.of(), ImmutableSet.of(), ImmutableMap.of()),
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
  }

  @Test
  public void testCollectStats_ignoreSpaces() throws Exception {
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(false),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools */ null,
            resourceManager,
            /* runfilestTreeUpdater */ null,
            new WorkerOptions(),
            eventBus,
            runtime);

    String psOutput = "    PID  \t  RSS\n   1  3216 \t\n  \t 2 \t 4096 \t";
    InputStream psStream = new ByteArrayInputStream(psOutput.getBytes(UTF_8));
    Process process = mock(Process.class);

    when(runtime.exec(new String[] {"bash", "-c", "ps -o pid,rss -p 1,2"})).thenReturn(process);
    when(process.getInputStream()).thenReturn(psStream);

    List<Long> pids = Arrays.asList(1L, 2L);
    Map<Long, WorkerMetric.WorkerStat> pidResults = runner.collectStats(OS.LINUX, pids);

    assertThat(pidResults).hasSize(2);
    assertThat(pidResults.get(1L).getUsedMemoryInKB()).isEqualTo(3);
    assertThat(pidResults.get(2L).getUsedMemoryInKB()).isEqualTo(4);
  }

  @Test
  public void testCollectStats_filterInvalidPids() throws Exception {
    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(false),
            fs.getPath("/execRoot"),
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools */ null,
            resourceManager,
            /* runfilestTreeUpdater */ null,
            new WorkerOptions(),
            eventBus,
            runtime);

    String psOutput = "PID  RSS  \n 1  3216";
    InputStream psStream = new ByteArrayInputStream(psOutput.getBytes(UTF_8));
    Process process = mock(Process.class);

    when(runtime.exec(new String[] {"bash", "-c", "ps -o pid,rss -p 1"})).thenReturn(process);
    when(process.getInputStream()).thenReturn(psStream);

    List<Long> pids = Arrays.asList(1L, 0L);
    Map<Long, WorkerMetric.WorkerStat> pidResults = runner.collectStats(OS.LINUX, pids);

    assertThat(pidResults).hasSize(1);
    assertThat(pidResults.get(1L).getUsedMemoryInKB()).isEqualTo(3);
  }

  @Test
  public void testExecInWorker_showsLogFileInException() throws Exception {
    assertRecordedResponsethrowsException("Some text", "unparseable WorkResponse!\n");
  }

  @Test
  public void testExecInWorker_throwsWithEmptyResponse() throws Exception {
    assertRecordedResponsethrowsException("", "did not return a WorkResponse");
  }

  private static String logMarker(String text) {
    return "---8<---8<--- " + text + " ---8<---8<---\n";
  }
}
