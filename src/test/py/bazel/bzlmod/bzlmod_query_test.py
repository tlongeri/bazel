# pylint: disable=g-backslash-continuation
# Copyright 2022 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Tests bzlmod integration inside query (querying and external repo using the repo mapping)."""

import os
import tempfile
import unittest

from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry


class BzlmodQueryTest(test_base.TestBase):
  """Test class for bzlmod integration inside query (querying and external repo using the repo mapping)."""

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.main_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'main'))
    self.main_registry.createCcModule('aaa', '1.0', {'ccc': '1.2'}) \
      .createCcModule('aaa', '1.1') \
      .createCcModule('bbb', '1.0', {'aaa': '1.0'}, {'aaa': 'com_foo_bar_aaa'}) \
      .createCcModule('ccc', '1.2')

    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'common --experimental_enable_bzlmod',
            'common --registry=' + self.main_registry.getURL(),
            # We need to have BCR here to make sure built-in modules like
            # bazel_tools can work.
            'common --registry=https://bcr.bazel.build',
            # Disable yanked version check so we are not affected BCR changes.
            'common --allow_yanked_versions=all',
        ])
    self.ScratchFile('WORKSPACE')
    # The existence of WORKSPACE.bzlmod prevents WORKSPACE prefixes or suffixes
    # from being used; this allows us to test built-in modules actually work
    self.ScratchFile('WORKSPACE.bzlmod')

  def testQueryModuleRepoTargetsBelow(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    _, stdout, _ = self.RunBazel(['query', '@my_repo//...'],
                                 allow_failure=False)
    self.assertListEqual(['@my_repo//:lib_aaa'], stdout)

  def testQueryModuleRepoTransitiveDeps(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@my_repo//:lib_aaa"],',
        ')',
    ])
    _, stdout, _ = self.RunBazel([
        'query',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
    ],
                                 allow_failure=False)
    self.assertListEqual(
        ['//:main', '@my_repo//:lib_aaa', '@@ccc~1.2//:lib_ccc'], stdout)

  def testAqueryModuleRepoTargetsBelow(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    _, stdout, _ = self.RunBazel(['aquery', '@my_repo//...'],
                                 allow_failure=False)
    # This label is stringified into a "purpose" in some action before it
    # reaches aquery code, so can't decanonicalize it.
    self.assertEqual(stdout[0], 'cc_library-compile for @aaa~1.0//:lib_aaa')
    self.assertIn('Target: @my_repo//:lib_aaa', stdout)

  def testAqueryModuleRepoTransitiveDeps(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@my_repo//:lib_aaa"],',
        ')',
    ])
    _, stdout, _ = self.RunBazel([
        'aquery',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
    ],
                                 allow_failure=False)
    self.assertIn('Target: //:main', stdout)
    self.assertIn('Target: @my_repo//:lib_aaa', stdout)
    self.assertIn('Target: @@ccc~1.2//:lib_ccc', stdout)

  def testCqueryModuleRepoTargetsBelow(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    _, stdout, _ = self.RunBazel(['cquery', '@my_repo//...'],
                                 allow_failure=False)
    self.assertRegex(stdout[0], r'@my_repo//:lib_aaa \([\w\d]+\)')

  def testCqueryModuleRepoTransitiveDeps(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@my_repo//:lib_aaa"],',
        ')',
    ])
    _, stdout, _ = self.RunBazel([
        'cquery',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
    ],
                                 allow_failure=False)
    self.assertRegex(stdout[0], r'^//:main \([\w\d]+\)$')
    self.assertRegex(stdout[1], r'^@my_repo//:lib_aaa \([\w\d]+\)$')
    self.assertRegex(stdout[2], r'^@@ccc~1.2//:lib_ccc \([\w\d]+\)$')
    self.assertEqual(len(stdout), 3)

  def testFetchModuleRepoTargetsBelow(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    self.RunBazel(['fetch', '@my_repo//...'], allow_failure=False)

  def testGenQueryTargetLiteralInGenRule(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    self.ScratchFile('BUILD', [
        "genquery(name='rinne',", "scope= ['@my_repo//:lib_aaa'],",
        "expression = '@my_repo//:lib_aaa' )", "genrule(name='gen_rinne',",
        "srcs = [':rinne'],", "outs = ['gen_rinne.txt'],",
        "cmd = 'cat $(SRCS) > $@')"
    ])
    self.RunBazel(['build', '//:gen_rinne'], allow_failure=False)
    output_file = open('bazel-bin/gen_rinne.txt', 'r')
    self.assertIsNotNone(output_file)
    output = output_file.readlines()
    output_file.close()
    self.assertListEqual(['@my_repo//:lib_aaa\n'], output)

  def testQueryCannotResolveRepoMapping_malformedModuleFile(self):
    self.ScratchFile('MODULE.bazel', [
        'module(namex="my_module", version = "1.0")',
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    exit_code, _, stderr = self.RunBazel(['query', '@my_repo//...'],
                                         allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'ERROR: Error computing the main repository mapping: error executing MODULE.bazel file for <root>',
        stderr)

  def testFetchCannotResolveRepoMapping_malformedModuleFile(self):
    self.ScratchFile('MODULE.bazel', [
        'module(namex="my_module", version = "1.0")',
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    exit_code, _, stderr = self.RunBazel(['fetch', '@my_repo//...'],
                                         allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'ERROR: Error computing the main repository mapping: error executing MODULE.bazel file for <root>',
        stderr)


if __name__ == '__main__':
  unittest.main()
