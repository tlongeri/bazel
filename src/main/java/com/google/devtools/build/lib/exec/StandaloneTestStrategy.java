// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.exec;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnContinuation;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.test.TestAttempt;
import com.google.devtools.build.lib.analysis.test.TestResult;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction.ResolvedPaths;
import com.google.devtools.build.lib.analysis.test.TestStrategy;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult.ExecutionInfo;
import com.google.devtools.build.lib.buildeventstream.TestFileNameConstants;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TestAction;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.TestCase;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/** Runs TestRunnerAction actions. */
// TODO(bazel-team): add tests for this strategy.
public class StandaloneTestStrategy extends TestStrategy {
  private static final ImmutableMap<String, String> ENV_VARS =
      ImmutableMap.<String, String>builder()
          .put("TZ", "UTC")
          .put("TEST_SRCDIR", TestPolicy.RUNFILES_DIR)
          // TODO(lberki): Remove JAVA_RUNFILES and PYTHON_RUNFILES.
          .put("JAVA_RUNFILES", TestPolicy.RUNFILES_DIR)
          .put("PYTHON_RUNFILES", TestPolicy.RUNFILES_DIR)
          .put("RUNFILES_DIR", TestPolicy.RUNFILES_DIR)
          .put("TEST_TMPDIR", TestPolicy.TEST_TMP_DIR)
          .put("RUN_UNDER_RUNFILES", "1")
          .build();

  public static final TestPolicy DEFAULT_LOCAL_POLICY = new TestPolicy(ENV_VARS);

  protected final Path tmpDirRoot;

  public StandaloneTestStrategy(
      ExecutionOptions executionOptions, BinTools binTools, Path tmpDirRoot) {
    super(executionOptions, binTools);
    this.tmpDirRoot = tmpDirRoot;
  }

  @Override
  public TestRunnerSpawn createTestRunnerSpawn(
      TestRunnerAction action, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    if (action.getExecutionSettings().getInputManifest() == null) {
      String errorMessage = "cannot run local tests with --nobuild_runfile_manifests";
      throw new TestExecException(
          errorMessage,
          FailureDetail.newBuilder()
              .setTestAction(
                  TestAction.newBuilder().setCode(TestAction.Code.LOCAL_TEST_PREREQ_UNMET))
              .setMessage(errorMessage)
              .build());
    }
    Map<String, String> testEnvironment =
        createEnvironment(
            actionExecutionContext, action, tmpDirRoot, executionOptions.splitXmlGeneration);

    Map<String, String> executionInfo =
        new TreeMap<>(action.getTestProperties().getExecutionInfo());
    if (!action.shouldCacheResult()) {
      executionInfo.put(ExecutionRequirements.NO_CACHE, "");
    }
    executionInfo.put(ExecutionRequirements.TIMEOUT, "" + getTimeout(action).getSeconds());
    if (action.getTestProperties().isPersistentTestRunner()) {
      executionInfo.put(ExecutionRequirements.SUPPORTS_WORKERS, "1");
    }

    ResourceSet localResourceUsage =
        action
            .getTestProperties()
            .getLocalResourceUsage(
                action.getOwner().getLabel(), executionOptions.usingLocalTestJobs());

    Spawn spawn =
        new SimpleSpawn(
            action,
            getArgs(action),
            ImmutableMap.copyOf(testEnvironment),
            ImmutableMap.copyOf(executionInfo),
            action.getRunfilesSupplier(),
            ImmutableMap.of(),
            /*inputs=*/ action.getInputs(),
            action.getTestProperties().isPersistentTestRunner()
                ? action.getTools()
                : NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            createSpawnOutputs(action),
            localResourceUsage);
    Path execRoot = actionExecutionContext.getExecRoot();
    ArtifactPathResolver pathResolver = actionExecutionContext.getPathResolver();
    Path runfilesDir = pathResolver.convertPath(action.getExecutionSettings().getRunfilesDir());
    Path tmpDir = pathResolver.convertPath(tmpDirRoot.getChild(TestStrategy.getTmpDirName(action)));
    Path workingDirectory = runfilesDir.getRelative(action.getRunfilesPrefix());
    return new StandaloneTestRunnerSpawn(
        action, actionExecutionContext, spawn, tmpDir, workingDirectory, execRoot);
  }

  private ImmutableSet<ActionInput> createSpawnOutputs(TestRunnerAction action) {
    ImmutableSet.Builder<ActionInput> builder = ImmutableSet.builder();
    for (ActionInput output : action.getSpawnOutputs()) {
      if (output.getExecPath().equals(action.getXmlOutputPath())) {
        // HACK: Convert type of test.xml from BasicActionInput to DerivedArtifact. We want to
        // inject metadata of test.xml if it is generated remotely and it's currently only possible
        // to inject Artifact.
        builder.add(createArtifactOutput(action, output.getExecPath()));
      } else {
        builder.add(output);
      }
    }
    return builder.build();
  }

