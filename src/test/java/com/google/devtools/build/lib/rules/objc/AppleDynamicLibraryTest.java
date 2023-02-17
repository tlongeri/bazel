// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.rules.objc.CompilationSupport.ExtraLinkArgs;
import com.google.devtools.build.lib.testutil.Scratch;
import java.io.IOException;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test case for apple_dyamic_library. */
@RunWith(JUnit4.class)
public class AppleDynamicLibraryTest extends AppleBinaryStarlarkApiTest {
  static final RuleType RULE_TYPE =
      new RuleType("apple_binary_starlark") {
        @Override
        Iterable<String> requiredAttributes(
            Scratch scratch, String packageDir, Set<String> alreadyAdded) throws IOException {
          return Iterables.concat(
              ImmutableList.of("binary_type = 'dylib'"),
              AppleBinaryStarlarkApiTest.RULE_TYPE.requiredAttributes(
                  scratch, packageDir, Sets.union(alreadyAdded, ImmutableSet.of("binary_type"))));
        }

        @Override
        public String starlarkLoadPrerequisites() {
          return "load('//test_starlark:apple_binary_starlark.bzl', 'apple_binary_starlark')";
        }
      };

  @Override
  @Test
  public void testCcDependencyLinkoptsArePropagatedToLinkAction() throws Exception {
    checkCcDependencyLinkoptsArePropagatedToLinkAction(RULE_TYPE);
  }

  @Override
  @Test
  public void testObjcLibraryLinkoptsArePropagatedToLinkAction() throws Exception {
    checkObjcLibraryLinkoptsArePropagatedToLinkAction(RULE_TYPE);
  }

  @Override
  @Test
  public void testCanUseCrosstool_singleArch() throws Exception {
    checkLinkingRuleCanUseCrosstool_singleArch(RULE_TYPE);
  }

  @Override
  @Test
  public void testCanUseCrosstool_multiArch() throws Exception {
    checkLinkingRuleCanUseCrosstool_multiArch(RULE_TYPE);
  }

  @Override
  @Test
  public void testAppleSdkIphoneosPlatformEnv() throws Exception {
    checkAppleSdkIphoneosPlatformEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testXcodeVersionEnv() throws Exception {
    checkXcodeVersionEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testAliasedLinkoptsThroughObjcLibrary() throws Exception {
    checkAliasedLinkoptsThroughObjcLibrary(RULE_TYPE);
  }

  @Override
  @Test
  public void testObjcProviderLinkInputsInLinkAction() throws Exception {
    checkObjcProviderLinkInputsInLinkAction(RULE_TYPE);
  }

  @Override
  @Test
  public void testAppleSdkVersionEnv() throws Exception {
    checkAppleSdkVersionEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testNonDefaultAppleSdkVersionEnv() throws Exception {
    checkNonDefaultAppleSdkVersionEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testAppleSdkDefaultPlatformEnv() throws Exception {
    checkAppleSdkDefaultPlatformEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testAvoidDepsObjects_avoidViaCcLibrary() throws Exception {
    checkAvoidDepsObjects_avoidViaCcLibrary(RULE_TYPE);
  }

  @Override
  @Test
  public void testLipoBinaryAction() throws Exception {
    checkLipoBinaryAction(RULE_TYPE);
  }

  @Test
  public void testMultiarchCcDep() throws Exception {
    checkMultiarchCcDep(RULE_TYPE);
  }

  @Override
  @Test
  public void testWatchSimulatorLipoAction() throws Exception {
    checkWatchSimulatorLipoAction(RULE_TYPE);
  }

  @Test
  public void testFrameworkDepLinkFlags() throws Exception {
    checkFrameworkDepLinkFlags(RULE_TYPE, new ExtraLinkArgs("-dynamiclib"));
  }

  @Test
  public void testAvoidDepsDependencies() throws Exception {
    checkAvoidDepsDependencies(RULE_TYPE, new ExtraLinkArgs("-dynamiclib"));
  }

  @Override
  @Test
  public void testMinimumOs() throws Exception {
    checkMinimumOsLinkAndCompileArg(RULE_TYPE);
  }

  @Override
  @Test
  public void testMinimumOs_watchos() throws Exception {
    checkMinimumOsLinkAndCompileArg_watchos(RULE_TYPE);
  }

  @Override
  @Test
  public void testAppleSdkWatchsimulatorPlatformEnv() throws Exception {
    checkAppleSdkWatchsimulatorPlatformEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testAppleSdkWatchosPlatformEnv() throws Exception {
    checkAppleSdkWatchosPlatformEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testAppleSdkTvsimulatorPlatformEnv() throws Exception {
    checkAppleSdkTvsimulatorPlatformEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testAppleSdkTvosPlatformEnv() throws Exception {
    checkAppleSdkTvosPlatformEnv(RULE_TYPE);
  }

  @Override
  @Test
  public void testWatchSimulatorLinkAction() throws Exception {
    checkWatchSimulatorLinkAction(RULE_TYPE);
  }

  @Override
  @Test
  public void testAvoidDepsObjects() throws Exception {
    checkAvoidDepsObjects(RULE_TYPE);
  }

  @Override
  @Test
  public void testMinimumOsDifferentTargets() throws Exception {
    checkMinimumOsDifferentTargets(RULE_TYPE, "_lipobin", "_bin");
  }

  @Override
  @Test
  public void testDrops32BitIosArchitecture() throws Exception {
    verifyDrops32BitIosArchitecture(RULE_TYPE);
  }

  @Override
  @Test
  public void testDrops32BitWatchArchitecture() throws Exception {
    verifyDrops32BitWatchArchitecture(RULE_TYPE);
  }
}
