// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages.util;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.OS;
import java.io.IOException;

/**
 * Bazel implementation of {@link MockCcSupport}
 */
public final class BazelMockCcSupport extends MockCcSupport {
  public static final BazelMockCcSupport INSTANCE = new BazelMockCcSupport();

  /** Filter to remove implicit dependencies of C/C++ rules. */
  private static final boolean isNotCcLabel(String label) {
    return !label.startsWith("//tools/cpp");
  }

  private BazelMockCcSupport() {}

  private static final ImmutableList<String> CROSSTOOL_ARCHS =
      ImmutableList.of("piii", "k8", "armeabi-v7a", "ppc", "darwin");

  @Override
  protected String getRealFilesystemCrosstoolTopPath() {
    // TODO(b/195425240): Make real-filesystem mode work.
    return "";
  }

  @Override
  protected String[] getRealFilesystemTools(String crosstoolTop) {
    // TODO(b/195425240): Make real-filesystem mode work.
    return new String[0];
  }

  @Override
  protected ImmutableList<String> getCrosstoolArchs() {
    return CROSSTOOL_ARCHS;
  }

  @Override
  public void setup(MockToolsConfig config) throws IOException {
    writeMacroFile(config);
    setupRulesCc(config);
    setupCcToolchainConfig(config, getToolchainConfigs());
    createParseHeadersAndLayeringCheckWhitelist(config);
    createStarlarkLooseHeadersWhitelist(config, "//...");
    config.append(
        TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/cpp/BUILD",
        "alias(name='host_xcodes',actual='@local_config_xcode//:host_xcodes')");
  }

  @Override
  public Label getMockCrosstoolLabel() {
    return Label.parseAbsoluteUnchecked("@bazel_tools//tools/cpp:toolchain");
  }

  @Override
  public String getMockCrosstoolPath() {
    return "embedded_tools/tools/cpp/";
  }

  @Override
  public Predicate<String> labelNameFilter() {
    return BazelMockCcSupport::isNotCcLabel;
  }

  @Override
  protected boolean shouldUseRealFileSystemCrosstool() {
    // TODO(b/195425240): Workaround for lack of real-filesystem support.
    return false;
  }

  private static ImmutableList<CcToolchainConfig> getToolchainConfigs() {
    ImmutableList.Builder<CcToolchainConfig> result = ImmutableList.builder();

    // Different from CcToolchainConfig.getDefault....
    result.add(CcToolchainConfig.builder().build());

    if (OS.getCurrent() == OS.DARWIN) {
      result.add(CcToolchainConfig.getCcToolchainConfigForCpu("darwin"));
    }

    return result.build();
  }
}