  private static ImmutableList<Pair<String, Path>> renameOutputs(
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      ImmutableList<Pair<String, Path>> testOutputs,
      int attemptId)
      throws IOException {
    // Rename outputs
    String namePrefix =
        FileSystemUtils.removeExtension(action.getTestLog().getExecPath().getBaseName());
    Path testRoot = actionExecutionContext.getInputPath(action.getTestLog()).getParentDirectory();
    Path attemptsDir = testRoot.getChild(namePrefix + "_attempts");
    attemptsDir.createDirectory();
    String attemptPrefix = "attempt_" + attemptId;
    Path testLog = attemptsDir.getChild(attemptPrefix + ".log");

    // Get the normal test output paths, and then update them to use "attempt_N" names, and
    // attemptDir, before adding them to the outputs.
    ImmutableList.Builder<Pair<String, Path>> testOutputsBuilder = new ImmutableList.Builder<>();
    for (Pair<String, Path> testOutput : testOutputs) {
      // e.g. /testRoot/test.dir/file, an example we follow throughout this loop's comments.
      Path testOutputPath = testOutput.getSecond();
      Path destinationPath;
      if (testOutput.getFirst().equals(TestFileNameConstants.TEST_LOG)) {
        // The rename rules for the test log are different than for all the other files.
        destinationPath = testLog;
      } else {
        // e.g. test.dir/file
        PathFragment relativeToTestDirectory = testOutputPath.relativeTo(testRoot);

        // e.g. attempt_1.dir/file
        String destinationPathFragmentStr =
            relativeToTestDirectory.getSafePathString().replaceFirst("test", attemptPrefix);
        PathFragment destinationPathFragment = PathFragment.create(destinationPathFragmentStr);

        // e.g. /attemptsDir/attempt_1.dir/file
        destinationPath = attemptsDir.getRelative(destinationPathFragment);
        destinationPath.getParentDirectory().createDirectory();
      }

      // Move to the destination.
      testOutputPath.renameTo(destinationPath);

      testOutputsBuilder.add(Pair.of(testOutput.getFirst(), destinationPath));
    }
    return testOutputsBuilder.build();
  }

  private StandaloneFailedAttemptResult processFailedTestAttempt(
      int attemptId,
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      StandaloneTestResult result)
      throws IOException {
    return processTestAttempt(
        attemptId, /*isLastAttempt=*/ false, actionExecutionContext, action, result);
  }

  private void finalizeTest(
      TestRunnerAction action,
      ActionExecutionContext actionExecutionContext,
      StandaloneTestResult standaloneTestResult,
      List<FailedAttemptResult> failedAttempts)
      throws IOException {
    processTestAttempt(
        failedAttempts.size() + 1,
        /*isLastAttempt=*/ true,
        actionExecutionContext,
        action,
        standaloneTestResult);

    TestResultData.Builder dataBuilder = standaloneTestResult.testResultDataBuilder();
    for (FailedAttemptResult failedAttempt : failedAttempts) {
      TestResultData failedAttemptData =
          ((StandaloneFailedAttemptResult) failedAttempt).testResultData;
      dataBuilder.addAllFailedLogs(failedAttemptData.getFailedLogsList());
      dataBuilder.addTestTimes(failedAttemptData.getTestTimes(0));
      dataBuilder.addAllTestProcessTimes(failedAttemptData.getTestProcessTimesList());
    }
    if (dataBuilder.getStatus() == BlazeTestStatus.PASSED && !failedAttempts.isEmpty()) {
      dataBuilder.setStatus(BlazeTestStatus.FLAKY);
    }
    TestResultData data = dataBuilder.build();
    TestResult result =
        new TestResult(action, data, false, standaloneTestResult.primarySystemFailure());
    postTestResult(actionExecutionContext, result);
  }

  private StandaloneFailedAttemptResult processTestAttempt(
      int attemptId,
      boolean isLastAttempt,
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      StandaloneTestResult result)
      throws IOException {
    ImmutableList<Pair<String, Path>> testOutputs =
        action.getTestOutputsMapping(
            actionExecutionContext.getPathResolver(), actionExecutionContext.getExecRoot());
    if (!isLastAttempt) {
      testOutputs = renameOutputs(actionExecutionContext, action, testOutputs, attemptId);
    }

    // Recover the test log path, which may have been renamed, and add it to the data builder.
    Path renamedTestLog = null;
    for (Pair<String, Path> pair : testOutputs) {
      if (TestFileNameConstants.TEST_LOG.equals(pair.getFirst())) {
        Preconditions.checkState(renamedTestLog == null, "multiple test_log matches");
        renamedTestLog = pair.getSecond();
      }
    }

    TestResultData.Builder dataBuilder = result.testResultDataBuilder();
    // If the test log path does not exist, mark the test as incomplete
    if (renamedTestLog == null) {
      dataBuilder.setStatus(BlazeTestStatus.INCOMPLETE);
    }

    if (dataBuilder.getStatus() == BlazeTestStatus.PASSED) {
      dataBuilder.setPassedLog(renamedTestLog.toString());
    } else if (dataBuilder.getStatus() == BlazeTestStatus.INCOMPLETE) {
      // Incomplete (cancelled) test runs don't have a log.
      Preconditions.checkState(renamedTestLog == null);
    } else {
      dataBuilder.addFailedLogs(renamedTestLog.toString());
    }

    // Add the test log to the output
    TestResultData data = dataBuilder.build();
    actionExecutionContext
        .getEventHandler()
        .post(
            TestAttempt.forExecutedTestResult(
                action, data, attemptId, testOutputs, result.executionInfo(), isLastAttempt));
    processTestOutput(actionExecutionContext, data, action.getTestName(), renamedTestLog);
    return new StandaloneFailedAttemptResult(data);
  }

