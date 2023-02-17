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
package com.google.devtools.build.lib.util;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CommandFailureUtils}. */
@RunWith(JUnit4.class)
public class CommandFailureUtilsTest {

  @Test
  public void describeCommandFailure() throws Exception {
    String target = "//foo:bar";
    String[] args = new String[3];
    args[0] = "/bin/sh";
    args[1] = "-c";
    args[2] = "echo Some errors 1>&2; echo Some output; exit 42";
    Map<String, String> env = new LinkedHashMap<>();
    env.put("FOO", "foo");
    env.put("PATH", "/usr/bin:/bin:/sbin");
    String cwd = null;
    PlatformInfo executionPlatform =
        PlatformInfo.builder().setLabel(Label.parseAbsoluteUnchecked("//platform:exec")).build();
    String message =
        CommandFailureUtils.describeCommandFailure(
            false,
            Arrays.asList(args),
            env,
            cwd,
            "cfg12345",
            target,
            executionPlatform.label().toString());
    assertThat(message)
        .isEqualTo(
            "sh failed: error executing command (from target //foo:bar) "
                + "/bin/sh -c 'echo Some errors 1>&2; echo Some output; exit 42'");
  }

  @Test
  public void describeCommandFailure_verbose() throws Exception {
    String target = "//foo:bar";
    String[] args = new String[3];
    args[0] = "/bin/sh";
    args[1] = "-c";
    args[2] = "echo Some errors 1>&2; echo Some output; exit 42";
    Map<String, String> env = new LinkedHashMap<>();
    env.put("FOO", "foo");
    env.put("PATH", "/usr/bin:/bin:/sbin");
    String cwd = null;
    PlatformInfo executionPlatform =
        PlatformInfo.builder().setLabel(Label.parseAbsoluteUnchecked("//platform:exec")).build();
    String message =
        CommandFailureUtils.describeCommandFailure(
            true,
            Arrays.asList(args),
            env,
            cwd,
            "cfg12345",
            target,
            executionPlatform.label().toString());
    assertThat(message)
        .isEqualTo(
            "sh failed: error executing command (from target //foo:bar) \n"
                + "  (exec env - \\\n"
                + "    FOO=foo \\\n"
                + "    PATH=/usr/bin:/bin:/sbin \\\n"
                + "  /bin/sh -c 'echo Some errors 1>&2; echo Some output; exit 42')\n"
                + "# Configuration: cfg12345\n"
                + "# Execution platform: //platform:exec");
  }

  @Test
  public void describeCommandFailure_longMessage() throws Exception {
    String target = "//foo:bar";
    String[] args = new String[40];
    args[0] = "some_command";
    for (int i = 1; i < args.length; i++) {
      args[i] = "arg" + i;
    }
    args[7] = "with spaces"; // Test embedded spaces in argument.
    args[9] = "*";           // Test shell meta characters.
    Map<String, String> env = new LinkedHashMap<>();
    env.put("FOO", "foo");
    env.put("PATH", "/usr/bin:/bin:/sbin");
    String cwd = "/my/working/directory";
    PlatformInfo executionPlatform =
        PlatformInfo.builder().setLabel(Label.parseAbsoluteUnchecked("//platform:exec")).build();
    String message =
        CommandFailureUtils.describeCommandFailure(
            false,
            Arrays.asList(args),
            env,
            cwd,
            "cfg12345",
            target,
            executionPlatform.label().toString());
    assertThat(message)
        .isEqualTo(
            "some_command failed: error executing command (from target //foo:bar) "
                + "some_command arg1 arg2 arg3 arg4 arg5 arg6 'with spaces' arg8 '*' arg10 "
                + "arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 "
                + "arg19 arg20 arg21 arg22 arg23 arg24 arg25 arg26 "
                + "arg27 arg28 arg29 arg30 arg31 "
                + "... (remaining 8 arguments skipped)");
  }

