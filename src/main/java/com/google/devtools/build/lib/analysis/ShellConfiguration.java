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
package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.OptionsUtils.PathFragmentConverter;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import java.util.Map;
import java.util.function.Function;

/** A configuration fragment that tells where the shell is. */
@RequiresOptions(options = {ShellConfiguration.Options.class})
public class ShellConfiguration extends Fragment {

  private static Map<OS, PathFragment> shellExecutables;

  private static final ConstraintSettingInfo OS_CONSTRAINT_SETTING =
      ConstraintSettingInfo.create(
          Label.parseAbsoluteUnchecked("@platforms//os:os"));

  private static Function<Options, PathFragment> optionsBasedDefault;

  /**
   * Injects a function for retrieving the default sh path from build options, and a map for
   * locating the correct sh executable given a set of target constraints.
   */
  public static void injectShellExecutableFinder(
      Function<Options, PathFragment> shellFromOptionsFinder, Map<OS, PathFragment> osToShellMap) {
    // It'd be nice not to have to set a global static field. But there are so many disparate calls
    // to getShellExecutables() (in both the build's analysis phase and in the run command) that
    // feeding this through instance variables is unwieldy. Fortunately this info is a function of
    // the Blaze implementation and not something that might change between builds.
    optionsBasedDefault = shellFromOptionsFinder;
    shellExecutables = osToShellMap;
  }

  /**
   * Injects a map for locating the correct sh executable given a set of target constraints. Assumes
   * no options-based default shell.
   */
  public static void injectShellExecutableFinder(Map<OS, PathFragment> osToShellMap) {
    optionsBasedDefault = (options) -> null;
    shellExecutables = osToShellMap;
  }
  // Standard mapping between OS and the corresponding platform constraints.
  static final ImmutableMap<OS, ConstraintValueInfo> OS_TO_CONSTRAINTS =
      ImmutableMap.<OS, ConstraintValueInfo>builder()
          .put(
              OS.DARWIN,
              ConstraintValueInfo.create(
                  OS_CONSTRAINT_SETTING,
                  Label.parseAbsoluteUnchecked("@platforms//os:osx")))
          .put(
              OS.WINDOWS,
              ConstraintValueInfo.create(
                  OS_CONSTRAINT_SETTING,
                  Label.parseAbsoluteUnchecked("@platforms//os:windows")))
          .put(
              OS.FREEBSD,
              ConstraintValueInfo.create(
                  OS_CONSTRAINT_SETTING,
                  Label.parseAbsoluteUnchecked("@platforms//os:freebsd")))
          .put(
              OS.OPENBSD,
              ConstraintValueInfo.create(
                  OS_CONSTRAINT_SETTING,
                  Label.parseAbsoluteUnchecked("@platforms//os:openbsd")))
          .put(
              OS.UNKNOWN,
              ConstraintValueInfo.create(
                  OS_CONSTRAINT_SETTING,
                  Label.parseAbsoluteUnchecked("@platforms//os:none")))
          .buildOrThrow();

  private final boolean useShBinaryStubScript;

  private final PathFragment defaultShellExecutableFromOptions;

  public ShellConfiguration(BuildOptions buildOptions) {
    this.defaultShellExecutableFromOptions =
        optionsBasedDefault.apply(buildOptions.get(Options.class));
    this.useShBinaryStubScript = buildOptions.get(Options.class).useShBinaryStubScript;
  }

  public static Map<OS, PathFragment> getShellExecutables() {
    return shellExecutables;
  }

  /* Returns a function for retrieving the default shell from build options. */
  public PathFragment getOptionsBasedDefault() {
    return defaultShellExecutableFromOptions;
  }

  public boolean useShBinaryStubScript() {
    return useShBinaryStubScript;
  }

  /** An option that tells Bazel where the shell is. */
  public static class Options extends FragmentOptions {
    @Option(
        name = "shell_executable",
        converter = PathFragmentConverter.class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        help =
            "Absolute path to the shell executable for Bazel to use. If this is unset, but the "
                + "BAZEL_SH environment variable is set on the first Bazel invocation (that starts "
                + "up a Bazel server), Bazel uses that. If neither is set, Bazel uses a hard-coded "
                + "default path depending on the operating system it runs on (Windows: "
                + "c:/tools/msys64/usr/bin/bash.exe, FreeBSD: /usr/local/bin/bash, all others: "
                + "/bin/bash). Note that using a shell that is not compatible with bash may lead "
                + "to build failures or runtime failures of the generated binaries."
    )
    public PathFragment shellExecutable;

    @Option(
        name = "experimental_use_sh_binary_stub_script",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        defaultValue = "false",
        help = "If enabled, use a stub script for sh_binary targets.")
    public boolean useShBinaryStubScript;

    @Override
    public Options getHost() {
      Options host = (Options) getDefault();
      host.shellExecutable = shellExecutable;
      host.useShBinaryStubScript = useShBinaryStubScript;
      return host;
    }
  }
}
