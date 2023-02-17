// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkbuildapi.test;

import com.google.devtools.build.lib.starlarkbuildapi.RunEnvironmentInfoApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkValue;

/** Helper module for accessing test infrastructure. */
@StarlarkBuiltin(
    name = "testing",
    doc = "Helper methods for Starlark to access testing infrastructure.")
public interface TestingModuleApi extends StarlarkValue {

  // TODO(bazel-team): Change this function to be the actual ExecutionInfo.PROVIDER.
  @StarlarkMethod(
      name = "ExecutionInfo",
      doc =
          "Creates a new execution info provider. Use this provider to specify special"
              + "environments requirements needed to run tests.",
      parameters = {
        @Param(
            name = "requirements",
            named = false,
            positional = true,
            doc =
                "A map of string keys and values to indicate special execution requirements,"
                    + " such as hardware platforms, etc. These keys and values are passed to the"
                    + " executor of the test action as parameters to configure the execution"
                    + " environment.")
      })
  ExecutionInfoApi executionInfo(Dict<?, ?> requirements // <String, String> expected
      ) throws EvalException;

  @StarlarkMethod(
      name = "TestEnvironment",
      doc =
          "<b>Deprecated: Use RunEnvironmentInfo instead.</b> Creates a new test environment "
              + "provider. Use this provider to specify extra environment variables to be made "
              + "available during test execution.",
      parameters = {
        @Param(
            name = "environment",
            named = true,
            positional = true,
            doc =
                "A map of string keys and values that represent environment variables and their"
                    + " values. These will be made available during the test execution."),
        @Param(
            name = "inherited_environment",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            named = true,
            positional = true,
            doc =
                "A sequence of names of environment variables. These variables are made available"
                    + " during the test execution with their current value taken from the shell"
                    + " environment. If a variable is contained in both <code>environment</code>"
                    + " and <code>inherited_environment</code>, the value inherited from the"
                    + " shell environment will take precedence if set.")
      })
  RunEnvironmentInfoApi testEnvironment(
      Dict<?, ?> environment, // <String, String> expected
      Sequence<?> inheritedEnvironment /* <String> expected */)
      throws EvalException;
}