  private static Map<String, String> setupEnvironment(
      TestRunnerAction action,
      Map<String, String> clientEnv,
      Path execRoot,
      Path runfilesDir,
      Path tmpDir) {
    PathFragment relativeTmpDir;
    if (tmpDir.startsWith(execRoot)) {
      relativeTmpDir = tmpDir.relativeTo(execRoot);
    } else {
      relativeTmpDir = tmpDir.asFragment();
    }
    return DEFAULT_LOCAL_POLICY.computeTestEnvironment(
        action, clientEnv, getTimeout(action), runfilesDir.relativeTo(execRoot), relativeTmpDir);
  }

  static class TestMetadataHandler implements MetadataHandler {
    private final MetadataHandler metadataHandler;
    private final ImmutableSet<Artifact> outputs;
    private final ConcurrentMap<Artifact, FileArtifactValue> fileMetadataMap =
        new ConcurrentHashMap<>();

    TestMetadataHandler(MetadataHandler metadataHandler, ImmutableSet<Artifact> outputs) {
      this.metadataHandler = metadataHandler;
      this.outputs = outputs;
    }

    @Nullable
    @Override
    public ActionInput getInput(String execPath) {
      return metadataHandler.getInput(execPath);
    }

    @Nullable
    @Override
    public FileArtifactValue getMetadata(ActionInput input) throws IOException {
      return metadataHandler.getMetadata(input);
    }

    @Override
    public void setDigestForVirtualArtifact(Artifact artifact, byte[] digest) {
      metadataHandler.setDigestForVirtualArtifact(artifact, digest);
    }

    @Override
    public FileArtifactValue constructMetadataForDigest(
        Artifact output, FileStatus statNoFollow, byte[] injectedDigest) throws IOException {
      return metadataHandler.constructMetadataForDigest(output, statNoFollow, injectedDigest);
    }

    @Override
    public ImmutableSet<TreeFileArtifact> getTreeArtifactChildren(SpecialArtifact treeArtifact) {
      return metadataHandler.getTreeArtifactChildren(treeArtifact);
    }

    @Override
    public TreeArtifactValue getTreeArtifactValue(SpecialArtifact treeArtifact) throws IOException {
      return metadataHandler.getTreeArtifactValue(treeArtifact);
    }

    @Override
    public void markOmitted(Artifact output) {
      metadataHandler.markOmitted(output);
    }

    @Override
    public boolean artifactOmitted(Artifact artifact) {
      return metadataHandler.artifactOmitted(artifact);
    }

    @Override
    public void resetOutputs(Iterable<? extends Artifact> outputs) {
      metadataHandler.resetOutputs(outputs);
    }

    @Override
    public void injectFile(Artifact output, FileArtifactValue metadata) {
      if (outputs.contains(output)) {
        metadataHandler.injectFile(output, metadata);
      }
      fileMetadataMap.put(output, metadata);
    }

    @Override
    public void injectTree(SpecialArtifact output, TreeArtifactValue tree) {
      metadataHandler.injectTree(output, tree);
    }

    public boolean fileInjected(Artifact output) {
      return fileMetadataMap.containsKey(output);
    }
  }

  private TestAttemptContinuation beginTestAttempt(
      TestRunnerAction testAction,
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      Path execRoot)
      throws IOException, InterruptedException {
    ResolvedPaths resolvedPaths = testAction.resolve(execRoot);
    Path out = actionExecutionContext.getInputPath(testAction.getTestLog());
    Path err = resolvedPaths.getTestStderr();
    FileOutErr testOutErr = new FileOutErr(out, err);
    Closeable streamed = null;
    if (executionOptions.testOutput.equals(ExecutionOptions.TestOutputFormat.STREAMED)) {
      streamed =
          createStreamedTestOutput(
              Reporter.outErrForReporter(actionExecutionContext.getEventHandler()), out);
    }

    // We use TestMetadataHandler here mainly because the one provided by actionExecutionContext
    // doesn't allow to inject undeclared outputs and test.xml is undeclared by the test action.
    TestMetadataHandler testMetadataHandler = null;
    if (actionExecutionContext.getMetadataHandler() != null) {
      testMetadataHandler =
          new TestMetadataHandler(
              actionExecutionContext.getMetadataHandler(), testAction.getOutputs());
    }

    long startTimeMillis = actionExecutionContext.getClock().currentTimeMillis();
    SpawnStrategyResolver resolver = actionExecutionContext.getContext(SpawnStrategyResolver.class);
    SpawnContinuation spawnContinuation;
    try {
      spawnContinuation =
          resolver.beginExecution(
              spawn,
              actionExecutionContext
                  .withFileOutErr(testOutErr)
                  .withMetadataHandler(testMetadataHandler));
    } catch (InterruptedException e) {
      if (streamed != null) {
        streamed.close();
      }
      testOutErr.close();
      throw e;
    }
    return new BazelTestAttemptContinuation(
        testAction,
        testMetadataHandler,
        actionExecutionContext,
        spawn,
        resolvedPaths,
        testOutErr,
        streamed,
        startTimeMillis,
        spawnContinuation,
        /* testResultDataBuilder= */ null,
        /* spawnResults= */ null);
  }

  private static void appendCoverageLog(FileOutErr coverageOutErr, FileOutErr outErr)
      throws IOException {
    writeOutFile(coverageOutErr.getErrorPath(), outErr.getOutputPath());
    writeOutFile(coverageOutErr.getOutputPath(), outErr.getOutputPath());
  }

