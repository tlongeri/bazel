# pylint: disable=g-backslash-continuation
# Copyright 2021 The Bazel Authors. All rights reserved.
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
# pylint: disable=g-long-ternary

import os
import pathlib
import tempfile
import unittest

from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry
from src.test.py.bazel.bzlmod.test_utils import scratchFile


class BazelModuleTest(test_base.TestBase):

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.main_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'main'))
    self.main_registry.createCcModule('aaa', '1.0') \
        .createCcModule('aaa', '1.1') \
        .createCcModule('bbb', '1.0', {'aaa': '1.0'}, {'aaa': 'com_foo_aaa'}) \
        .createCcModule('bbb', '1.1', {'aaa': '1.1'}) \
        .createCcModule('ccc', '1.1', {'aaa': '1.1', 'bbb': '1.1'}) \
        .createCcModule('ddd', '1.0', {'yanked1': '1.0', 'yanked2': '1.0'}) \
        .createCcModule('eee', '1.0', {'yanked1': '1.0'}) \
        .createCcModule('yanked1', '1.0') \
        .createCcModule('yanked2', '1.0') \
        .addMetadata('yanked1', yanked_versions={'1.0': 'dodgy'}) \
        .addMetadata('yanked2', yanked_versions={'1.0': 'sketchy'})
    self.writeBazelrcFile()
    self.ScratchFile('WORKSPACE')
    # The existence of WORKSPACE.bzlmod prevents WORKSPACE prefixes or suffixes
    # from being used; this allows us to test built-in modules actually work
    self.ScratchFile('WORKSPACE.bzlmod')

  def writeBazelrcFile(self, allow_yanked_versions=True):
    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'common --enable_bzlmod',
            'common --registry=' + self.main_registry.getURL(),
            # We need to have BCR here to make sure built-in modules like
            # bazel_tools can work.
            'common --registry=https://bcr.bazel.build',
            'common --verbose_failures',
        ] + ([
            # Disable yanked version check so we are not affected BCR changes.
            'common --allow_yanked_versions=all',
        ] if allow_yanked_versions else []))

  def writeMainProjectFiles(self):
    self.ScratchFile('aaa.patch', [
        '--- a/aaa.cc',
        '+++ b/aaa.cc',
        '@@ -1,6 +1,6 @@',
        ' #include <stdio.h>',
        ' #include "aaa.h"',
        ' void hello_aaa(const std::string& caller) {',
        '-    std::string lib_name = "aaa@1.0";',
        '+    std::string lib_name = "aaa@1.0 (locally patched)";',
        '     printf("%s => %s\\n", caller.c_str(), lib_name.c_str());',
        ' }',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = [',
        '    "@aaa//:lib_aaa",',
        '    "@bbb//:lib_bbb",',
        '  ],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        '#include "aaa.h"',
        '#include "bbb.h"',
        'int main() {',
        '    hello_aaa("main function");',
        '    hello_bbb("main function");',
        '}',
    ])

  def testSimple(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@aaa//:lib_aaa"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        '#include "aaa.h"',
        'int main() {',
        '    hello_aaa("main function");',
        '}',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.0', stdout)

  def testSimpleTransitive(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@bbb//:lib_bbb"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        '#include "bbb.h"',
        'int main() {',
        '    hello_bbb("main function");',
        '}',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => bbb@1.0', stdout)
    self.assertIn('bbb@1.0 => aaa@1.0', stdout)

  def testSimpleDiamond(self):
    self.writeMainProjectFiles()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.1")',
            # bbb@1.0 has to depend on aaa@1.1 after MVS.
            'bazel_dep(name = "bbb", version = "1.0")',
        ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.1', stdout)
    self.assertIn('main function => bbb@1.0', stdout)
    self.assertIn('bbb@1.0 => aaa@1.1', stdout)

  def testSingleVersionOverrideWithPatch(self):
    self.writeMainProjectFiles()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.1")',
            'bazel_dep(name = "bbb", version = "1.1")',
            # Both main and bbb@1.1 has to depend on the locally patched aaa@1.0
            'single_version_override(',
            '  module_name = "aaa",',
            '  version = "1.0",',
            '  patches = ["//:aaa.patch"],',
            '  patch_strip = 1,',
            ')',
        ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.0 (locally patched)', stdout)
    self.assertIn('main function => bbb@1.1', stdout)
    self.assertIn('bbb@1.1 => aaa@1.0 (locally patched)', stdout)

  def testRegistryOverride(self):
    self.writeMainProjectFiles()
    another_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'another'),
        ' from another registry')
    another_registry.createCcModule('aaa', '1.0')
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0")',
        'bazel_dep(name = "bbb", version = "1.0")',
        'single_version_override(',
        '  module_name = "aaa",',
        '  registry = "%s",' % another_registry.getURL(),
        ')',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.0 from another registry', stdout)
    self.assertIn('main function => bbb@1.0', stdout)
    self.assertIn('bbb@1.0 => aaa@1.0 from another registry', stdout)

  def testArchiveOverride(self):
    self.writeMainProjectFiles()
    archive_aaa_1_0 = self.main_registry.archives.joinpath('aaa.1.0.zip')
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.1")',
        'bazel_dep(name = "bbb", version = "1.1")',
        'archive_override(',
        '  module_name = "aaa",',
        '  urls = ["%s"],' % archive_aaa_1_0.as_uri(),
        '  patches = ["//:aaa.patch"],',
        '  patch_strip = 1,',
        ')',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.0 (locally patched)', stdout)
    self.assertIn('main function => bbb@1.1', stdout)
    self.assertIn('bbb@1.1 => aaa@1.0 (locally patched)', stdout)

  def testGitOverride(self):
    self.writeMainProjectFiles()

    src_aaa_1_0 = self.main_registry.projects.joinpath('aaa', '1.0')
    self.RunProgram(['git', 'init'], cwd=src_aaa_1_0, allow_failure=False)
    self.RunProgram(['git', 'config', 'user.name', 'tester'],
                    cwd=src_aaa_1_0,
                    allow_failure=False)
    self.RunProgram(['git', 'config', 'user.email', 'tester@foo.com'],
                    cwd=src_aaa_1_0,
                    allow_failure=False)
    self.RunProgram(['git', 'add', './'], cwd=src_aaa_1_0, allow_failure=False)
    self.RunProgram(['git', 'commit', '-m', 'Initial commit.'],
                    cwd=src_aaa_1_0,
                    allow_failure=False)
    _, stdout, _ = self.RunProgram(['git', 'rev-parse', 'HEAD'],
                                   cwd=src_aaa_1_0,
                                   allow_failure=False)
    commit = stdout[0].strip()

    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.1")',
        'bazel_dep(name = "bbb", version = "1.1")',
        'git_override(',
        '  module_name = "aaa",',
        '  remote = "%s",' % src_aaa_1_0.as_uri(),
        '  commit = "%s",' % commit,
        '  patches = ["//:aaa.patch"],',
        '  patch_strip = 1,',
        ')',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.0 (locally patched)', stdout)
    self.assertIn('main function => bbb@1.1', stdout)
    self.assertIn('bbb@1.1 => aaa@1.0 (locally patched)', stdout)

  def testLocalPathOverride(self):
    src_aaa_1_0 = self.main_registry.projects.joinpath('aaa', '1.0')
    self.writeMainProjectFiles()
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.1")',
        'bazel_dep(name = "bbb", version = "1.1")',
        'local_path_override(',
        '  module_name = "aaa",',
        '  path = "%s",' % str(src_aaa_1_0.resolve()).replace('\\', '/'),
        ')',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.0', stdout)
    self.assertIn('main function => bbb@1.1', stdout)
    self.assertIn('bbb@1.1 => aaa@1.0', stdout)

  def testRemotePatchForBazelDep(self):
    patch_file = self.ScratchFile('aaa.patch', [
        '--- a/aaa.cc',
        '+++ b/aaa.cc',
        '@@ -1,6 +1,6 @@',
        ' #include <stdio.h>',
        ' #include "aaa.h"',
        ' void hello_aaa(const std::string& caller) {',
        '-    std::string lib_name = "aaa@1.1-1";',
        '+    std::string lib_name = "aaa@1.1-1 (remotely patched)";',
        '     printf("%s => %s\\n", caller.c_str(), lib_name.c_str());',
        ' }',
    ])
    self.main_registry.createCcModule(
        'aaa', '1.1-1', patches=[patch_file], patch_strip=1)
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.1-1")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@aaa//:lib_aaa"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        '#include "aaa.h"',
        'int main() {',
        '    hello_aaa("main function");',
        '}',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.1-1 (remotely patched)', stdout)

  def testRepoNameForBazelDep(self):
    self.writeMainProjectFiles()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo_a_name")',
            # bbb should still be able to access aaa as com_foo_aaa
            'bazel_dep(name = "bbb", version = "1.0")',
        ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = [',
        '    "@my_repo_a_name//:lib_aaa",',
        '    "@bbb//:lib_bbb",',
        '  ],',
        ')',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => aaa@1.0', stdout)
    self.assertIn('main function => bbb@1.0', stdout)
    self.assertIn('bbb@1.0 => aaa@1.0', stdout)

  def testCheckDirectDependencies(self):
    self.writeMainProjectFiles()
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0")',
        'bazel_dep(name = "bbb", version = "1.0")',
        'bazel_dep(name = "ccc", version = "1.1")',
    ])
    _, stdout, stderr = self.RunBazel(
        ['run', '//:main', '--check_direct_dependencies=warning'],
        allow_failure=False)
    self.assertIn(
        'WARNING: For repository \'aaa\', the root module requires module version aaa@1.0, but got aaa@1.1 in the resolved dependency graph.',
        stderr)
    self.assertIn(
        'WARNING: For repository \'bbb\', the root module requires module version bbb@1.0, but got bbb@1.1 in the resolved dependency graph.',
        stderr)
    self.assertIn('main function => aaa@1.1', stdout)
    self.assertIn('main function => bbb@1.1', stdout)
    self.assertIn('bbb@1.1 => aaa@1.1', stdout)

    exit_code, _, stderr = self.RunBazel(
        ['run', '//:main', '--check_direct_dependencies=error'],
        allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'ERROR: For repository \'aaa\', the root module requires module version aaa@1.0, but got aaa@1.1 in the resolved dependency graph.',
        stderr)
    self.assertIn(
        'ERROR: For repository \'bbb\', the root module requires module version bbb@1.0, but got bbb@1.1 in the resolved dependency graph.',
        stderr)

  def testRepositoryRuleErrorInModuleExtensionFailsTheBuild(self):
    self.ScratchFile('MODULE.bazel', [
        'module_ext = use_extension("//pkg:extension.bzl", "module_ext")',
        'use_repo(module_ext, "foo")',
    ])
    self.ScratchFile('pkg/BUILD.bazel')
    self.ScratchFile('pkg/rules.bzl', [
        'def _repo_rule_impl(ctx):',
        '    ctx.file("WORKSPACE")',
        'repo_rule = repository_rule(implementation = _repo_rule_impl)',
    ])
    self.ScratchFile('pkg/extension.bzl', [
        'load(":rules.bzl", "repo_rule")',
        'def _module_ext_impl(ctx):',
        '    repo_rule(name = "foo", invalid_attr = "value")',
        'module_ext = module_extension(implementation = _module_ext_impl)',
    ])
    exit_code, _, stderr = self.RunBazel(['run', '@foo//...'],
                                         allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        "ERROR: <builtin>: //pkg:_main~module_ext~foo: no such attribute 'invalid_attr' in 'repo_rule' rule",
        stderr)
    self.assertTrue(
        any([
            '/pkg/extension.bzl", line 3, column 14, in _module_ext_impl'
            in line for line in stderr
        ]))

  def testCommandLineModuleOverride(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "ss", version = "1.0")',
        'local_path_override(',
        '  module_name = "ss",',
        '  path = "%s",' % self.Path('aa'),
        ')',
    ])
    self.ScratchFile('BUILD')
    self.ScratchFile('WORKSPACE')

    self.ScratchFile('aa/MODULE.bazel', [
        'module(name=\'ss\')',
    ])
    self.ScratchFile('aa/BUILD', [
        'filegroup(name = "never_ever")',
    ])
    self.ScratchFile('aa/WORKSPACE')

    self.ScratchFile('bb/MODULE.bazel', [
        'module(name=\'ss\')',
    ])
    self.ScratchFile('bb/BUILD', [
        'filegroup(name = "choose_me")',
    ])
    self.ScratchFile('bb/WORKSPACE')

    _, _, stderr = self.RunBazel([
        'build', '--experimental_enable_bzlmod', '@ss//:all',
        '--override_module', 'ss=' + self.Path('bb')
    ],
                                 allow_failure=False)
    # module file override should be ignored, and bb directory should be used
    self.assertIn(
        'Target @ss~override//:choose_me up-to-date (nothing to build)', stderr)

  def testDownload(self):
    data_path = self.ScratchFile('data.txt', ['some data'])
    data_url = pathlib.Path(data_path).resolve().as_uri()
    self.ScratchFile('MODULE.bazel', [
        'data_ext = use_extension("//:ext.bzl", "data_ext")',
        'use_repo(data_ext, "no_op")',
    ])
    self.ScratchFile('BUILD')
    self.ScratchFile('WORKSPACE')
    self.ScratchFile('ext.bzl', [
        'def _no_op_impl(ctx):',
        '  ctx.file("WORKSPACE")',
        '  ctx.file("BUILD", "filegroup(name=\\"no_op\\")")',
        'no_op = repository_rule(_no_op_impl)',
        'def _data_ext_impl(ctx):',
        '  if not ctx.download(url="%s", output="data.txt").success:' %
        data_url,
        '    fail("download failed")',
        '  if ctx.read("data.txt").strip() != "some data":',
        '    fail("unexpected downloaded content: %s" % ctx.read("data.txt").strip())',
        '  no_op(name="no_op")',
        'data_ext = module_extension(_data_ext_impl)',
    ])
    self.RunBazel(['build', '@no_op//:no_op'], allow_failure=False)

  def testNonRegistryOverriddenModulesIgnoreYanked(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    src_yanked1 = self.main_registry.projects.joinpath('yanked1', '1.0')
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "yanked1", version = "1.0")', 'local_path_override(',
        '  module_name = "yanked1",',
        '  path = "%s",' % str(src_yanked1.resolve()).replace('\\', '/'), ')'
    ])
    self.ScratchFile('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@yanked1//:lib_yanked1"],',
        ')',
    ])
    self.RunBazel(['build', '--nobuild', '//:main'], allow_failure=False)

  def testContainingYankedDepFails(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "yanked1", version = "1.0")',
    ])
    self.ScratchFile('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@ddd//:lib_ddd"],',
        ')',
    ])
    exit_code, _, stderr = self.RunBazel(['build', '--nobuild', '//:main'],
                                         allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: ' +
        'yanked1@1.0, for the reason: dodgy.', ''.join(stderr))

  def testAllowedYankedDepsSuccessByFlag(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "ddd", version = "1.0")',
    ])
    self.ScratchFile('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@ddd//:lib_ddd"],',
        ')',
    ])
    self.RunBazel([
        'build', '--nobuild', '--allow_yanked_versions=yanked1@1.0,yanked2@1.0',
        '//:main'
    ],
                  allow_failure=False)

  def testAllowedYankedDepsByEnvVar(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "ddd", version = "1.0")',
    ])
    self.ScratchFile('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@ddd//:lib_ddd"],',
        ')',
    ])
    self.RunBazel(
        ['build', '--nobuild', '//:main'],
        env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'yanked1@1.0,yanked2@1.0'},
        allow_failure=False)

    # Test changing the env var, the build should fail again.
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '//:main'],
        env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'yanked2@1.0'},
        allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: ' +
        'yanked1@1.0, for the reason: dodgy.', ''.join(stderr))

  def testAllowedYankedDepsSuccessMix(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "ddd", version = "1.0")',
    ])
    self.ScratchFile('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@ddd//:lib_ddd"],',
        ')',
    ])
    self.RunBazel([
        'build', '--nobuild', '--allow_yanked_versions=yanked1@1.0', '//:main'
    ],
                  env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'yanked2@1.0'},
                  allow_failure=False)

  def setUpProjectWithLocalRegistryModule(self, dep_name, dep_version):
    self.main_registry.generateCcSource(dep_name, dep_version)
    self.main_registry.createLocalPathModule(dep_name, dep_version,
                                             dep_name + '/' + dep_version)

    self.ScratchFile('main.cc', [
        '#include "%s.h"' % dep_name,
        'int main() {',
        '    hello_%s("main function");' % dep_name,
        '}',
    ])
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "%s", version = "%s")' % (dep_name, dep_version),
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@%s//:lib_%s"],' % (dep_name, dep_name),
        ')',
    ])
    self.ScratchFile('WORKSPACE', [])

  def testLocalRepoInSourceJsonAbsoluteBasePath(self):
    self.main_registry.setModuleBasePath(str(self.main_registry.projects))
    self.setUpProjectWithLocalRegistryModule('sss', '1.3')
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => sss@1.3', stdout)

  def testLocalRepoInSourceJsonRelativeBasePath(self):
    self.main_registry.setModuleBasePath('projects')
    self.setUpProjectWithLocalRegistryModule('sss', '1.3')
    _, stdout, _ = self.RunBazel(['run', '//:main'], allow_failure=False)
    self.assertIn('main function => sss@1.3', stdout)

  def testRunfilesRepoMappingManifest(self):
    self.main_registry.setModuleBasePath('projects')
    projects_dir = self.main_registry.projects

    # Set up a "bare_rule" module that contains the "bare_binary" rule which
    # passes runfiles along
    self.main_registry.createLocalPathModule('bare_rule', '1.0', 'bare_rule')
    projects_dir.joinpath('bare_rule').mkdir(exist_ok=True)
    scratchFile(projects_dir.joinpath('bare_rule', 'WORKSPACE'))
    scratchFile(projects_dir.joinpath('bare_rule', 'BUILD'))
    scratchFile(
        projects_dir.joinpath('bare_rule', 'defs.bzl'), [
            'def _bare_binary_impl(ctx):',
            '  exe = ctx.actions.declare_file(ctx.label.name)',
            '  ctx.actions.write(exe,',
            '    "#/bin/bash\\nif [[ ! -f \\"$0.repo_mapping\\" ]]; then\\necho >&2 \\"ERROR: cannot find repo mapping manifest file\\"\\nexit 1\\nfi",',
            '    True)',
            '  runfiles = ctx.runfiles(files=ctx.files.data)',
            '  for data in ctx.attr.data:',
            '    runfiles = runfiles.merge(data[DefaultInfo].default_runfiles)',
            '  return DefaultInfo(files=depset(direct=[exe]), executable=exe, runfiles=runfiles)',
            'bare_binary=rule(',
            '  implementation=_bare_binary_impl,',
            '  attrs={"data":attr.label_list(allow_files=True)},',
            '  executable=True,',
            ')',
        ])

    # Now set up a project tree shaped like a diamond
    self.ScratchFile('MODULE.bazel', [
        'module(name="me",version="1.0")',
        'bazel_dep(name="foo",version="1.0")',
        'bazel_dep(name="bar",version="2.0")',
        'bazel_dep(name="bare_rule",version="1.0")',
    ])
    self.ScratchFile('WORKSPACE')
    self.ScratchFile('WORKSPACE.bzlmod', ['workspace(name="me_ws")'])
    self.ScratchFile('BUILD', [
        'load("@bare_rule//:defs.bzl", "bare_binary")',
        'bare_binary(name="me",data=["@foo"])',
    ])
    self.main_registry.createLocalPathModule('foo', '1.0', 'foo', {
        'quux': '1.0',
        'bare_rule': '1.0'
    })
    self.main_registry.createLocalPathModule('bar', '2.0', 'bar', {
        'quux': '2.0',
        'bare_rule': '1.0'
    })
    self.main_registry.createLocalPathModule('quux', '1.0', 'quux1',
                                             {'bare_rule': '1.0'})
    self.main_registry.createLocalPathModule('quux', '2.0', 'quux2',
                                             {'bare_rule': '1.0'})
    for dir_name, build_file in [
        ('foo', 'bare_binary(name="foo",data=["@quux"])'),
        ('bar', 'bare_binary(name="bar",data=["@quux"])'),
        ('quux1', 'bare_binary(name="quux")'),
        ('quux2', 'bare_binary(name="quux")'),
    ]:
      projects_dir.joinpath(dir_name).mkdir(exist_ok=True)
      scratchFile(projects_dir.joinpath(dir_name, 'WORKSPACE'))
      scratchFile(
          projects_dir.joinpath(dir_name, 'BUILD'), [
              'load("@bare_rule//:defs.bzl", "bare_binary")',
              'package(default_visibility=["//visibility:public"])',
              build_file,
          ])

    # We use a shell script to check that the binary itself can see the repo
    # mapping manifest. This obviously doesn't work on Windows, so we just build
    # the target. TODO(wyv): make this work on Windows by using Batch.
    bazel_command = 'build' if self.IsWindows() else 'run'

    # Finally we get to build stuff!
    self.RunBazel([bazel_command, '//:me'], allow_failure=False)
    with open(self.Path('bazel-bin/me.repo_mapping'), 'r') as f:
      self.assertEqual(
          f.read().strip(), """,foo,foo~1.0
,me,_main
,me_ws,_main
foo~1.0,foo,foo~1.0
foo~1.0,quux,quux~2.0
quux~2.0,quux,quux~2.0""")
    self.RunBazel([bazel_command, '@bar//:bar'], allow_failure=False)
    with open(self.Path('bazel-bin/external/bar~2.0/bar.repo_mapping'),
              'r') as f:
      self.assertEqual(
          f.read().strip(), """bar~2.0,bar,bar~2.0
bar~2.0,quux,quux~2.0
quux~2.0,quux,quux~2.0""")

if __name__ == '__main__':
  unittest.main()
