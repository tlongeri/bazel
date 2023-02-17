// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/** Factory used by the pool to create / destroy / validate worker processes. */
public class WorkerFactory extends BaseKeyedPooledObjectFactory<WorkerKey, Worker> {

  // It's fine to use an AtomicInteger here (which is 32-bit), because it is only incremented when
  // spawning a new worker, thus even under worst-case circumstances and buggy workers quitting
  // after each action, this should never overflow.
  // This starts at 1 to avoid hiding latent problems of multiplex workers not returning a
  // request_id (which is indistinguishable from 0 in proto3).
  private static final AtomicInteger pidCounter = new AtomicInteger(1);

  private final Path workerBaseDir;
  private Reporter reporter;

  public WorkerFactory(Path workerBaseDir) {
    this.workerBaseDir = workerBaseDir;
  }

  public void setReporter(Reporter reporter) {
    this.reporter = reporter;
  }

  @Override
  public Worker create(WorkerKey key) throws IOException {
    int workerId = pidCounter.getAndIncrement();
    String workTypeName = key.getWorkerTypeName();
    if (!workerBaseDir.isDirectory()) {
      try {
        workerBaseDir.createDirectoryAndParents();
      } catch (IOException e) {
        System.err.println(
            "Can't create worker dir, there is a " + workerBaseDir.stat() + " there.");
      }
    }

    Path logFile =
        workerBaseDir.getRelative(workTypeName + "-" + workerId + "-" + key.getMnemonic() + ".log");

    Worker worker;
    if (key.isSandboxed()) {
      if (key.isMultiplex()) {
        WorkerMultiplexer workerMultiplexer = WorkerMultiplexerManager.getInstance(key, logFile);
        Path workDir = getSandboxedWorkerPath(key);
        worker = new SandboxedWorkerProxy(key, workerId, logFile, workerMultiplexer, workDir);
      } else {
        Path workDir = getSandboxedWorkerPath(key, workerId);
        worker = new SandboxedWorker(key, workerId, workDir, logFile);
      }
    } else if (key.isMultiplex()) {
      WorkerMultiplexer workerMultiplexer = WorkerMultiplexerManager.getInstance(key, logFile);
      worker =
          new WorkerProxy(
              key, workerId, workerMultiplexer.getLogFile(), workerMultiplexer, key.getExecRoot());
    } else {
      worker = new SingleplexWorker(key, workerId, key.getExecRoot(), logFile);
    }
    if (reporter != null) {
      reporter.handle(
          Event.info(
              String.format(
                  "Created new %s %s %s (id %d), logging to %s",
                  key.isSandboxed() ? "sandboxed" : "non-sandboxed",
                  key.getMnemonic(),
                  workTypeName,
                  workerId,
                  worker.getLogFile())));
    }
    return worker;
  }

  Path getSandboxedWorkerPath(WorkerKey key, int workerId) {
    String workspaceName = key.getExecRoot().getBaseName();
    return workerBaseDir
        .getRelative(key.getWorkerTypeName() + "-" + workerId + "-" + key.getMnemonic())
        .getRelative(workspaceName);
  }

  Path getSandboxedWorkerPath(WorkerKey key) {
    String workspaceName = key.getExecRoot().getBaseName();
    return workerBaseDir
        .getRelative(key.getMnemonic() + "-" + key.getWorkerTypeName() + "-workdir")
        .getRelative(workspaceName);
  }

  /** Use the DefaultPooledObject implementation. */
  @Override
  public PooledObject<Worker> wrap(Worker worker) {
    return new DefaultPooledObject<>(worker);
  }

  /** When a worker process is discarded, destroy its process, too. */
  @Override
  public void destroyObject(WorkerKey key, PooledObject<Worker> p) {
    if (reporter != null) {
      int workerId = p.getObject().getWorkerId();
      reporter.handle(
          Event.info(
              String.format(
                  "Destroying %s %s (id %d)",
                  key.getMnemonic(), key.getWorkerTypeName(), workerId)));
    }
    p.getObject().destroy();
  }

  /**
   * Returns true if this worker is still valid. The worker is considered to be valid as long as its
   * process has not exited and its files have not changed on disk.
   */
  @Override
  public boolean validateObject(WorkerKey key, PooledObject<Worker> p) {
    Worker worker = p.getObject();
    Optional<Integer> exitValue = worker.getExitValue();
    if (exitValue.isPresent()) {
      if (reporter != null && worker.diedUnexpectedly()) {
        String msg =
            String.format(
                "%s %s (id %d) has unexpectedly died with exit code %d.",
                key.getMnemonic(), key.getWorkerTypeName(), worker.getWorkerId(), exitValue.get());
        ErrorMessage errorMessage =
            ErrorMessage.builder()
                .message(msg)
                .logFile(worker.getLogFile())
                .logSizeLimit(4096)
                .build();
        reporter.handle(Event.warn(errorMessage.toString()));
      }
      return false;
    }
    boolean filesChanged =
        !key.getWorkerFilesCombinedHash().equals(worker.getWorkerFilesCombinedHash());

    if (reporter != null && filesChanged) {
      StringBuilder msg = new StringBuilder();
      msg.append(
          String.format(
              "%s %s (id %d) can no longer be used, because its files have changed on disk:",
              key.getMnemonic(), key.getWorkerTypeName(), worker.getWorkerId()));
      TreeSet<PathFragment> files = new TreeSet<>();
      files.addAll(key.getWorkerFilesWithDigests().keySet());
      files.addAll(worker.getWorkerFilesWithDigests().keySet());
      for (PathFragment file : files) {
        byte[] oldDigest = worker.getWorkerFilesWithDigests().get(file);
        byte[] newDigest = key.getWorkerFilesWithDigests().get(file);
        if (!Arrays.equals(oldDigest, newDigest)) {
          msg.append("\n")
              .append(file.getPathString())
              .append(": ")
              .append(hexStringForDebugging(oldDigest))
              .append(" -> ")
              .append(hexStringForDebugging(newDigest));
        }
      }

      reporter.handle(Event.warn(msg.toString()));
    }

    return !filesChanged;
  }

  private static String hexStringForDebugging(@Nullable byte[] bytes) {
    return bytes != null ? BaseEncoding.base16().encode(bytes).toLowerCase(Locale.ROOT) : "<none>";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof WorkerFactory)) {
      return false;
    }
    WorkerFactory that = (WorkerFactory) o;
    return workerBaseDir.equals(that.workerBaseDir);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(workerBaseDir);
  }
}