  private static void writeOutFile(Path inFilePath, Path outFilePath) throws IOException {
    FileStatus stat = inFilePath.statNullable();
    if (stat != null) {
      try {
        if (stat.getSize() > 0) {
          if (outFilePath.exists()) {
            outFilePath.setWritable(true);
          }
          try (OutputStream out = outFilePath.getOutputStream(true);
              InputStream in = inFilePath.getInputStream()) {
            ByteStreams.copy(in, out);
          }
        }
      } finally {
        inFilePath.delete();
      }
    }
  }

  private static BuildEventStreamProtos.TestResult.ExecutionInfo extractExecutionInfo(
      SpawnResult spawnResult, TestResultData.Builder result) {
    BuildEventStreamProtos.TestResult.ExecutionInfo.Builder executionInfo =
        BuildEventStreamProtos.TestResult.ExecutionInfo.newBuilder();
    if (spawnResult.isCacheHit()) {
      result.setRemotelyCached(true);
      executionInfo.setCachedRemotely(true);
    }
    String strategy = spawnResult.getRunnerName();
    if (strategy != null) {
      executionInfo.setStrategy(strategy);
      result.setIsRemoteStrategy(strategy.equals("remote"));
    }
    if (spawnResult.getExecutorHostName() != null) {
      executionInfo.setHostname(spawnResult.getExecutorHostName());
    }
    return executionInfo.build();
  }

  private static Artifact.DerivedArtifact createArtifactOutput(
      TestRunnerAction action, PathFragment outputPath) {
    Artifact.DerivedArtifact testLog = (Artifact.DerivedArtifact) action.getTestLog();
    return DerivedArtifact.create(testLog.getRoot(), outputPath, testLog.getArtifactOwner());
  }

  /**
   * A spawn to generate a test.xml file from the test log. This is only used if the test does not
   * generate a test.xml file itself.
   */
  private static Spawn createXmlGeneratingSpawn(
      TestRunnerAction action, ImmutableMap<String, String> testEnv, SpawnResult result) {
    ImmutableList<String> args =
        ImmutableList.of(
            action.getTestXmlGeneratorScript().getExecPath().getCallablePathString(),
            action.getTestLog().getExecPathString(),
            action.getXmlOutputPath().getPathString(),
            Long.toString(result.getWallTime().orElse(Duration.ZERO).getSeconds()),
            Integer.toString(result.exitCode()));
    ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();
    // "PATH" and "TEST_BINARY" are also required, they should always be set in testEnv.
    Preconditions.checkArgument(testEnv.containsKey("PATH"));
    Preconditions.checkArgument(testEnv.containsKey("TEST_BINARY"));
    envBuilder.putAll(testEnv).put("TEST_NAME", action.getTestName());
    // testEnv only contains TEST_SHARD_INDEX and TEST_TOTAL_SHARDS if the test action is sharded,
    // we need to set the default value when the action isn't sharded.
    if (!action.isSharded()) {
      envBuilder.put("TEST_SHARD_INDEX", "0");
      envBuilder.put("TEST_TOTAL_SHARDS", "0");
    }
    Map<String, String> executionInfo =
        Maps.newHashMapWithExpectedSize(action.getExecutionInfo().size() + 1);
    executionInfo.putAll(action.getExecutionInfo());
    if (result.exitCode() != 0) {
      // If the test is failed, the spawn shouldn't use remote cache since the test.xml file is
      // renamed immediately after the spawn execution. If there is another test attempt, the async
      // upload will fail because it cannot read the file at original position.
      executionInfo.put(ExecutionRequirements.NO_REMOTE_CACHE, "");
    }
    return new SimpleSpawn(
        action,
        args,
        envBuilder.build(),
        // Pass the execution info of the action which is identical to the supported tags set on the
        // test target. In particular, this does not set the test timeout on the spawn.
        ImmutableMap.copyOf(executionInfo),
        null,
        ImmutableMap.of(),
        /*inputs=*/ NestedSetBuilder.create(
            Order.STABLE_ORDER, action.getTestXmlGeneratorScript(), action.getTestLog()),
        /*tools=*/ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /*outputs=*/ ImmutableSet.of(createArtifactOutput(action, action.getXmlOutputPath())),
        SpawnAction.DEFAULT_RESOURCE_SET);
  }

  /**
   * A spawn to generate a test.xml file from the test log. This is only used if the test does not
   * generate a test.xml file itself.
   */
  private static Spawn createCoveragePostProcessingSpawn(
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      List<ActionInput> expandedCoverageDir,
      Path tmpDirRoot,
      boolean splitXmlGeneration) {
    ImmutableList<String> args =
        ImmutableList.of(action.getCollectCoverageScript().getExecPathString());

    Map<String, String> testEnvironment =
        createEnvironment(actionExecutionContext, action, tmpDirRoot, splitXmlGeneration);

    testEnvironment.put("TEST_SHARD_INDEX", Integer.toString(action.getShardNum()));
    testEnvironment.put(
        "TEST_TOTAL_SHARDS", Integer.toString(action.getExecutionSettings().getTotalShards()));
    testEnvironment.put("TEST_NAME", action.getTestName());
    testEnvironment.put("IS_COVERAGE_SPAWN", "1");
    return new SimpleSpawn(
        action,
        args,
        ImmutableMap.copyOf(testEnvironment),
        action.getExecutionInfo(),
        action.getLcovMergerRunfilesSupplier(),
        /* filesetMappings= */ ImmutableMap.of(),
        /* inputs= */ NestedSetBuilder.<ActionInput>compileOrder()
            .addTransitive(action.getInputs())
            .addAll(expandedCoverageDir)
            .add(action.getCollectCoverageScript())
            .add(action.getCoverageDirectoryTreeArtifact())
            .add(action.getCoverageManifest())
            .addTransitive(action.getLcovMergerFilesToRun().build())
            .build(),
        /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /* outputs= */ ImmutableSet.of(
            ActionInputHelper.fromPath(action.getCoverageData().getExecPath())),
        SpawnAction.DEFAULT_RESOURCE_SET);
  }

