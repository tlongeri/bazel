// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.view.java;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.testutil.TestConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for the Java-specific parts of {@link BuildConfigurationValue} creation, and the
 * Java-related configuration transitions.
 */
@RunWith(JUnit4.class)
public class JavaConfigurationTest extends ConfigurationTestCase {

  @Test
  public void testJavaLauncherConfiguration() throws Exception {
    // Default value of --java_launcher: null.
    BuildConfigurationValue config = create();
    JavaConfiguration cfg = config.getFragment(JavaConfiguration.class);
    assertThat(cfg.getJavaLauncherLabel()).isNull();

    // Explicitly enabled launcher as default
    scratch.file("foo/BUILD", "filegroup(name='bar')", "filegroup(name='baz')");
    config = create("--java_launcher=//foo:bar");
    cfg = config.getFragment(JavaConfiguration.class);
    assertThat(Label.parseAbsoluteUnchecked("//foo:bar")).isEqualTo(cfg.getJavaLauncherLabel());
  }

  @Test
  public void testHostCrosstoolTop() throws Exception {
    BuildConfigurationCollection configs = createCollection();
    BuildConfigurationValue config = Iterables.getOnlyElement(configs.getTargetConfigurations());
    assertThat(config.getFragment(CppConfiguration.class).getRuleProvidingCcToolchainProvider())
        .isEqualTo(
            Label.parseAbsoluteUnchecked(TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain"));

    BuildConfigurationValue hostConfig = configs.getHostConfiguration();
    assertThat(hostConfig.getFragment(CppConfiguration.class).getRuleProvidingCcToolchainProvider())
        .isEqualTo(
            Label.parseAbsoluteUnchecked(TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain"));
  }


  @Test
  public void testConfigurationsHaveUniqueOutputDirectories() throws Exception {
    assertConfigurationsHaveUniqueOutputDirectories(createCollection("--cpu=k8"));

    assertConfigurationsHaveUniqueOutputDirectories(
        createCollection("--cpu=k8", "--compilation_mode=opt"));
  }
}
