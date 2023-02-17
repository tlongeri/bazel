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
package com.google.devtools.build.lib.remote;

import static com.google.devtools.build.lib.testutil.TestUtils.tmpDirFile;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.BlockWaitingModule;
import com.google.devtools.build.lib.runtime.BuildSummaryStatsModule;
import com.google.devtools.build.lib.standalone.StandaloneModule;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DiskCacheIntegrationTest extends BuildIntegrationTestCase {

  private static PathFragment getDiskCacheDir() {
    PathFragment testTmpDir = PathFragment.create(tmpDirFile().getAbsolutePath());
    return testTmpDir.getRelative("disk_cache");
  }

  @Override
  protected void setupOptions() throws Exception {
    super.setupOptions();

    addOptions("--disk_cache=" + getDiskCacheDir());
  }

  @After
  public void tearDown() throws IOException {
    getWorkspace().getFileSystem().getPath(getDiskCacheDir()).deleteTree();
  }

  @Override
  protected ImmutableList<BlazeModule> getSpawnModules() {
    return ImmutableList.<BlazeModule>builder()
        .addAll(super.getSpawnModules())
        .add(new StandaloneModule())
        .build();
  }

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(new RemoteModule())
        .addBlazeModule(new BuildSummaryStatsModule())
        .addBlazeModule(new BlockWaitingModule());
  }

  @Test
  public void hitDiskCache() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['foo.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo.out', 'bar.in'],",
        "  outs = ['foobar.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")");
    write("foo.in", "foo");
    write("bar.in", "bar");
    buildTarget("//:foobar");
    cleanAndRestartServer();

    buildTarget("//:foobar");

    events.assertContainsInfo("2 disk cache hit");
  }

  private void blobsReferencedInAAreMissingCasIgnoresAc(String... additionalOptions)
      throws Exception {
    // Arrange: Prepare the workspace and populate disk cache
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['foo.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo.out', 'bar.in'],",
        "  outs = ['foobar.out'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")");
    write("foo.in", "foo");
    write("bar.in", "bar");
    addOptions(additionalOptions);
    buildTarget("//:foobar");
    cleanAndRestartServer();

    // Act: Delete blobs in CAS from disk cache and do a clean build
    getWorkspace().getFileSystem().getPath(getDiskCacheDir().getRelative("cas")).deleteTree();
    addOptions(additionalOptions);
    buildTarget("//:foobar");

    // Assert: Should ignore the stale AC and rerun the generating action
    events.assertDoesNotContainEvent("disk cache hit");
  }

  @Test
  public void blobsReferencedInAcAreMissingCas_ignoresAc() throws Exception {
    blobsReferencedInAAreMissingCasIgnoresAc();
  }

  @Test
  public void bwob_blobsReferencedInAcAreMissingCas_ignoresAc() throws Exception {
    blobsReferencedInAAreMissingCasIgnoresAc("--remote_download_minimal");
  }

  private void cleanAndRestartServer() throws Exception {
    getOutputBase().getRelative("action_cache").deleteTreesBelow();
    // Simulates a server restart
    createRuntimeWrapper();
  }
}