  private static Map<String, String> createEnvironment(
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      Path tmpDirRoot,
      boolean splitXmlGeneration) {
    Path execRoot = actionExecutionContext.getExecRoot();
    ArtifactPathResolver pathResolver = actionExecutionContext.getPathResolver();
    Path runfilesDir = pathResolver.convertPath(action.getExecutionSettings().getRunfilesDir());
    Path tmpDir = pathResolver.convertPath(tmpDirRoot.getChild(TestStrategy.getTmpDirName(action)));
    Map<String, String> testEnvironment =
        setupEnvironment(
            action, actionExecutionContext.getClientEnv(), execRoot, runfilesDir, tmpDir);
    if (splitXmlGeneration) {
      testEnvironment.put("EXPERIMENTAL_SPLIT_XML_GENERATION", "1");
    }
    return testEnvironment;
  }

  @Override
  public TestResult newCachedTestResult(
      Path execRoot, TestRunnerAction action, TestResultData data) {
    return new TestResult(action, data, /*cached*/ true, execRoot, /*systemFailure=*/ null);
  }

  @VisibleForTesting
  static final class StandaloneFailedAttemptResult implements FailedAttemptResult {
    private final TestResultData testResultData;

    StandaloneFailedAttemptResult(TestResultData testResultData) {
      this.testResultData = testResultData;
    }

    TestResultData testResultData() {
      return testResultData;
    }
  }

  private final class StandaloneTestRunnerSpawn implements TestRunnerSpawn {
    private final TestRunnerAction testAction;
    private final ActionExecutionContext actionExecutionContext;
    private final Spawn spawn;
    private final Path tmpDir;
    private final Path workingDirectory;
    private final Path execRoot;

    StandaloneTestRunnerSpawn(
        TestRunnerAction testAction,
        ActionExecutionContext actionExecutionContext,
        Spawn spawn,
        Path tmpDir,
        Path workingDirectory,
        Path execRoot) {
      this.testAction = testAction;
      this.actionExecutionContext = actionExecutionContext;
      this.spawn = spawn;
      this.tmpDir = tmpDir;
      this.workingDirectory = workingDirectory;
      this.execRoot = execRoot;
    }

    @Override
    public ActionExecutionContext getActionExecutionContext() {
      return actionExecutionContext;
    }

    @Override
    public TestAttemptContinuation beginExecution() throws InterruptedException, IOException {
      prepareFileSystem(testAction, execRoot, tmpDir, workingDirectory);
      return beginTestAttempt(testAction, spawn, actionExecutionContext, execRoot);
    }

    @Override
    public int getMaxAttempts(TestAttemptResult firstTestAttemptResult) {
      return getTestAttempts(testAction);
    }

    @Override
    public FailedAttemptResult finalizeFailedTestAttempt(
        TestAttemptResult testAttemptResult, int attempt) throws IOException {
      return processFailedTestAttempt(
          attempt, actionExecutionContext, testAction, (StandaloneTestResult) testAttemptResult);
    }

    @Override
    public void finalizeTest(
        TestAttemptResult finalResult, List<FailedAttemptResult> failedAttempts)
        throws IOException {
      StandaloneTestStrategy.this.finalizeTest(
          testAction, actionExecutionContext, (StandaloneTestResult) finalResult, failedAttempts);
    }

    @Override
    public void finalizeCancelledTest(List<FailedAttemptResult> failedAttempts) throws IOException {
      TestResultData.Builder builder =
          TestResultData.newBuilder()
              .setCachable(false)
              .setTestPassed(false)
              .setStatus(BlazeTestStatus.INCOMPLETE);
      StandaloneTestResult standaloneTestResult =
          StandaloneTestResult.builder()
              .setSpawnResults(ImmutableList.of())
              .setTestResultDataBuilder(builder)
              .setExecutionInfo(ExecutionInfo.getDefaultInstance())
              .build();
      finalizeTest(standaloneTestResult, failedAttempts);
    }
  }

  private final class BazelTestAttemptContinuation extends TestAttemptContinuation {
    private final TestRunnerAction testAction;
    @Nullable private final TestMetadataHandler testMetadataHandler;
    private final ActionExecutionContext actionExecutionContext;
    private final Spawn spawn;
    private final ResolvedPaths resolvedPaths;
    private final FileOutErr fileOutErr;
    private final Closeable streamed;
    private final long startTimeMillis;
    private final SpawnContinuation spawnContinuation;
    private TestResultData.Builder testResultDataBuilder;
    private ImmutableList<SpawnResult> spawnResults;

