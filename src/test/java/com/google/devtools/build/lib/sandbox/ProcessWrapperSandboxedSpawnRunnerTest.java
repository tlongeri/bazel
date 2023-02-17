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
package com.google.devtools.build.lib.sandbox;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assume.assumeTrue;

import com.google.devtools.build.lib.actions.LocalHostCapacity;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.SpawnRunnerTestUtil.SpawnExecutionContextForTesting;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ProcessWrapperSandboxSpawnRunner}. */
@RunWith(JUnit4.class)
public final class ProcessWrapperSandboxedSpawnRunnerTest extends SandboxedSpawnRunnerTestCase {

  /** Tree deleter to use by default for all tests. */
  private static final TreeDeleter treeDeleter = new SynchronousTreeDeleter();

  @Test
  public void processWrapperSandboxedSpawnRunner_canRunEcho() throws Exception {
    // TODO(b/62588075) Currently no process-wrapper support in windows.
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    CommandEnvironment commandEnvironment = runtimeWrapper.newCommand();
    commandEnvironment.setWorkspaceName("workspace");
    commandEnvironment
        .getLocalResourceManager()
        .setAvailableResources(LocalHostCapacity.getLocalHostCapacity());

    Path execRoot = commandEnvironment.getExecRoot();
    execRoot.createDirectory();

    SpawnRunnerTestUtil.copyProcessWrapperIntoPath(execRoot);

    Path sandboxBase = execRoot.getRelative("sandbox");
    sandboxBase.createDirectory();

    Duration policyTimeout = Duration.ofSeconds(60);

    ProcessWrapperSandboxedSpawnRunner runner =
        new ProcessWrapperSandboxedSpawnRunner(
            new SandboxHelpers(),
            commandEnvironment,
            sandboxBase,
            /* sandboxfsProcess= */ null,
            /* sandboxfsMapSymlinkTargets= */ false,
            treeDeleter);

    Spawn spawn = new SpawnBuilder("echo", "cooee").build();

    FileOutErr fileOutErr =
        new FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"));
    SpawnExecutionContextForTesting policy =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, policyTimeout);

