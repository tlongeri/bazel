// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import java.util.Locale;
import java.util.Map;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.StarlarkValue;

/** A Starlark structure to deliver information about the system we are running on. */
@StarlarkBuiltin(
    name = "repository_os",
    category = DocCategory.BUILTIN,
    doc = "Various data about the current platform Bazel is running on.")
@Immutable
final class StarlarkOS implements StarlarkValue {

  private final ImmutableMap<String, String> environ;

  StarlarkOS(Map<String, String> environ) {
    this.environ = ImmutableMap.copyOf(environ);
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  @StarlarkMethod(name = "environ", structField = true, doc = "The list of environment variables.")
  public ImmutableMap<String, String> getEnvironmentVariables() {
    return environ;
  }

  @StarlarkMethod(
      name = "name",
      structField = true,
      doc =
          "A string identifying the operating system Bazel is running on (the value of the"
              + " \"os.name\" Java property).")
  public String getName() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT);
  }

  @StarlarkMethod(
      name = "arch",
      structField = true,
      doc =
          "A string identifying the architecture Bazel is running on (the value of the \"os.arch\""
              + " Java property).")
  public String getArch() {
    return System.getProperty("os.arch").toLowerCase(Locale.ROOT);
  }
}