    BazelTestAttemptContinuation(
        TestRunnerAction testAction,
        @Nullable TestMetadataHandler testMetadataHandler,
        ActionExecutionContext actionExecutionContext,
        Spawn spawn,
        ResolvedPaths resolvedPaths,
        FileOutErr fileOutErr,
        Closeable streamed,
        long startTimeMillis,
        SpawnContinuation spawnContinuation,
        TestResultData.Builder testResultDataBuilder,
        ImmutableList<SpawnResult> spawnResults) {
      this.testAction = testAction;
      this.testMetadataHandler = testMetadataHandler;
      this.actionExecutionContext = actionExecutionContext;
      this.spawn = spawn;
      this.resolvedPaths = resolvedPaths;
      this.fileOutErr = fileOutErr;
      this.streamed = streamed;
      this.startTimeMillis = startTimeMillis;
      this.spawnContinuation = spawnContinuation;
      this.testResultDataBuilder = testResultDataBuilder;
      this.spawnResults = spawnResults;
    }

    @Nullable
    @Override
    public ListenableFuture<?> getFuture() {
      return spawnContinuation.getFuture();
    }

    @Override
    public TestAttemptContinuation execute()
        throws InterruptedException, ExecException, IOException {

      if (testResultDataBuilder == null) {
        // We have two protos to represent test attempts:
        // 1. com.google.devtools.build.lib.view.test.TestStatus.TestResultData represents both
        //    failed attempts and finished tests. Bazel stores this to disk to persist cached test
        //    result information across server restarts.
        // 2. com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult
        //    represents only individual attempts (failed or not). Bazel reports this as an event to
        //    the Build Event Protocol, but never saves it to disk.
        //
        // The TestResult proto is always constructed from a TestResultData instance, either one
        // that is created right here, or one that is read back from disk.
        TestResultData.Builder builder = null;
        ImmutableList<SpawnResult> spawnResults;
        try {
          SpawnContinuation nextContinuation = spawnContinuation.execute();
          if (!nextContinuation.isDone()) {
            return new BazelTestAttemptContinuation(
                testAction,
                testMetadataHandler,
                actionExecutionContext,
                spawn,
                resolvedPaths,
                fileOutErr,
                streamed,
                startTimeMillis,
                nextContinuation,
                builder,
                /* spawnResults= */ null);
          }
          spawnResults = nextContinuation.get();
          builder = TestResultData.newBuilder();
          builder.setCachable(true).setTestPassed(true).setStatus(BlazeTestStatus.PASSED);
        } catch (SpawnExecException e) {
          if (e.isCatastrophic()) {
            closeSuppressed(e, streamed);
            closeSuppressed(e, fileOutErr);
            throw e;
          }
          if (!e.getSpawnResult().setupSuccess()) {
            closeSuppressed(e, streamed);
            closeSuppressed(e, fileOutErr);
            // Rethrow as the test could not be run and thus there's no point in retrying.
            throw e;
          }
          spawnResults = ImmutableList.of(e.getSpawnResult());
          builder = TestResultData.newBuilder();
          builder
              .setCachable(e.getSpawnResult().status().isConsideredUserError())
              .setTestPassed(false)
              .setStatus(e.hasTimedOut() ? BlazeTestStatus.TIMEOUT : BlazeTestStatus.FAILED);
        } catch (InterruptedException e) {
          closeSuppressed(e, streamed);
          closeSuppressed(e, fileOutErr);
          throw e;
        }
        long endTimeMillis = actionExecutionContext.getClock().currentTimeMillis();

        // SpawnActionContext guarantees the first entry to correspond to the spawn passed in (there
        // may be additional entries due to tree artifact handling).
        SpawnResult primaryResult = spawnResults.get(0);

        // The SpawnResult of a remotely cached or remotely executed action may not have walltime
        // set. We fall back to the time measured here for backwards compatibility.
        long durationMillis = endTimeMillis - startTimeMillis;
        durationMillis =
            primaryResult.getWallTime().orElse(Duration.ofMillis(durationMillis)).toMillis();

        builder
            .setStartTimeMillisEpoch(startTimeMillis)
            .addTestTimes(durationMillis)
            .addTestProcessTimes(durationMillis)
            .setRunDurationMillis(durationMillis)
            .setHasCoverage(testAction.isCoverageMode());

        if (testAction.isCoverageMode() && testAction.getSplitCoveragePostProcessing()) {
          actionExecutionContext
              .getMetadataHandler()
              .getMetadata(testAction.getCoverageDirectoryTreeArtifact());
          ImmutableSet<? extends ActionInput> expandedCoverageDir =
              actionExecutionContext
                  .getMetadataHandler()
                  .getTreeArtifactChildren(
                      (SpecialArtifact) testAction.getCoverageDirectoryTreeArtifact());
          Spawn coveragePostProcessingSpawn =
              createCoveragePostProcessingSpawn(
                  actionExecutionContext,
                  testAction,
                  ImmutableList.copyOf(expandedCoverageDir),
                  tmpDirRoot,
                  executionOptions.splitXmlGeneration);
          SpawnStrategyResolver spawnStrategyResolver =
              actionExecutionContext.getContext(SpawnStrategyResolver.class);

          Path testRoot =
              actionExecutionContext.getInputPath(testAction.getTestLog()).getParentDirectory();

          Path out = testRoot.getChild("coverage.log");
          Path err = testRoot.getChild("coverage.err");
          FileOutErr coverageOutErr = new FileOutErr(out, err);
          ActionExecutionContext actionExecutionContextWithCoverageFileOutErr =
              actionExecutionContext.withFileOutErr(coverageOutErr);

          SpawnContinuation coveragePostProcessingContinuation =
              spawnStrategyResolver.beginExecution(
                  coveragePostProcessingSpawn, actionExecutionContextWithCoverageFileOutErr);
          writeOutFile(coverageOutErr.getErrorPath(), coverageOutErr.getOutputPath());
          appendCoverageLog(coverageOutErr, fileOutErr);
          return new BazelCoveragePostProcessingContinuation(
              testAction,
              testMetadataHandler,
              actionExecutionContext,
              spawn,
              resolvedPaths,
              fileOutErr,
              streamed,
              builder,
              spawnResults,
              coveragePostProcessingContinuation);
        } else {
          this.spawnResults = spawnResults;
          this.testResultDataBuilder = builder;
        }
      }

      Verify.verify(
          !(testAction.isCoverageMode() && testAction.getSplitCoveragePostProcessing())
              || testAction.getCoverageData().getPath().exists());
      Verify.verifyNotNull(spawnResults);
      Verify.verifyNotNull(testResultDataBuilder);

      try {
        if (!fileOutErr.hasRecordedOutput()) {
          // Make sure that the test.log exists.
          FileSystemUtils.touchFile(fileOutErr.getOutputPath());
        }
        // Append any error output to the test.log. This is very rare.
        writeOutFile(fileOutErr.getErrorPath(), fileOutErr.getOutputPath());
        fileOutErr.close();
        if (streamed != null) {
          streamed.close();
        }
      } catch (IOException e) {
        throw new EnvironmentalExecException(e, Code.TEST_OUT_ERR_IO_EXCEPTION);
      }

      Path xmlOutputPath = resolvedPaths.getXmlOutputPath();
      boolean testXmlGenerated = xmlOutputPath.exists();
      if (!testXmlGenerated && testMetadataHandler != null) {
        testXmlGenerated =
            testMetadataHandler.fileInjected(
                createArtifactOutput(testAction, testAction.getXmlOutputPath()));
      }

      // If the test did not create a test.xml, and --experimental_split_xml_generation is enabled,
      // then we run a separate action to create a test.xml from test.log. We do this as a spawn
      // rather than doing it locally in-process, as the test.log file may only exist remotely (when
      // remote execution is enabled), and we do not want to have to download it.
      if (executionOptions.splitXmlGeneration
          && fileOutErr.getOutputPath().exists()
          && !testXmlGenerated) {
        Spawn xmlGeneratingSpawn =
            createXmlGeneratingSpawn(testAction, spawn.getEnvironment(), spawnResults.get(0));
        SpawnStrategyResolver spawnStrategyResolver =
            actionExecutionContext.getContext(SpawnStrategyResolver.class);
        // We treat all failures to generate the test.xml here as catastrophic, and won't rerun
        // the test if this fails. We redirect the output to a temporary file.
        FileOutErr xmlSpawnOutErr = actionExecutionContext.getFileOutErr().childOutErr();
        try {
          SpawnContinuation xmlContinuation =
              spawnStrategyResolver.beginExecution(
                  xmlGeneratingSpawn,
                  actionExecutionContext
                      .withFileOutErr(xmlSpawnOutErr)
                      .withMetadataHandler(testMetadataHandler));
          return new BazelXmlCreationContinuation(
              resolvedPaths, xmlSpawnOutErr, testResultDataBuilder, spawnResults, xmlContinuation);
        } catch (InterruptedException e) {
          closeSuppressed(e, xmlSpawnOutErr);
          throw e;
        }
      }

      TestCase details = parseTestResult(xmlOutputPath);
      if (details != null) {
        testResultDataBuilder.setTestCase(details);
      }

      BuildEventStreamProtos.TestResult.ExecutionInfo executionInfo =
          extractExecutionInfo(spawnResults.get(0), testResultDataBuilder);
      StandaloneTestResult standaloneTestResult =
          StandaloneTestResult.builder()
              .setSpawnResults(spawnResults)
              // We return the TestResultData.Builder rather than the finished TestResultData
              // instance, as we may have to rename the output files in case the test needs to be
              // rerun (if it failed here _and_ is marked flaky _and_ the number of flaky attempts
              // is larger than 1).
              .setTestResultDataBuilder(testResultDataBuilder)
              .setExecutionInfo(executionInfo)
              .build();
      return TestAttemptContinuation.of(standaloneTestResult);
    }
  }