  @Test
  public void describeCommandFailure_longMessage_verbose() throws Exception {
    String target = "//foo:bar";
    String[] args = new String[40];
    args[0] = "some_command";
    for (int i = 1; i < args.length; i++) {
      args[i] = "arg" + i;
    }
    args[7] = "with spaces"; // Test embedded spaces in argument.
    args[9] = "*"; // Test shell meta characters.
    Map<String, String> env = new LinkedHashMap<>();
    env.put("FOO", "foo");
    env.put("PATH", "/usr/bin:/bin:/sbin");
    String cwd = "/my/working/directory";
    PlatformInfo executionPlatform =
        PlatformInfo.builder().setLabel(Label.parseAbsoluteUnchecked("//platform:exec")).build();
    String message =
        CommandFailureUtils.describeCommandFailure(
            true,
            Arrays.asList(args),
            env,
            cwd,
            "cfg12345",
            target,
            executionPlatform.label().toString());
    assertThat(message)
        .isEqualTo(
            "some_command failed: error executing command (from target //foo:bar) \n"
                + "  (cd /my/working/directory && \\\n"
                + "  exec env - \\\n"
                + "    FOO=foo \\\n"
                + "    PATH=/usr/bin:/bin:/sbin \\\n"
                + "  some_command arg1 arg2 arg3 arg4 arg5 arg6 'with spaces' arg8 '*' arg10 "
                + "arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 "
                + "arg19 arg20 arg21 arg22 arg23 arg24 arg25 arg26 "
                + "arg27 arg28 arg29 arg30 arg31 arg32 arg33 arg34 "
                + "arg35 arg36 arg37 arg38 arg39)\n"
                + "# Configuration: cfg12345\n"
                + "# Execution platform: //platform:exec");
  }

  @Test
  public void describeCommandFailure_singleSkippedArgument() throws Exception {
    String target = "//foo:bar";
    String[] args = new String[35]; // Long enough to make us skip 1 argument below.
    args[0] = "some_command";
    for (int i = 1; i < args.length; i++) {
      args[i] = "arg" + i;
    }
    Map<String, String> env = new LinkedHashMap<>();
    String cwd = "/my/working/directory";
    PlatformInfo executionPlatform =
        PlatformInfo.builder().setLabel(Label.parseAbsoluteUnchecked("//platform:exec")).build();
    String message =
        CommandFailureUtils.describeCommandFailure(
            false,
            Arrays.asList(args),
            env,
            cwd,
            "cfg12345",
            target,
            executionPlatform.label().toString());
    assertThat(message)
        .isEqualTo(
            "some_command failed: error executing command (from target //foo:bar) some_command"
                + " arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9"
                + " arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 arg21 arg22"
                + " arg23 arg24 arg25 arg26 arg27 arg28 arg29 arg30 arg31 arg32 arg33 ..."
                + " (remaining 1 argument skipped)");
  }

  @Test
  public void describeCommandPrettyPrintArgs() throws Exception {

    String[] args = new String[6];
    args[0] = "some_command";
    for (int i = 1; i < args.length; i++) {
      args[i] = "arg" + i;
    }
    args[3] = "with spaces"; // Test embedded spaces in argument.
    args[4] = "*";           // Test shell meta characters.

    Map<String, String> env = new LinkedHashMap<>();
    env.put("FOO", "foo");
    env.put("PATH", "/usr/bin:/bin:/sbin");

    String cwd = "/my/working/directory";
    PlatformInfo executionPlatform =
        PlatformInfo.builder().setLabel(Label.parseAbsoluteUnchecked("//platform:exec")).build();
    String message =
        CommandFailureUtils.describeCommand(
            CommandDescriptionForm.COMPLETE,
            true,
            Arrays.asList(args),
            env,
            cwd,
            "cfg12345",
            executionPlatform.label().toString());

    assertThat(message)
        .isEqualTo(
            "(cd /my/working/directory && \\\n"
                + "  exec env - \\\n"
                + "    FOO=foo \\\n"
                + "    PATH=/usr/bin:/bin:/sbin \\\n"
                + "  some_command \\\n"
                + "    arg1 \\\n"
                + "    arg2 \\\n"
                + "    'with spaces' \\\n"
                + "    '*' \\\n"
                + "    arg5)\n"
                + "# Configuration: cfg12345\n"
                + "# Execution platform: //platform:exec");
  }
}
