// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository.starlark;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for complex functions of {@link StarlarkPath}. */
@RunWith(JUnit4.class)
public class StarlarkPathTest {

  private final BazelEvaluationTestCase ev = new BazelEvaluationTestCase();
  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path wd = FileSystemUtils.getWorkingDirectory(fs);

  @Before
  public void setup() throws Exception {
    ev.update("wd", new StarlarkPath(wd));
  }

  @Test
  public void testStarlarkPathGetChild() throws Exception {
    assertThat(ev.eval("wd.get_child()")).isEqualTo(new StarlarkPath(wd));
    assertThat(ev.eval("wd.get_child('foo')")).isEqualTo(new StarlarkPath(wd.getChild("foo")));
    assertThat(ev.eval("wd.get_child('a','b/c','/d/')"))
        .isEqualTo(new StarlarkPath(wd.getRelative("a/b/c/d")));
  }
}