  private final class BazelXmlCreationContinuation extends TestAttemptContinuation {
    private final ResolvedPaths resolvedPaths;
    private final FileOutErr fileOutErr;
    private final TestResultData.Builder builder;
    private final List<SpawnResult> primarySpawnResults;
    private final SpawnContinuation spawnContinuation;

    BazelXmlCreationContinuation(
        ResolvedPaths resolvedPaths,
        FileOutErr fileOutErr,
        TestResultData.Builder builder,
        List<SpawnResult> primarySpawnResults,
        SpawnContinuation spawnContinuation) {
      this.resolvedPaths = resolvedPaths;
      this.fileOutErr = fileOutErr;
      this.builder = builder;
      this.primarySpawnResults = primarySpawnResults;
      this.spawnContinuation = spawnContinuation;
    }

    @Nullable
    @Override
    public ListenableFuture<?> getFuture() {
      return spawnContinuation.getFuture();
    }

    @Override
    public TestAttemptContinuation execute() throws InterruptedException, ExecException {
      SpawnContinuation nextContinuation;
      try {
        nextContinuation = spawnContinuation.execute();
        if (!nextContinuation.isDone()) {
          return new BazelXmlCreationContinuation(
              resolvedPaths, fileOutErr, builder, primarySpawnResults, nextContinuation);
        }
      } catch (ExecException | InterruptedException e) {
        closeSuppressed(e, fileOutErr);
        throw e;
      }

      ImmutableList.Builder<SpawnResult> spawnResults = ImmutableList.builder();
      spawnResults.addAll(primarySpawnResults);
      spawnResults.addAll(nextContinuation.get());

      Path xmlOutputPath = resolvedPaths.getXmlOutputPath();
      TestCase details = parseTestResult(xmlOutputPath);
      if (details != null) {
        builder.setTestCase(details);
      }

      BuildEventStreamProtos.TestResult.ExecutionInfo executionInfo =
          extractExecutionInfo(primarySpawnResults.get(0), builder);
      StandaloneTestResult standaloneTestResult =
          StandaloneTestResult.builder()
              .setSpawnResults(spawnResults.build())
              // We return the TestResultData.Builder rather than the finished TestResultData
              // instance, as we may have to rename the output files in case the test needs to be
              // rerun (if it failed here _and_ is marked flaky _and_ the number of flaky attempts
              // is larger than 1).
              .setTestResultDataBuilder(builder)
              .setExecutionInfo(executionInfo)
              .build();
      return TestAttemptContinuation.of(standaloneTestResult);
    }
  }

