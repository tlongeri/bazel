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
package com.google.devtools.build.lib.worker;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.worker.TestUtils.createWorkerKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.lib.worker.WorkerPool.WorkerPoolConfig;
import java.time.Instant;
import java.util.Map.Entry;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class WorkerLifecycleManagerTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock WorkerFactory factoryMock;
  private FileSystem fileSystem;
  private int workerIds = 1;

  private static class TestWorker extends SingleplexWorker {
    TestWorker(WorkerKey workerKey, int workerId, Path workDir, Path logFile) {
      super(workerKey, workerId, workDir, logFile);
    }
  }

  @Before
  public void setUp() throws Exception {
    fileSystem = new InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256);
    doAnswer(
            arg ->
                new DefaultPooledObject<>(
                    new TestWorker(
                        arg.getArgument(0),
                        workerIds++,
                        fileSystem.getPath("/workDir"),
                        fileSystem.getPath("/logDir"))))
        .when(factoryMock)
        .makeObject(any());
    when(factoryMock.validateObject(any(), any())).thenReturn(true);
  }

  @Test
  public void testEvictWorkers_doNothing_lowMemoryUsage() throws Exception {
    WorkerPool workerPool =
        new WorkerPool(
            new WorkerPoolConfig(
                factoryMock, entryList("dummy", 1), emptyEntryList(), Lists.newArrayList()));
    WorkerKey key = createWorkerKey("dummy", fileSystem);
    Worker w1 = workerPool.borrowObject(key);
    workerPool.returnObject(key, w1);

    ImmutableList<WorkerMetric> workerMetrics =
        ImmutableList.of(
            WorkerMetric.create(
                createWorkerProperties(w1.getWorkerId(), 1L, "dummy"),
                createWorkerStat(1024),
                true));
    WorkerOptions options = new WorkerOptions();
    options.totalWorkerMemoryLimitMb = 1024 * 100;

    WorkerLifecycleManager manager = new WorkerLifecycleManager(workerPool, options);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);

    manager.evictWorkers(workerMetrics);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);
  }

  @Test
  public void testEvictWorkers_doNothing_zeroThreshold() throws Exception {
    WorkerPool workerPool =
        new WorkerPool(
            new WorkerPoolConfig(
                factoryMock, entryList("dummy", 1), emptyEntryList(), Lists.newArrayList()));
    WorkerKey key = createWorkerKey("dummy", fileSystem);
    Worker w1 = workerPool.borrowObject(key);
    workerPool.returnObject(key, w1);

    ImmutableList<WorkerMetric> workerMetrics =
        ImmutableList.of(
            WorkerMetric.create(
                createWorkerProperties(w1.getWorkerId(), 1L, "dummy"),
                createWorkerStat(1024),
                true));
    WorkerOptions options = new WorkerOptions();
    options.totalWorkerMemoryLimitMb = 0;

    WorkerLifecycleManager manager = new WorkerLifecycleManager(workerPool, options);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);

    manager.evictWorkers(workerMetrics);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);
  }

  @Test
  public void testEvictWorkers_doNothing_emptyMetrics() throws Exception {
    WorkerPool workerPool =
        new WorkerPool(
            new WorkerPoolConfig(
                factoryMock, entryList("dummy", 1), emptyEntryList(), Lists.newArrayList()));
    WorkerKey key = createWorkerKey("dummy", fileSystem);
    Worker w1 = workerPool.borrowObject(key);
    workerPool.returnObject(key, w1);

    ImmutableList<WorkerMetric> workerMetrics = ImmutableList.of();
    WorkerOptions options = new WorkerOptions();
    options.totalWorkerMemoryLimitMb = 1;

    WorkerLifecycleManager manager = new WorkerLifecycleManager(workerPool, options);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);

    manager.evictWorkers(workerMetrics);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);
  }

  @Test
  public void testGetEvictionCandidates_selectOnlyWorker() throws Exception {
    WorkerPool workerPool =
        new WorkerPool(
            new WorkerPoolConfig(
                factoryMock, entryList("dummy", 1), emptyEntryList(), Lists.newArrayList()));
    WorkerKey key = createWorkerKey("dummy", fileSystem);
    Worker w1 = workerPool.borrowObject(key);
    workerPool.returnObject(key, w1);

    ImmutableList<WorkerMetric> workerMetrics =
        ImmutableList.of(
            WorkerMetric.create(
                createWorkerProperties(w1.getWorkerId(), 1L, "dummy"),
                createWorkerStat(2000),
                true));
    WorkerOptions options = new WorkerOptions();
    options.totalWorkerMemoryLimitMb = 1;

    WorkerLifecycleManager manager = new WorkerLifecycleManager(workerPool, options);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);

    manager.evictWorkers(workerMetrics);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(0);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);
  }

  @Test
  public void testGetEvictionCandidates_evictLargestWorkers() throws Exception {
    WorkerPool workerPool =
        new WorkerPool(
            new WorkerPoolConfig(
                factoryMock, entryList("dummy", 3), emptyEntryList(), Lists.newArrayList()));
    WorkerKey key = createWorkerKey("dummy", fileSystem);
    Worker w1 = workerPool.borrowObject(key);
    Worker w2 = workerPool.borrowObject(key);
    Worker w3 = workerPool.borrowObject(key);
    workerPool.returnObject(key, w1);
    workerPool.returnObject(key, w2);
    workerPool.returnObject(key, w3);

    ImmutableList<WorkerMetric> workerMetrics =
        ImmutableList.of(
            WorkerMetric.create(
                createWorkerProperties(w1.getWorkerId(), 1L, "dummy"),
                createWorkerStat(2000),
                true),
            WorkerMetric.create(
                createWorkerProperties(w2.getWorkerId(), 2L, "dummy"),
                createWorkerStat(1000),
                true),
            WorkerMetric.create(
                createWorkerProperties(w3.getWorkerId(), 3L, "dummy"),
                createWorkerStat(4000),
                true));

    WorkerOptions options = new WorkerOptions();
    options.totalWorkerMemoryLimitMb = 2;
    WorkerLifecycleManager manager = new WorkerLifecycleManager(workerPool, options);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(3);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);

    manager.evictWorkers(workerMetrics);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key)).isEqualTo(0);
    assertThat(workerPool.borrowObject(key).getWorkerId()).isEqualTo(w2.getWorkerId());
  }

  @Test
  public void testGetEvictionCandidates_evictOnlyIdleWorkers() throws Exception {
    WorkerPool workerPool =
        new WorkerPool(
            new WorkerPoolConfig(
                factoryMock, entryList("dummy", 3), emptyEntryList(), Lists.newArrayList()));
    WorkerKey key = createWorkerKey("dummy", fileSystem);
    Worker w1 = workerPool.borrowObject(key);
    Worker w2 = workerPool.borrowObject(key);
    Worker w3 = workerPool.borrowObject(key);
    workerPool.returnObject(key, w1);
    workerPool.returnObject(key, w2);

    ImmutableList<WorkerMetric> workerMetrics =
        ImmutableList.of(
            WorkerMetric.create(
                createWorkerProperties(w1.getWorkerId(), 1L, "dummy"),
                createWorkerStat(2000),
                true),
            WorkerMetric.create(
                createWorkerProperties(w2.getWorkerId(), 2L, "dummy"),
                createWorkerStat(1000),
                true),
            WorkerMetric.create(
                createWorkerProperties(w3.getWorkerId(), 3L, "dummy"),
                createWorkerStat(4000),
                true));

    WorkerOptions options = new WorkerOptions();
    options.totalWorkerMemoryLimitMb = 2;

    WorkerLifecycleManager manager = new WorkerLifecycleManager(workerPool, options);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(2);
    assertThat(workerPool.getNumActive(key)).isEqualTo(1);

    manager.evictWorkers(workerMetrics);

    assertThat(workerPool.getNumIdlePerKey(key)).isEqualTo(0);
    assertThat(workerPool.getNumActive(key)).isEqualTo(1);
  }

  @Test
  public void testGetEvictionCandidates_evictDifferentWorkerKeys() throws Exception {
    WorkerPool workerPool =
        new WorkerPool(
            new WorkerPoolConfig(
                factoryMock,
                entryList("dummy", 2, "smart", 2),
                emptyEntryList(),
                Lists.newArrayList()));
    WorkerKey key1 = createWorkerKey("dummy", fileSystem);
    WorkerKey key2 = createWorkerKey("smart", fileSystem);
    Worker w1 = workerPool.borrowObject(key1);
    Worker w2 = workerPool.borrowObject(key1);
    Worker w3 = workerPool.borrowObject(key2);
    Worker w4 = workerPool.borrowObject(key2);
    workerPool.returnObject(key1, w1);
    workerPool.returnObject(key1, w2);
    workerPool.returnObject(key2, w3);
    workerPool.returnObject(key2, w4);

    ImmutableList<WorkerMetric> workerMetrics =
        ImmutableList.of(
            WorkerMetric.create(
                createWorkerProperties(w1.getWorkerId(), 1L, "dummy"),
                createWorkerStat(1000),
                true),
            WorkerMetric.create(
                createWorkerProperties(w2.getWorkerId(), 2L, "dummy"),
                createWorkerStat(4000),
                true),
            WorkerMetric.create(
                createWorkerProperties(w3.getWorkerId(), 3L, "smart"),
                createWorkerStat(3000),
                true),
            WorkerMetric.create(
                createWorkerProperties(w4.getWorkerId(), 4L, "smart"),
                createWorkerStat(1000),
                true));

    WorkerOptions options = new WorkerOptions();
    options.totalWorkerMemoryLimitMb = 2;

    WorkerLifecycleManager manager = new WorkerLifecycleManager(workerPool, options);

    assertThat(workerPool.getNumIdlePerKey(key1)).isEqualTo(2);
    assertThat(workerPool.getNumActive(key1)).isEqualTo(0);
    assertThat(workerPool.getNumIdlePerKey(key2)).isEqualTo(2);
    assertThat(workerPool.getNumActive(key2)).isEqualTo(0);

    manager.evictWorkers(workerMetrics);

    assertThat(workerPool.getNumIdlePerKey(key1)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key1)).isEqualTo(0);
    assertThat(workerPool.borrowObject(key1).getWorkerId()).isEqualTo(w1.getWorkerId());

    assertThat(workerPool.getNumIdlePerKey(key2)).isEqualTo(1);
    assertThat(workerPool.getNumActive(key2)).isEqualTo(0);
    assertThat(workerPool.borrowObject(key2).getWorkerId()).isEqualTo(w4.getWorkerId());
  }

  private static WorkerMetric.WorkerProperties createWorkerProperties(
      int workerId, long processId, String mnemonic) {
    return WorkerMetric.WorkerProperties.create(
        workerId, processId, mnemonic, /* isMultiplex= */ false, /* isSandboxed= */ false);
  }

  private static WorkerMetric.WorkerStat createWorkerStat(int memoryUsage) {
    return WorkerMetric.WorkerStat.create(
        memoryUsage, /*lastCallTimestamp */ Instant.now(), /* timestamp*/ Instant.now());
  }

  private static ImmutableList<Entry<String, Integer>> emptyEntryList() {
    return ImmutableList.of();
  }

  private static ImmutableList<Entry<String, Integer>> entryList(String key1, int value1) {
    return ImmutableList.of(Maps.immutableEntry(key1, value1));
  }

  private static ImmutableList<Entry<String, Integer>> entryList(
      String key1, int value1, String key2, int value2) {
    return ImmutableList.of(Maps.immutableEntry(key1, value1), Maps.immutableEntry(key2, value2));
  }
}