    SpawnResult spawnResult = runner.execAsync(spawn, policy).get();

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(spawnResult.exitCode()).isEqualTo(0);
    assertThat(spawnResult.setupSuccess()).isTrue();
    assertThat(spawnResult.getWallTime()).isPresent();
  }

  @Test
  public void hasExecutionStatistics_whenOptionIsEnabled() throws Exception {
    // TODO(b/62588075) Currently no process-wrapper or execution statistics support in Windows.
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    Duration minimumWallTimeToSpend = Duration.ofSeconds(10);
    // Because of e.g. interference, wall time taken may be much larger than CPU time used.
    Duration maximumWallTimeToSpend = Duration.ofSeconds(40);

    Duration minimumUserTimeToSpend = minimumWallTimeToSpend;
    Duration maximumUserTimeToSpend = minimumUserTimeToSpend.plus(Duration.ofSeconds(2));

    Duration minimumSystemTimeToSpend = Duration.ZERO;
    Duration maximumSystemTimeToSpend = minimumSystemTimeToSpend.plus(Duration.ofSeconds(2));

    CommandEnvironment commandEnvironment = runtimeWrapper.newCommand();
    commandEnvironment.setWorkspaceName("workspace");
    Path execRoot = commandEnvironment.getExecRoot();
    execRoot.createDirectory();

    SpawnRunnerTestUtil.copyProcessWrapperIntoPath(execRoot);

    Path sandboxBase = execRoot.getRelative("sandbox");
    sandboxBase.createDirectory();

    Path cpuTimeSpenderPath = SpawnRunnerTestUtil.copyCpuTimeSpenderIntoPath(execRoot);

    Duration policyTimeout = Duration.ofSeconds(60);

    ProcessWrapperSandboxedSpawnRunner runner =
        new ProcessWrapperSandboxedSpawnRunner(
            new SandboxHelpers(),
            commandEnvironment,
            sandboxBase,
            /* sandboxfsProcess= */ null,
            /* sandboxfsMapSymlinkTargets= */ false,
            treeDeleter);

    Spawn spawn =
        new SpawnBuilder(
                cpuTimeSpenderPath.getPathString(),
                String.valueOf(minimumUserTimeToSpend.getSeconds()),
                String.valueOf(minimumSystemTimeToSpend.getSeconds()))
            .build();

    FileOutErr fileOutErr =
        new FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"));
    SpawnExecutionContextForTesting policy =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, policyTimeout);

    SpawnResult spawnResult = runner.execAsync(spawn, policy).get();

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(spawnResult.exitCode()).isEqualTo(0);
    assertThat(spawnResult.setupSuccess()).isTrue();

    assertThat(spawnResult.getWallTime()).isPresent();
    assertThat(spawnResult.getWallTime().get()).isAtLeast(minimumWallTimeToSpend);
    assertThat(spawnResult.getWallTime().get()).isAtMost(maximumWallTimeToSpend);
    assertThat(spawnResult.getUserTime()).isPresent();
    assertThat(spawnResult.getUserTime().get()).isAtLeast(minimumUserTimeToSpend);
    assertThat(spawnResult.getUserTime().get()).isAtMost(maximumUserTimeToSpend);
    assertThat(spawnResult.getSystemTime()).isPresent();
    assertThat(spawnResult.getSystemTime().get()).isAtLeast(minimumSystemTimeToSpend);
    assertThat(spawnResult.getSystemTime().get()).isAtMost(maximumSystemTimeToSpend);
    assertThat(spawnResult.getNumBlockOutputOperations().get()).isAtLeast(0L);
    assertThat(spawnResult.getNumBlockInputOperations().get()).isAtLeast(0L);
    assertThat(spawnResult.getNumInvoluntaryContextSwitches().get()).isAtLeast(0L);
  }

  @Test
  public void hasNoExecutionStatistics_whenOptionIsDisabled() throws Exception {
    // TODO(b/62588075) Currently no process-wrapper support in Windows.
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    Duration minimumWallTimeToSpend = Duration.ofSeconds(10);
    // Because of e.g. interference, wall time taken may be much larger than CPU time used.
    Duration maximumWallTimeToSpend = Duration.ofSeconds(40);

    Duration minimumUserTimeToSpend = minimumWallTimeToSpend;
    Duration minimumSystemTimeToSpend = Duration.ZERO;

    CommandEnvironment commandEnvironment =
        getCommandEnvironmentWithExecutionStatisticsOptionDisabled("workspace");
    commandEnvironment
        .getLocalResourceManager()
        .setAvailableResources(LocalHostCapacity.getLocalHostCapacity());

    Path execRoot = commandEnvironment.getExecRoot();
    execRoot.createDirectory();

    SpawnRunnerTestUtil.copyProcessWrapperIntoPath(execRoot);

    Path sandboxBase = execRoot.getRelative("sandbox");
    sandboxBase.createDirectory();

    Path cpuTimeSpenderPath = SpawnRunnerTestUtil.copyCpuTimeSpenderIntoPath(execRoot);

    Duration policyTimeout = Duration.ofSeconds(60);

    ProcessWrapperSandboxedSpawnRunner runner =
        new ProcessWrapperSandboxedSpawnRunner(
            new SandboxHelpers(),
            commandEnvironment,
            sandboxBase,
            /* sandboxfsProcess= */ null,
            /* sandboxfsMapSymlinkTargets= */ false,
            treeDeleter);

    Spawn spawn =
        new SpawnBuilder(
                cpuTimeSpenderPath.getPathString(),
                String.valueOf(minimumUserTimeToSpend.getSeconds()),
                String.valueOf(minimumSystemTimeToSpend.getSeconds()))
            .build();

    FileOutErr fileOutErr =
        new FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"));
    SpawnExecutionContextForTesting policy =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, policyTimeout);

    SpawnResult spawnResult = runner.execAsync(spawn, policy).get();

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(spawnResult.exitCode()).isEqualTo(0);
    assertThat(spawnResult.setupSuccess()).isTrue();

    assertThat(spawnResult.getWallTime()).isPresent();
    assertThat(spawnResult.getWallTime().get()).isAtLeast(minimumWallTimeToSpend);
    assertThat(spawnResult.getWallTime().get()).isAtMost(maximumWallTimeToSpend);
    assertThat(spawnResult.getUserTime()).isEmpty();
    assertThat(spawnResult.getSystemTime()).isEmpty();
    assertThat(spawnResult.getNumBlockOutputOperations()).isEmpty();
    assertThat(spawnResult.getNumBlockInputOperations()).isEmpty();
    assertThat(spawnResult.getNumInvoluntaryContextSwitches()).isEmpty();
  }
}