  private final class BazelCoveragePostProcessingContinuation extends TestAttemptContinuation {
    private final ResolvedPaths resolvedPaths;
    @Nullable private final TestMetadataHandler testMetadataHandler;
    private final FileOutErr fileOutErr;
    private final Closeable streamed;
    private final TestResultData.Builder testResultDataBuilder;
    private final ImmutableList<SpawnResult> primarySpawnResults;
    private final SpawnContinuation spawnContinuation;
    private final TestRunnerAction testAction;
    private final ActionExecutionContext actionExecutionContext;
    private final Spawn spawn;

    BazelCoveragePostProcessingContinuation(
        TestRunnerAction testAction,
        @Nullable TestMetadataHandler testMetadataHandler,
        ActionExecutionContext actionExecutionContext,
        Spawn spawn,
        ResolvedPaths resolvedPaths,
        FileOutErr fileOutErr,
        Closeable streamed,
        TestResultData.Builder testResultDataBuilder,
        ImmutableList<SpawnResult> primarySpawnResults,
        SpawnContinuation spawnContinuation) {
      this.testAction = testAction;
      this.testMetadataHandler = testMetadataHandler;
      this.actionExecutionContext = actionExecutionContext;
      this.spawn = spawn;
      this.resolvedPaths = resolvedPaths;
      this.fileOutErr = fileOutErr;
      this.streamed = streamed;
      this.testResultDataBuilder = testResultDataBuilder;
      this.primarySpawnResults = primarySpawnResults;
      this.spawnContinuation = spawnContinuation;
    }

    @Nullable
    @Override
    public ListenableFuture<?> getFuture() {
      return spawnContinuation.getFuture();
    }

    @Override
    public TestAttemptContinuation execute() throws InterruptedException, ExecException {
      SpawnContinuation nextContinuation = null;
      try {
        nextContinuation = spawnContinuation.execute();
        if (!nextContinuation.isDone()) {
          return new BazelCoveragePostProcessingContinuation(
              testAction,
              testMetadataHandler,
              actionExecutionContext,
              spawn,
              resolvedPaths,
              fileOutErr,
              streamed,
              testResultDataBuilder,
              ImmutableList.<SpawnResult>builder()
                  .addAll(primarySpawnResults)
                  .addAll(nextContinuation.get())
                  .build(),
              nextContinuation);
        }
      } catch (SpawnExecException e) {
        if (e.isCatastrophic()) {
          closeSuppressed(e, streamed);
          closeSuppressed(e, fileOutErr);
          throw e;
        }
        if (!e.getSpawnResult().setupSuccess()) {
          closeSuppressed(e, streamed);
          closeSuppressed(e, fileOutErr);
          // Rethrow as the test could not be run and thus there's no point in retrying.
          throw e;
        }
        testResultDataBuilder
            .setCachable(e.getSpawnResult().status().isConsideredUserError())
            .setTestPassed(false)
            .setStatus(e.hasTimedOut() ? BlazeTestStatus.TIMEOUT : BlazeTestStatus.FAILED);
      } catch (ExecException | InterruptedException e) {
        closeSuppressed(e, fileOutErr);
        closeSuppressed(e, streamed);
        throw e;
      }

      return new BazelTestAttemptContinuation(
          testAction,
          testMetadataHandler,
          actionExecutionContext,
          spawn,
          resolvedPaths,
          fileOutErr,
          streamed,
          /* startTimeMillis= */ 0,
          nextContinuation,
          testResultDataBuilder,
          primarySpawnResults);
    }
  }
}
