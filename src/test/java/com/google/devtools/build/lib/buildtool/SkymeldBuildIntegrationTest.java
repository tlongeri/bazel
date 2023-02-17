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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.TestConstants.WORKSPACE_NAME;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.AnalysisPhaseCompleteEvent;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelEntityAnalysisConcludedEvent;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Integration tests for project Skymeld: interleaving Skyframe's analysis and execution phases. */
@RunWith(TestParameterInjector.class)
public class SkymeldBuildIntegrationTest extends BuildIntegrationTestCase {
  private AnalysisEventsSubscriber analysisEventsSubscriber;

  @Before
  public void setUp() {
    addOptions("--experimental_merged_skyframe_analysis_execution");
    this.analysisEventsSubscriber = new AnalysisEventsSubscriber();
    runtimeWrapper.registerSubscriber(analysisEventsSubscriber);
  }

  /** A simple rule that has srcs, deps and writes these attributes to its output. */
  private void writeMyRuleBzl() throws IOException {
    write(
        "foo/my_rule.bzl",
        "def _path(file):",
        "  return file.path",
        "def _impl(ctx):",
        "  inputs = depset(",
        "    ctx.files.srcs, transitive = [dep[DefaultInfo].files for dep in ctx.attr.deps])",
        "  output = ctx.actions.declare_file(ctx.attr.name + '.out')",
        "  command = 'echo $@ > %s' % (output.path)",
        "  args = ctx.actions.args()",
        "  args.add_all(inputs, map_each=_path)",
        "  ctx.actions.run_shell(",
        "    inputs = inputs,",
        "    outputs = [output],",
        "    command = command,",
        "    arguments = [args]",
        "  )",
        "  return DefaultInfo(files = depset([output]))",
        "",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files = True),",
        "    'deps': attr.label_list(providers = ['DefaultInfo']),",
        "  }",
        ")");
  }

  private void writeAnalysisFailureAspectBzl() throws IOException {
    write(
        "foo/aspect.bzl",
        "def _aspect_impl(target, ctx):",
        "  malformed",
        "",
        "analysis_err_aspect = aspect(implementation = _aspect_impl)");
  }

  private void writeExecutionFailureAspectBzl() throws IOException {
    write(
        "foo/aspect.bzl",
        "def _aspect_impl(target, ctx):",
        "  output = ctx.actions.declare_file('aspect_output')",
        "  ctx.actions.run_shell(",
        "    outputs = [output],",
        "    command = 'false',",
        "  )",
        "  return [OutputGroupInfo(",
        "    files = depset([output])",
        "  )]",
        "",
        "execution_err_aspect = aspect(implementation = _aspect_impl)");
  }

  private void writeEnvironmentRules(String... defaults) throws Exception {
    StringBuilder defaultsBuilder = new StringBuilder();
    for (String defaultEnv : defaults) {
      defaultsBuilder.append("'").append(defaultEnv).append("', ");
    }

    write(
        "buildenv/BUILD",
        "environment_group(",
        "    name = 'group',",
        "    environments = [':one', ':two'],",
        "    defaults = [" + defaultsBuilder + "])",
        "environment(name = 'one')",
        "environment(name = 'two')");
  }

  @CanIgnoreReturnValue
  private Path assertSingleOutputBuilt(String target) throws Exception {
    Path singleOutput = Iterables.getOnlyElement(getArtifacts(target)).getPath();
    assertThat(singleOutput.isFile()).isTrue();

    return singleOutput;
  }

  @Test
  public void nobuild_warning() throws Exception {
    writeMyRuleBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");
    addOptions("--nobuild");

    BuildResult result = buildTarget("//foo:foo");

    assertThat(result.getSuccess()).isTrue();
    events.assertContainsWarning(
        "--experimental_merged_skyframe_analysis_execution is incompatible with --nobuild and will"
            + " be ignored");
  }

  @Test
  public void multiTargetBuild_success() throws Exception {
    writeMyRuleBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'bar', srcs = ['bar.in'])",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");
    write("foo/bar.in");

    BuildResult result = buildTarget("//foo:foo", "//foo:bar");

    assertThat(result.getSuccess()).isTrue();
    assertSingleOutputBuilt("//foo:foo");
    assertSingleOutputBuilt("//foo:bar");

    assertThat(getLabelsOfAnalyzedTargets()).containsExactly("//foo:foo", "//foo:bar");
    assertThat(getLabelsOfBuiltTargets()).containsExactly("//foo:foo", "//foo:bar");

    assertThat(analysisEventsSubscriber.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2);
    assertSingleAnalysisPhaseCompleteEventWithLabels("//foo:foo", "//foo:bar");

    assertThat(directories.getOutputPath(WORKSPACE_NAME).getRelative("build-info.txt").isFile())
        .isTrue();
    assertThat(
            directories.getOutputPath(WORKSPACE_NAME).getRelative("build-changelist.txt").isFile())
        .isTrue();
  }

  @Test
  public void multiTargetNullIncrementalBuild_success() throws Exception {
    writeMyRuleBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'bar', srcs = ['bar.in'])",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");
    write("foo/bar.in");

    // First build, ignored.
    buildTarget("//foo:foo", "//foo:bar");
    BuildResult result = buildTarget("//foo:foo", "//foo:bar");

    assertThat(result.getSuccess()).isTrue();
    assertSingleOutputBuilt("//foo:foo");
    assertSingleOutputBuilt("//foo:bar");

    assertThat(directories.getOutputPath(WORKSPACE_NAME).getRelative("build-info.txt").isFile())
        .isTrue();
    assertThat(
        directories.getOutputPath(WORKSPACE_NAME).getRelative("build-changelist.txt").isFile())
        .isTrue();
  }

  @Test
  public void aspectAnalysisFailure_consistentWithNonSkymeld(
      @TestParameter boolean keepGoing, @TestParameter boolean mergedAnalysisExecution)
      throws Exception {
    addOptions("--keep_going=" + keepGoing);
    addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution);
    writeMyRuleBzl();
    writeAnalysisFailureAspectBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");

    addOptions("--aspects=//foo:aspect.bzl%analysis_err_aspect", "--output_groups=files");
    if (keepGoing) {
      assertThrows(BuildFailedException.class, () -> buildTarget("//foo:foo"));
    } else {
      assertThrows(ViewCreationFailedException.class, () -> buildTarget("//foo:foo"));
    }
    events.assertContainsError("compilation of module 'foo/aspect.bzl' failed");
  }

  @Test
  public void aspectExecutionFailure_consistentWithNonSkymeld(
      @TestParameter boolean keepGoing, @TestParameter boolean mergedAnalysisExecution)
      throws Exception {
    addOptions("--keep_going=" + keepGoing);
    addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution);
    writeMyRuleBzl();
    writeExecutionFailureAspectBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");

    addOptions("--aspects=//foo:aspect.bzl%execution_err_aspect", "--output_groups=files");
    assertThrows(BuildFailedException.class, () -> buildTarget("//foo:foo"));
    events.assertContainsError(
        "Action foo/aspect_output failed: (Exit 1): bash failed: error executing command");
  }

  @Test
  public void targetExecutionFailure_consistentWithNonSkymeld(
      @TestParameter boolean keepGoing, @TestParameter boolean mergedAnalysisExecution)
      throws Exception {
    addOptions("--keep_going=" + keepGoing);
    addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution);
    writeMyRuleBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'execution_failure', srcs = ['missing'])",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");

    assertThrows(
        BuildFailedException.class, () -> buildTarget("//foo:foo", "//foo:execution_failure"));
    if (keepGoing) {
      assertSingleOutputBuilt("//foo:foo");
    }
    events.assertContainsError(
        "Action foo/execution_failure.out failed: missing input file '//foo:missing'");
  }

  @Test
  public void targetAnalysisFailure_consistentWithNonSkymeld(
      @TestParameter boolean keepGoing, @TestParameter boolean mergedAnalysisExecution)
      throws Exception {
    addOptions("--keep_going=" + keepGoing);
    addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution);
    writeMyRuleBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'analysis_failure', srcs = ['foo.in'], deps = [':missing'])",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");

    if (keepGoing) {
      assertThrows(
          BuildFailedException.class, () -> buildTarget("//foo:foo", "//foo:analysis_failure"));
      assertSingleOutputBuilt("//foo:foo");
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () -> buildTarget("//foo:foo", "//foo:analysis_failure"));
    }
    events.assertContainsError("rule '//foo:missing' does not exist");
  }

  @Test
  public void analysisAndExecutionFailure_keepGoing_bothReported() throws Exception {
    addOptions("--keep_going");
    writeMyRuleBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'execution_failure', srcs = ['missing'])",
        "my_rule(name = 'analysis_failure', srcs = ['foo.in'], deps = [':missing'])");
    write("foo/foo.in");

    assertThrows(
        BuildFailedException.class,
        () -> buildTarget("//foo:analysis_failure", "//foo:execution_failure"));
    events.assertContainsError(
        "Action foo/execution_failure.out failed: missing input file '//foo:missing'");
    events.assertContainsError("rule '//foo:missing' does not exist");

    assertThat(getLabelsOfAnalyzedTargets()).contains("//foo:execution_failure");
    assertThat(getLabelsOfBuiltTargets()).isEmpty();
  }

  @Test
  public void symlinkPlantedLocalAction_success() throws Exception {
    addOptions("--spawn_strategy=standalone");
    write(
        "foo/BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['foo.out'],",
        "  cmd = 'cp $< $@'",
        ")");
    write("foo/foo.in");

    BuildResult result = buildTarget("//foo:foo");

    assertThat(result.getSuccess()).isTrue();
    assertSingleOutputBuilt("//foo:foo");
  }

  @Test
  public void symlinksPlanted() throws Exception {
    Path execroot = directories.getExecRoot(directories.getWorkspace().getBaseName());
    writeMyRuleBzl();
    Path fooDir =
        write(
                "foo/BUILD",
                "load('//foo:my_rule.bzl', 'my_rule')",
                "my_rule(name = 'foo', srcs = ['foo.in'])")
            .getParentDirectory();
    write("foo/foo.in");
    Path unusedDir = write("unused/dummy").getParentDirectory();

    // Before the build: no symlink.
    assertThat(execroot.getRelative("foo").exists()).isFalse();

    buildTarget("//foo:foo");

    // After the build: symlinks to the source directory, even unused packages.
    assertThat(execroot.getRelative("foo").resolveSymbolicLinks()).isEqualTo(fooDir);
    assertThat(execroot.getRelative("unused").resolveSymbolicLinks()).isEqualTo(unusedDir);
  }

  @Test
  public void symlinksReplantedEachBuild() throws Exception {
    Path execroot = directories.getExecRoot(directories.getWorkspace().getBaseName());
    writeMyRuleBzl();
    Path fooDir =
        write(
                "foo/BUILD",
                "load('//foo:my_rule.bzl', 'my_rule')",
                "my_rule(name = 'foo', srcs = ['foo.in'])")
            .getParentDirectory();
    write("foo/foo.in");
    Path unusedDir = write("unused/dummy").getParentDirectory();

    buildTarget("//foo:foo");

    // After the 1st build: symlinks to the source directory, even unused packages.
    assertThat(execroot.getRelative("foo").resolveSymbolicLinks()).isEqualTo(fooDir);
    assertThat(execroot.getRelative("unused").resolveSymbolicLinks()).isEqualTo(unusedDir);

    unusedDir.deleteTree();

    buildTarget("//foo:foo");

    // After the 2nd build: symlink to unusedDir is gone, since the package itself was deleted.
    assertThat(execroot.getRelative("foo").resolveSymbolicLinks()).isEqualTo(fooDir);
    assertThat(execroot.getRelative("unused").exists()).isFalse();
  }

  @Test
  public void targetAnalysisFailure_skymeld_correctAnalysisEvents(@TestParameter boolean keepGoing)
      throws Exception {
    addOptions("--keep_going=" + keepGoing);
    addOptions("--experimental_merged_skyframe_analysis_execution");
    writeMyRuleBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'analysis_failure', srcs = ['foo.in'], deps = [':missing'])",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");

    if (keepGoing) {
      assertThrows(
          BuildFailedException.class, () -> buildTarget("//foo:foo", "//foo:analysis_failure"));

      assertThat(analysisEventsSubscriber.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2);
      assertSingleAnalysisPhaseCompleteEventWithLabels("//foo:foo");
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () -> buildTarget("//foo:foo", "//foo:analysis_failure"));
      assertThat(analysisEventsSubscriber.getAnalysisPhaseCompleteEvents()).isEmpty();
    }
  }

  @Test
  public void aspectAnalysisFailure_skymeld_correctAnalysisEvents(@TestParameter boolean keepGoing)
      throws Exception {
    addOptions("--keep_going=" + keepGoing);
    writeMyRuleBzl();
    writeAnalysisFailureAspectBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");

    addOptions("--aspects=//foo:aspect.bzl%analysis_err_aspect", "--output_groups=files");
    if (keepGoing) {
      assertThrows(BuildFailedException.class, () -> buildTarget("//foo:foo"));
      assertThat(analysisEventsSubscriber.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2);
      assertSingleAnalysisPhaseCompleteEventWithLabels("//foo:foo");
    } else {
      assertThrows(ViewCreationFailedException.class, () -> buildTarget("//foo:foo"));
      assertThat(analysisEventsSubscriber.getAnalysisPhaseCompleteEvents()).isEmpty();
    }
    events.assertContainsError("compilation of module 'foo/aspect.bzl' failed");
  }

  @Test
  public void targetSkipped_skymeld_correctAnalysisEvents(@TestParameter boolean keepGoing)
      throws Exception {
    writeEnvironmentRules();
    addOptions("--keep_going=" + keepGoing);
    write(
        "foo/BUILD",
        "sh_library(name = 'good_bar', srcs = ['bar.sh'], compatible_with = ['//buildenv:one'])",
        "sh_library(name = 'bad_bar', srcs = ['bar.sh'], compatible_with = ['//buildenv:two'])");
    write("foo/bar.sh");
    addOptions("--target_environment=//buildenv:one");
    if (keepGoing) {
      assertThrows(
          BuildFailedException.class, () -> buildTarget("//foo:good_bar", "//foo:bad_bar"));

      assertThat(analysisEventsSubscriber.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2);
      assertThat(analysisEventsSubscriber.getAnalysisPhaseCompleteEvents()).hasSize(1);
      AnalysisPhaseCompleteEvent analysisPhaseCompleteEvent =
          Iterables.getOnlyElement(analysisEventsSubscriber.getAnalysisPhaseCompleteEvents());
      assertThat(analysisPhaseCompleteEvent.getTimeInMs()).isGreaterThan(0);
      assertThat(getLabelsOfAnalyzedTargets(analysisPhaseCompleteEvent))
          .containsExactly("//foo:good_bar", "//foo:bad_bar");
    } else {
      assertThrows(
          ViewCreationFailedException.class, () -> buildTarget("//foo:good_bar", "//foo:bad_bar"));
      assertThat(analysisEventsSubscriber.getAnalysisPhaseCompleteEvents()).isEmpty();
    }
  }

  @Test
  public void targetWithNoConfiguration_success() throws Exception {
    write("foo/BUILD", "exports_files(['bar.txt'])");
    write("foo/bar.txt", "This is just a test file to pretend to build.");
    BuildResult result = buildTarget("//foo:bar.txt");

    assertThat(result.getSuccess()).isTrue();
  }

  @Test
  public void multiplePackagePath_gracefulExit() throws Exception {
    write("foo/BUILD", "sh_binary(name = 'root', srcs = ['root.sh'])");
    write("foo/root.sh");
    write("otherroot/bar/BUILD", "cc_library(name = 'bar')");
    addOptions("--package_path=%workspace%:otherroot");
    InvalidConfigurationException e =
        assertThrows(InvalidConfigurationException.class, () -> buildTarget("//foo:root", "//bar"));

    assertThat(e.getDetailedExitCode().getExitCode().getNumericExitCode()).isEqualTo(2);
    assertThat(e.getMessage())
        .contains(
            "--experimental_merged_skyframe_analysis_execution requires a single package path"
                + " entry");
  }

  // Regression test for b/245919888.
  @Test
  public void outputFileRemoved_regeneratedWithIncrementalBuild() throws Exception {
    writeMyRuleBzl();
    write(
        "foo/BUILD",
        "load('//foo:my_rule.bzl', 'my_rule')",
        "my_rule(name = 'foo', srcs = ['foo.in'])");
    write("foo/foo.in");

    BuildResult result = buildTarget("//foo:foo");

    assertThat(result.getSuccess()).isTrue();
    Path fooOut = assertSingleOutputBuilt("//foo:foo");

    fooOut.delete();

    BuildResult incrementalBuild = buildTarget("//foo:foo");

    assertThat(incrementalBuild.getSuccess()).isTrue();
    assertSingleOutputBuilt("//foo:foo");
  }

  // Regression test for b/245922900.
  @Test
  public void executionFailure_discardAnalysisCache_doesNotCrash() throws Exception {
    addOptions("--experimental_merged_skyframe_analysis_execution", "--discard_analysis_cache");
    writeExecutionFailureAspectBzl();
    write(
        "foo/BUILD",
        "cc_library(name = 'foo', srcs = ['foo.cc'], deps = [':bar'])",
        "cc_library(name = 'bar', srcs = ['bar.cc'])");
    write("foo/foo.cc");
    write("foo/bar.cc");
    addOptions("--aspects=//foo:aspect.bzl%execution_err_aspect", "--output_groups=files");

    // Verify that the build did not crash.
    assertThrows(BuildFailedException.class, () -> buildTarget("//foo:foo"));
    events.assertContainsError(
        "Action foo/aspect_output failed: (Exit 1): bash failed: error executing command");
  }

  private void assertSingleAnalysisPhaseCompleteEventWithLabels(String... labels) {
    assertThat(analysisEventsSubscriber.getAnalysisPhaseCompleteEvents()).hasSize(1);
    AnalysisPhaseCompleteEvent analysisPhaseCompleteEvent =
        Iterables.getOnlyElement(analysisEventsSubscriber.getAnalysisPhaseCompleteEvents());
    assertThat(analysisPhaseCompleteEvent.getTimeInMs()).isGreaterThan(0);
    assertThat(getLabelsOfAnalyzedTargets(analysisPhaseCompleteEvent))
        .containsExactlyElementsIn(labels);
  }

  private static ImmutableSet<String> getLabelsOfAnalyzedTargets(AnalysisPhaseCompleteEvent event) {
    return event.getTopLevelTargets().stream()
        .map(x -> x.getOriginalLabel().getCanonicalForm())
        .collect(toImmutableSet());
  }

  private static final class AnalysisEventsSubscriber {

    private final Set<TopLevelEntityAnalysisConcludedEvent> topLevelEntityAnalysisConcludedEvents =
        Sets.newConcurrentHashSet();

    private final Set<AnalysisPhaseCompleteEvent> analysisPhaseCompleteEvents =
        Sets.newConcurrentHashSet();

    AnalysisEventsSubscriber() {}

    @Subscribe
    void recordTopLevelEntityAnalysisConcludedEvent(TopLevelEntityAnalysisConcludedEvent event) {
      topLevelEntityAnalysisConcludedEvents.add(event);
    }

    @Subscribe
    void recordAnalysisPhaseCompleteEvent(AnalysisPhaseCompleteEvent event) {
      analysisPhaseCompleteEvents.add(event);
    }

    public Set<TopLevelEntityAnalysisConcludedEvent> getTopLevelEntityAnalysisConcludedEvents() {
      return topLevelEntityAnalysisConcludedEvents;
    }

    public Set<AnalysisPhaseCompleteEvent> getAnalysisPhaseCompleteEvents() {
      return analysisPhaseCompleteEvents;
    }
  }
}
