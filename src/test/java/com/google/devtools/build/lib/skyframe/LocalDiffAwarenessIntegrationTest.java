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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.buildtool.util.SkyframeIntegrationTestBase;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.DelegateFileSystem;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.NotifyingHelper;
import com.google.devtools.common.options.OptionsBase;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for local diff awareness. A good place for general tests of Bazel's interactions with
 * "smart" filesystems, so that open-source changes don't break Google-internal features around
 * smart filesystems.
 */
@RunWith(JUnit4.class)
public class LocalDiffAwarenessIntegrationTest extends SkyframeIntegrationTestBase {
  private final Map<PathFragment, IOException> throwOnNextStatIfFound = new HashMap<>();

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(
            new BlazeModule() {
              @Override
              public void workspaceInit(
                  BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
                builder.addDiffAwarenessFactory(new LocalDiffAwareness.Factory(ImmutableList.of()));
              }

              @Override
              public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
                return ImmutableList.of(LocalDiffAwareness.Options.class);
              }
            });
  }

  @Override
  public FileSystem createFileSystem() throws Exception {
    return new DelegateFileSystem(super.createFileSystem()) {
      @Override
      protected FileStatus statIfFound(PathFragment path, boolean followSymlinks)
          throws IOException {
        IOException e = throwOnNextStatIfFound.remove(path);
        if (e != null) {
          throw e;
        }
        return super.statIfFound(path, followSymlinks);
      }
    };
  }

  @Before
  public void addOptions() {
    addOptions("--watchfs", "--experimental_windows_watchfs");
  }

  @After
  public void checkExceptionsThrown() {
    assertWithMessage("Injected exception(s) not thrown").that(throwOnNextStatIfFound).isEmpty();
  }

  @Test
  public void changedFile_detectsChange() throws Exception {
    // TODO(bazel-team): Understand why these tests are flaky on Mac. Probably real watchfs bug?
    Assume.assumeFalse(OS.DARWIN.equals(OS.getCurrent()));
    write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo hello > $@')");
    buildTarget("//foo");
    assertContents("hello", "//foo");
    write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo there > $@')");

    buildTarget("//foo");

    assertContents("there", "//foo");
  }

  @Test
  public void changedFile_statFails_throwsError() throws Exception {
    Assume.assumeFalse(OS.DARWIN.equals(OS.getCurrent()));
    write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo hello > $@')");
    buildTarget("//foo");
    assertContents("hello", "//foo");
    Path buildFile = write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo there > $@')");
    IOException injectedException = new IOException("oh no!");
    throwOnNextStatIfFound.put(buildFile.asFragment(), injectedException);

    AbruptExitException e = assertThrows(AbruptExitException.class, () -> buildTarget("//foo"));

    assertThat(e.getCause()).hasCauseThat().hasCauseThat().isSameInstanceAs(injectedException);
  }

  // This test doesn't use --watchfs functionality, but if the source filesystem doesn't offer diffs
  // Bazel must scan the full Skyframe graph anyway, so a bug in checking output files wouldn't be
  // detected without --watchfs.
  @Test
  public void ignoreOutputFilesThenCheckAgainDoesCheck() throws Exception {
    Path buildFile =
        write(
            "foo/BUILD",
            "genrule(name = 'foo', outs = ['out'], cmd = 'cp $< $@', srcs = ['link'])");
    Path outputFile = directories.getOutputBase().getChild("linkTarget");
    FileSystemUtils.writeContentAsLatin1(outputFile, "one");
    buildFile.getParentDirectory().getChild("link").createSymbolicLink(outputFile.asFragment());

    buildTarget("//foo:foo");

    assertContents("one", "//foo:foo");

    addOptions("--noexperimental_check_output_files");
    FileSystemUtils.writeContentAsLatin1(outputFile, "two");

    buildTarget("//foo:foo");

    assertContents("one", "//foo:foo");

    addOptions("--experimental_check_output_files");

    buildTarget("//foo:foo");

    assertContents("two", "//foo:foo");
  }

  @Test
  public void externalSymlink_doesNotTriggerFullGraphTraversal() throws Exception {
    addOptions("--symlink_prefix=/");
    AtomicInteger calledGetValues = new AtomicInteger(0);
    skyframeExecutor()
        .getEvaluator()
        .injectGraphTransformerForTesting(
            NotifyingHelper.makeNotifyingTransformer(
                (key, type, order, context) -> {
                  if (type == NotifyingHelper.EventType.GET_VALUES) {
                    calledGetValues.incrementAndGet();
                  }
                }));
    write(
        "hello/BUILD",
        "genrule(name='target', srcs = ['external'], outs=['out'], cmd='/bin/cat $(SRCS) > $@')");
    String externalLink = System.getenv("TEST_TMPDIR") + "/target";
    write(externalLink, "one");
    createSymlink(externalLink, "hello/external");

    // Trivial build: external symlink is not seen, so normal diff awareness is in play.
    buildTarget("//hello:BUILD");
    // New package path on first build triggers full-graph work.
    calledGetValues.set(0);
    // getValues() called during output file checking (although if an output service is able to
    // report modified files in practice there is no iteration).
    // If external repositories are being used, getValues called because of that too.
    // TODO(bazel-team): get rid of this when we can disable checks for external repositories.
    int numGetValuesInFullDiffAwarenessBuild =
        1 + ("bazel".equals(this.getRuntime().getProductName()) ? 1 : 0);

    buildTarget("//hello:BUILD");
    assertThat(calledGetValues.getAndSet(0)).isEqualTo(numGetValuesInFullDiffAwarenessBuild);

    // Now bring the external symlink into Bazel's awareness.
    buildTarget("//hello:target");
    assertContents("one", "//hello:target");
    assertThat(calledGetValues.getAndSet(0)).isEqualTo(numGetValuesInFullDiffAwarenessBuild);

    // Builds that follow a build containing an external file don't trigger a traversal.
    buildTarget("//hello:target");
    assertContents("one", "//hello:target");
    assertThat(calledGetValues.getAndSet(0)).isEqualTo(numGetValuesInFullDiffAwarenessBuild);

    write(externalLink, "two");

    buildTarget("//hello:target");
    // External file changes are tracked.
    assertContents("two", "//hello:target");
    assertThat(calledGetValues.getAndSet(0)).isEqualTo(numGetValuesInFullDiffAwarenessBuild);
  }
}
