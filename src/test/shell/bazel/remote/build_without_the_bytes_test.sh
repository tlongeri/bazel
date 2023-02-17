#!/bin/bash
#
# Copyright 2016 The Bazel Authors. All rights reserved.
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
#
# Tests build without the bytes.

set -euo pipefail

# --- begin runfiles.bash initialization ---
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }
source "$(rlocation "io_bazel/src/test/shell/bazel/remote/remote_utils.sh")" \
  || { echo "remote_utils.sh not found!" >&2; exit 1; }

function set_up() {
  start_worker
}

function tear_down() {
  bazel clean >& $TEST_log
  stop_worker
}

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

if "$is_windows"; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
  declare -r EXE_EXT=".exe"
else
  declare -r EXE_EXT=""
fi

function test_cc_tree() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  mkdir -p a
  cat > a/BUILD <<EOF
load(":tree.bzl", "mytree")
mytree(name = "tree")
cc_library(name = "tree_cc", srcs = [":tree"])
EOF
  cat > a/tree.bzl <<EOF
def _tree_impl(ctx):
    tree = ctx.actions.declare_directory("file.cc")
    ctx.actions.run_shell(outputs = [tree],
                          command = "mkdir -p %s && touch %s/one.cc" % (tree.path, tree.path))
    return [DefaultInfo(files = depset([tree]))]

mytree = rule(implementation = _tree_impl)
EOF
  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_download_minimal \
      //a:tree_cc >& "$TEST_log" \
      || fail "Failed to build //a:tree_cc with minimal downloads"
}

function test_cc_include_scanning_and_minimal_downloads() {
  cat > BUILD <<'EOF'
cc_binary(
name = 'bin',
srcs = ['bin.cc', ':header.h'],
)

genrule(
name = 'gen',
outs = ['header.h'],
cmd = 'touch $@',
)
EOF
  cat > bin.cc <<EOF
#include "header.h"
int main() { return 0; }
EOF
  bazel build //:bin --remote_cache=grpc://localhost:${worker_port} >& $TEST_log \
    || fail "Failed to populate remote cache"
  bazel clean >& $TEST_log || fail "Failed to clean"
  cat > bin.cc <<EOF
#include "header.h"
int main() { return 1; }
EOF
  bazel build \
        --experimental_unsupported_and_brittle_include_scanning \
        --features=cc_include_scanning \
        --remote_cache=grpc://localhost:${worker_port} \
        --remote_download_minimal \
      //:bin >& $TEST_log \
      || fail "Failed to build with --remote_download_minimal"
}

function test_downloads_minimal() {
  # Test that genrule outputs are not downloaded when using
  # --remote_download_minimal
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
)

genrule(
  name = "foobar",
  srcs = [":foo"],
  outs = ["foobar.txt"],
  cmd = "cat $(location :foo) > \"$@\" && echo \"bar\" >> \"$@\"",
)
EOF

  bazel build \
    --genrule_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:foobar || fail "Failed to build //a:foobar"

  (! [[ -f bazel-bin/a/foo.txt ]] && ! [[ -f bazel-bin/a/foobar.txt ]]) \
  || fail "Expected no files to have been downloaded"
}

function test_downloads_minimal_failure() {
  # Test that outputs of failing actions are downloaded when using
  # --remote_download_minimal
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "fail",
  srcs = [],
  outs = ["fail.txt"],
  cmd = "echo \"foo\" > \"$@\" && exit 1",
)
EOF

  bazel build \
    --spawn_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:fail && fail "Expected test failure" || true

  [[ -f bazel-bin/a/fail.txt ]] \
  || fail "Expected fail.txt of failing target //a:fail to be downloaded"
}

function test_downloads_minimal_prefetch() {
  # Test that when using --remote_download_minimal a remote-only output that's
  # an input to a local action is downloaded lazily before executing the local action.
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "remote",
  srcs = [],
  outs = ["remote.txt"],
  cmd = "echo -n \"remote\" > \"$@\"",
)

genrule(
  name = "local",
  srcs = [":remote"],
  outs = ["local.txt"],
  cmd = "cat $(location :remote) > \"$@\" && echo -n \"local\" >> \"$@\"",
  tags = ["no-remote"],
)
EOF

  bazel build \
    --genrule_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:remote || fail "Failed to build //a:remote"

  (! [[ -f bazel-bin/a/remote.txt ]]) \
  || fail "Expected bazel-bin/a/remote.txt to have not been downloaded"

  bazel build \
    --genrule_strategy=remote,local \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:local || fail "Failed to build //a:local"

  localtxt="bazel-bin/a/local.txt"
  [[ $(< ${localtxt}) == "remotelocal" ]] \
  || fail "Unexpected contents in " ${localtxt} ": " $(< ${localtxt})

  [[ -f bazel-bin/a/remote.txt ]] \
  || fail "Expected bazel-bin/a/remote.txt to be downloaded"
}

function test_download_outputs_invalidation() {
  # Test that when changing values of --remote_download_minimal all actions are
  # invalidated.
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "remote",
  srcs = [],
  outs = ["remote.txt"],
  cmd = "echo -n \"remote\" > \"$@\"",
)
EOF

  bazel build \
    --genrule_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:remote >& $TEST_log || fail "Failed to build //a:remote"

  expect_log "2 processes: 1 internal, 1 remote"

  bazel build \
    --genrule_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_outputs=all \
    //a:remote >& $TEST_log || fail "Failed to build //a:remote"

  # Changing --remote_download_outputs to "all" should invalidate SkyFrames in-memory
  # caching and make it re-run the action.
  expect_log "2 processes: 1 remote cache hit, 1 internal"
}

function test_downloads_minimal_native_prefetch() {
  # Test that when using --remote_download_outputs=minimal a remotely stored output
  # that's an input to a native action (ctx.actions.expand_template) is staged lazily for action
  # execution.
  mkdir -p a
  cat > a/substitute_username.bzl <<'EOF'
def _substitute_username_impl(ctx):
    ctx.actions.expand_template(
        template = ctx.file.template,
        output = ctx.outputs.out,
        substitutions = {
            "{USERNAME}": ctx.attr.username,
        },
    )

substitute_username = rule(
    implementation = _substitute_username_impl,
    attrs = {
        "username": attr.string(mandatory = True),
        "template": attr.label(
            allow_single_file = True,
            mandatory = True,
        ),
    },
    outputs = {"out": "%{name}.txt"},
)
EOF

  cat > a/BUILD <<'EOF'
load(":substitute_username.bzl", "substitute_username")
genrule(
    name = "generate-template",
    cmd = "echo -n \"Hello {USERNAME}!\" > $@",
    outs = ["template.txt"],
    srcs = [],
)

substitute_username(
    name = "substitute-buchgr",
    username = "buchgr",
    template = ":generate-template",
)
EOF

  bazel build \
    --genrule_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:substitute-buchgr >& $TEST_log || fail "Failed to build //a:substitute-buchgr"

  # The genrule //a:generate-template should run remotely and //a:substitute-buchgr
  # should be a native action running locally.
  expect_log "3 processes: 2 internal, 1 remote"

  outtxt="bazel-bin/a/substitute-buchgr.txt"
  [[ $(< ${outtxt}) == "Hello buchgr!" ]] \
  || fail "Unexpected contents in "${outtxt}":" $(< ${outtxt})

  [[ -f bazel-bin/a/template.txt ]] \
  || fail "Expected bazel-bin/a/template.txt to be downloaded"
}

function test_downloads_minimal_hit_action_cache() {
  # Test that remote metadata is saved and action cache is hit across server restarts when using
  # --remote_download_minimal
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
)

genrule(
  name = "foobar",
  srcs = [":foo"],
  outs = ["foobar.txt"],
  cmd = "cat $(location :foo) > \"$@\" && echo \"bar\" >> \"$@\"",
)
EOF

  bazel build \
    --experimental_ui_debug_all_events \
    --experimental_action_cache_store_output_metadata \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:foobar >& $TEST_log || fail "Failed to build //a:foobar"

  expect_log "START.*: \[.*\] Executing genrule //a:foobar"

  (! [[ -e bazel-bin/a/foo.txt ]] && ! [[ -e bazel-bin/a/foobar.txt ]]) \
  || fail "Expected no files to have been downloaded"

  assert_equals "" "$(ls bazel-bin/a)"

  bazel shutdown

  bazel build \
    --experimental_ui_debug_all_events \
    --experimental_action_cache_store_output_metadata \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:foobar >& $TEST_log || fail "Failed to build //a:foobar"

  expect_not_log "START.*: \[.*\] Executing genrule //a:foobar"
}

function test_downloads_toplevel() {
  # Test that when using --remote_download_outputs=toplevel only the output of the
  # toplevel target is being downloaded.
  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
)

genrule(
  name = "foobar",
  srcs = [":foo"],
  outs = ["foobar.txt"],
  cmd = "cat $(location :foo) > \"$@\" && echo \"bar\" >> \"$@\"",
)
EOF

  bazel build \
    --genrule_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //a:foobar || fail "Failed to build //a:foobar"

  (! [[ -f bazel-bin/a/foo.txt ]]) \
  || fail "Expected intermediate output bazel-bin/a/foo.txt to not be downloaded"

  [[ -f bazel-bin/a/foobar.txt ]] \
  || fail "Expected toplevel output bazel-bin/a/foobar.txt to be downloaded"


  # Delete the file to test that the action is re-run
  rm -f bazel-bin/a/foobar.txt

  bazel build \
    --genrule_strategy=remote \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //a:foobar >& $TEST_log || fail "Failed to build //a:foobar"

  expect_log "2 processes: 1 remote cache hit, 1 internal"

  [[ -f bazel-bin/a/foobar.txt ]] \
  || fail "Expected toplevel output bazel-bin/a/foobar.txt to be re-downloaded"
}

function test_downloads_toplevel_runfiles() {
  # Test that --remote_download_toplevel fetches only the top level binaries
  # and generated runfiles.
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  mkdir -p a

  cat > a/create_bar.tmpl <<'EOF'
#!/bin/sh
echo "bar runfiles"
exit 0
EOF

  cat > a/foo.cc <<'EOF'
#include <iostream>
int main() { std::cout << "foo" << std::endl; return 0; }
EOF

  cat > a/BUILD <<'EOF'
genrule(
  name = "bar",
  srcs = ["create_bar.tmpl"],
  outs = ["create_bar.sh"],
  cmd = "cat $(location create_bar.tmpl) > \"$@\"",
)

cc_binary(
  name = "foo",
  srcs = ["foo.cc"],
  data = [":bar"],
)
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //a:foo || fail "Failed to build //a:foobar"

  [[ -f bazel-bin/a/foo${EXE_EXT} ]] \
  || fail "Expected toplevel output bazel-bin/a/foo${EXE_EXT} to be downloaded"

  [[ -f bazel-bin/a/create_bar.sh ]] \
  || fail "Expected runfile bazel-bin/a/create_bar.sh to be downloaded"
}

# Test that --remote_download_toplevel fetches inputs to symlink actions. In
# particular, cc_binary links against a symlinked imported .so file, and only
# the symlink is in the runfiles.
function test_downloads_toplevel_symlinks() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  mkdir -p a

  cat > a/bar.cc <<'EOF'
int f() {
  return 42;
}
EOF

  cat > a/foo.cc <<'EOF'
extern int f();
int main() { return f() == 42 ? 0 : 1; }
EOF

  cat > a/BUILD <<'EOF'
cc_binary(
  name = "foo",
  srcs = ["foo.cc"],
  deps = [":libbar_lib"],
)

cc_import(
  name = "libbar_lib",
  shared_library = ":libbar.so",
)

cc_binary(
  name = "libbar.so",
  srcs = ["bar.cc"],
  linkshared = 1,
  linkstatic = 1,
)
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //a:foo || fail "Failed to build //a:foobar"

  ./bazel-bin/a/foo${EXE_EXT} || fail "bazel-bin/a/foo${EXE_EXT} failed to run"
}

function test_symlink_outputs_not_allowed_with_minimial() {
  mkdir -p a
  cat > a/input.txt <<'EOF'
Input file
EOF
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = ["input.txt"],
  outs = ["output.txt", "output_symlink"],
  cmd = "cp $< $(location :output.txt) && ln -s output.txt $(location output_symlink)",
)
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:foo >& $TEST_log && fail "Expected failure to build //a:foo"
  expect_log "Symlinks in action outputs are not yet supported"
}

# Regression test that --remote_download_toplevel does not crash when the
# top-level output is a tree artifact.
function test_downloads_toplevel_tree_artifact() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  mkdir -p a

  # We need the top-level output to be a tree artifact generated by a template
  # action. This is one way to do that: generate a tree artifact of C++ source
  # files, and then compile them with a cc_library / cc_binary rule.
  #
  # The default top-level output of a cc_binary is the final binary, which is
  # not what we want. Instead, we use --output_groups=compilation_outputs to
  # fetch the .o files as the top-level outputs.

  cat > a/gentree.bzl <<'EOF'
def _gentree(ctx):
    out = ctx.actions.declare_directory("dir.cc")
    ctx.actions.run_shell(
        outputs = [out],
        command = "mkdir -p %s && echo 'int main(int c, char** v){return 1;}' > %s/foo.cc" %
            (out.path, out.path),
    )
    return DefaultInfo(files = depset([out]))

gentree = rule(implementation = _gentree)
EOF

  cat > a/BUILD <<'EOF'
load(":gentree.bzl", "gentree")
gentree(name = "tree")
cc_binary(name = "main", srcs = [":tree"])
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    --output_groups=compilation_outputs \
    //a:main || fail "Failed to build //a:main"
}

function test_downloads_toplevel_src_runfiles() {
  # Test that using --remote_download_toplevel with a non-generated (source)
  # runfile dependency works.
  mkdir -p a
  cat > a/create_foo.sh <<'EOF'
#!/bin/sh
echo "foo runfiles"
exit 0
EOF
  chmod +x a/create_foo.sh
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  tools = ["create_foo.sh"],
  outs = ["foo.txt"],
  cmd = "./$(location create_foo.sh) > \"$@\"",
)
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //a:foo || fail "Failed to build //a:foobar"

  [[ -f bazel-bin/a/foo.txt ]] \
  || fail "Expected toplevel output bazel-bin/a/foo.txt to be downloaded"
}

function test_download_toplevel_test_rule() {
  # Test that when using --remote_download_toplevel with bazel test only
  # the test.log and test.xml file are downloaded but not the test binary.
  # However when building a test then the test binary should be downloaded.

  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi

  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_test(
  name = 'test',
  srcs = [ 'test.cc' ],
)
EOF
  cat > a/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello test!" << std::endl; return 0; }
EOF

  # When invoking bazel test only test.log and test.xml should be downloaded.
  bazel test \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //a:test >& $TEST_log || fail "Failed to test //a:test with remote execution"

  (! [[ -f bazel-bin/a/test ]]) \
  || fail "Expected test binary bazel-bin/a/test to not be downloaded"

  [[ -f bazel-testlogs/a/test/test.log ]] \
  || fail "Expected toplevel output bazel-testlogs/a/test/test.log to be downloaded"

  [[ -f bazel-testlogs/a/test/test.xml ]] \
  || fail "Expected toplevel output bazel-testlogs/a/test/test.log to be downloaded"

  bazel clean

  # When invoking bazel build the test binary should be downloaded.
  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //a:test >& $TEST_log || fail "Failed to build //a:test with remote execution"

  ([[ -f bazel-bin/a/test ]]) \
  || fail "Expected test binary bazel-bin/a/test to be downloaded"
}

function test_downloads_minimal_bep() {
  # Test that when using --remote_download_minimal all URI's in the BEP
  # are rewritten as bytestream://..
  mkdir -p a
  cat > a/success.sh <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod 755 a/success.sh
  cat > a/BUILD <<'EOF'
sh_test(
  name = "success_test",
  srcs = ["success.sh"],
)

genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
)
EOF

  bazel test \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    --build_event_text_file=$TEST_log \
    //a:foo //a:success_test || fail "Failed to test //a:foo //a:success_test"

  expect_not_log 'uri:.*file://'
  expect_log "uri:.*bytestream://localhost"
}

function test_bytestream_uri_prefix() {
  # Test that when --remote_bytestream_uri_prefix is set, bytestream://
  # URIs do not contain the hostname that's part of --remote_executor.
  # They should use a fixed value instead.
  mkdir -p a
  cat > a/success.sh <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod 755 a/success.sh
  cat > a/BUILD <<'EOF'
sh_test(
  name = "success_test",
  srcs = ["success.sh"],
)

genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
)
EOF

  bazel test \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    --remote_bytestream_uri_prefix=example.com/my-instance-name \
    --build_event_text_file=$TEST_log \
    //a:foo //a:success_test || fail "Failed to test //a:foo //a:success_test"

  expect_not_log 'uri:.*file://'
  expect_log "uri:.*bytestream://example.com/my-instance-name/blobs"
}

# This test is derivative of test_bep_output_groups in
# build_event_stream_test.sh, which verifies that successful output groups'
# artifacts appear in BEP when a top-level target fails to build.
function test_downloads_minimal_bep_partially_failed_target() {
  # Test that when using --remote_download_minimal all URI's in the BEP
  # are rewritten as bytestream://.. *even when* a target fails to be built and
  # some output groups within that target are successfully built.
  mkdir -p outputgroups
  cat > outputgroups/rules.bzl <<EOF
def _my_rule_impl(ctx):
    group_kwargs = {}
    for name, exit in (("foo", 0), ("bar", 0)):
        outfile = ctx.actions.declare_file(ctx.label.name + "-" + name + ".out")
        ctx.actions.run_shell(
            outputs = [outfile],
            command = "printf %s > %s && exit %d" % (name, outfile.path, exit),
        )
        group_kwargs[name + "_outputs"] = depset([outfile])
    for name, exit, suffix in (
      ("foo", 1, ".fail.out"), ("bar", 0, ".ok.out"), ("bar", 0, ".ok.out2")):
        outfile = ctx.actions.declare_file(ctx.label.name + "-" + name + suffix)
        ctx.actions.run_shell(
            outputs = [outfile],
            command = "printf %s > %s && exit %d" % (name, outfile.path, exit),
        )
        group_kwargs[name + "_outputs"] = depset(
            [outfile], transitive=[group_kwargs[name + "_outputs"]])
    return [OutputGroupInfo(**group_kwargs)]

my_rule = rule(implementation = _my_rule_impl, attrs = {
    "outs": attr.output_list(),
})
EOF
  cat > outputgroups/BUILD <<EOF
load("//outputgroups:rules.bzl", "my_rule")
my_rule(name = "my_lib", outs=[])
EOF

  # In outputgroups/rules.bzl, the `my_rule` definition defines four output
  # groups with different (successful/failed) action counts:
  #    1. foo_outputs (1 successful/1 failed)
  #    2. bar_outputs (1/0)
  #
  # We request both output groups and expect artifacts produced by bar_outputs
  # to appear in BEP with bytestream URIs.
  bazel build //outputgroups:my_lib \
    --remote_executor=grpc://localhost:${worker_port} \
    --keep_going \
    --remote_download_minimal \
    --build_event_text_file=$TEST_log \
    --output_groups=foo_outputs,bar_outputs \
    && fail "expected failure" || true

  expect_not_log 'uri:.*file://'
  expect_log "uri:.*bytestream://localhost"
}

# This test is derivative of test_failing_aspect_bep_output_groups in
# build_event_stream_test.sh, which verifies that successful output groups'
# artifacts appear in BEP when a top-level aspect fails to build.
function test_downloads_minimal_bep_partially_failed_aspect() {
  # Test that when using --remote_download_minimal all URI's in the BEP
  # are rewritten as bytestream://.. *even when* an aspect fails to be built and
  # some output groups within that aspect are successfully built.
  touch BUILD
  cat > semifailingaspect.bzl <<'EOF'
def _semifailing_aspect_impl(target, ctx):
    if not ctx.rule.attr.outs:
        return struct(output_groups = {})
    bad_outputs = list()
    good_outputs = list()
    for out in ctx.rule.attr.outs:
        if out.name[0] == "f":
            aspect_out = ctx.actions.declare_file(out.name + ".aspect.bad")
            bad_outputs.append(aspect_out)
            cmd = "false"
        else:
            aspect_out = ctx.actions.declare_file(out.name + ".aspect.good")
            good_outputs.append(aspect_out)
            cmd = "echo %s > %s" % (out.name, aspect_out.path)
        ctx.actions.run_shell(
            inputs = [],
            outputs = [aspect_out],
            command = cmd,
        )
    return [OutputGroupInfo(**{
        "bad-aspect-out": depset(bad_outputs),
        "good-aspect-out": depset(good_outputs),
    })]

semifailing_aspect = aspect(implementation = _semifailing_aspect_impl)
EOF
  mkdir -p semifailingpkg/
  cat > semifailingpkg/BUILD <<'EOF'
genrule(
  name = "semifail",
  outs = ["out1.txt", "out2.txt", "failingout1.txt"],
  cmd = "for f in $(OUTS); do echo foo > $(RULEDIR)/$$f; done"
)
EOF

  # In semifailingaspect.bzl, the `semifailing_aspect` definition defines two
  # output groups: good-aspect-out and bad-aspect-out. We expect the artifacts
  # produced by good-aspect-out to have bytestream URIs in BEP.
  bazel build //semifailingpkg:semifail \
    --remote_executor=grpc://localhost:${worker_port} \
    --keep_going \
    --remote_download_minimal \
    --build_event_text_file=$TEST_log \
    --aspects=semifailingaspect.bzl%semifailing_aspect \
    --output_groups=good-aspect-out,bad-aspect-out \
    && fail "expected failure" || true

  expect_not_log 'uri:.*file://'
  expect_log "uri:.*bytestream://localhost"
}

function test_downloads_minimal_stable_status() {
  # Regression test for #8385

  mkdir -p a
  cat > a/BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo.txt"],
  cmd = "echo \"foo\" > \"$@\"",
)
EOF

cat > status.sh << 'EOF'
#!/bin/sh
echo "STABLE_FOO 1"
EOF
chmod +x status.sh

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    --workspace_status_command=status.sh \
    //a:foo || "Failed to build //a:foo"

cat > status.sh << 'EOF'
#!/bin/sh
echo "STABLE_FOO 2"
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    --workspace_status_command=status.sh \
    //a:foo || "Failed to build //a:foo"
}

function test_testxml_download_toplevel() {
  # Test that a test action generating its own test.xml file works with
  # --remote_download_toplevel.
  mkdir -p a

  cat > a/test.sh <<'EOF'
#!/bin/sh

cat > "$XML_OUTPUT_FILE" <<EOF2
<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="test" tests="1" failures="0" errors="0">
    <testcase name="test_case" status="run">
      <system-out>test_case succeeded.</system-out>
    </testcase>
  </testsuite>
</testsuites>
EOF2
EOF

  chmod +x a/test.sh

  cat > a/BUILD <<EOF
sh_test(
  name = 'test',
  srcs = [ 'test.sh' ],
)
EOF

  bazel test \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_download_toplevel \
      //a:test \
      || fail "Failed to run //a:test with remote execution"

  TESTXML="bazel-testlogs/a/test/test.xml"
  assert_contains "test_case succeeded" "$TESTXML"
}

# Regression test that Bazel does not crash if remote execution is disabled,
# but --remote_download_toplevel is enabled.
function test_download_toplevel_no_remote_execution() {
  bazel build --remote_download_toplevel \
      || fail "Failed to run bazel build --remote_download_toplevel"
}

function test_download_toplevel_can_delete_directory_outputs() {
  cat > BUILD <<'EOF'
genrule(
    name = 'g',
    outs = ['out'],
    cmd = "touch $@",
)
EOF
  bazel build
  mkdir $(bazel info bazel-genfiles)/out
  touch $(bazel info bazel-genfiles)/out/f
  bazel build \
        --remote_download_toplevel \
        --remote_executor=grpc://localhost:${worker_port} \
        //:g \
        || fail "should have worked"
}

function test_prefetcher_change_permission() {
  # test that prefetcher change permission for downloaded files and directories to 0555.
  touch WORKSPACE

  cat > rules.bzl <<'EOF'
def _gen_output_dir_impl(ctx):
    output_dir = ctx.actions.declare_directory(ctx.attr.outdir)
    ctx.actions.run_shell(
        outputs = [output_dir],
        inputs = [],
        command = """
          mkdir -p $1/sub
          echo "Shuffle, duffle, muzzle, muff" > $1/sub/bar
        """,
        arguments = [output_dir.path],
    )
    return DefaultInfo(files = depset(direct = [output_dir]))

gen_output_dir = rule(
    implementation = _gen_output_dir_impl,
    attrs = {
        "outdir": attr.string(mandatory = True),
    },
)
EOF

  cat > BUILD <<'EOF'
load("rules.bzl", "gen_output_dir")

genrule(
  name = "input-file",
  srcs = [],
  outs = ["file"],
  cmd = "echo 'input' > $@",
)

gen_output_dir(
  name = "input-tree",
  outdir = "tree",
)

genrule(
  name = "test",
  srcs = [":input-file", ":input-tree"],
  outs = ["out"],
  cmd = "touch $@",
  tags = ["no-remote"],
)
EOF

  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_download_minimal \
      //:test >& $TEST_log \
      || fail "Failed to build"

  ls -l bazel-bin/file >& $TEST_log
  expect_log "-r-xr-xr-x"

  ls -ld bazel-bin/tree >& $TEST_log
  expect_log "dr-xr-xr-x"

  ls -ld bazel-bin/tree/sub >& $TEST_log
  expect_log "dr-xr-xr-x"

  ls -l bazel-bin/tree/sub/bar >& $TEST_log
  expect_log "-r-xr-xr-x"
}

function test_remote_cache_intermediate_outputs_toplevel() {
  # test that remote cache is hit when intermediate output is not executable in remote download toplevel mode
  touch WORKSPACE
  cat > BUILD <<'EOF'
genrule(
  name = "dep",
  srcs = [],
  outs = ["dep"],
  cmd = "echo 'dep' > $@",
)

genrule(
  name = "test",
  srcs = [":dep"],
  outs = ["out"],
  cmd = "cat $(SRCS) > $@",
)
EOF

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //:test >& $TEST_log || fail "Failed to build //:test"

  bazel clean

  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //:test >& $TEST_log || fail "Failed to build //:test"

  expect_log "2 remote cache hit"
}

function test_remote_download_toplevel_with_non_toplevel_unused_inputs_list() {
  # Test that --remote_download_toplevel should download non-toplevel
  # unused_inputs_list for starlark action. See #11732.

  touch WORKSPACE

  cat > test.bzl <<'EOF'
def _test_rule_impl(ctx):
    inputs = ctx.files.inputs
    output = ctx.outputs.out
    unused_inputs_list = ctx.actions.declare_file(ctx.label.name + ".unused")
    arguments = []
    arguments += [output.path]
    arguments += [unused_inputs_list.path]
    for input in inputs:
        arguments += [input.path]
    ctx.actions.run(
        inputs = inputs,
        outputs = [output, unused_inputs_list],
        arguments = arguments,
        executable = ctx.executable._executable,
        unused_inputs_list = unused_inputs_list,
    )

test_rule = rule(
    implementation = _test_rule_impl,
    attrs = {
        "inputs": attr.label_list(allow_files = True),
        "out": attr.output(),
        "_executable": attr.label(executable = True, cfg = "exec", default = "//:exe"),
    },
)
EOF

  cat > BUILD <<'EOF'
load(":test.bzl", "test_rule")

test_rule(
    name = "test_non_toplevel",
    inputs = ["1.txt", "2.txt"],
    out = "3.txt",
)

sh_binary(
    name = "exe",
    srcs = ["a.sh"],
)

genrule(
    name = "test",
    srcs = [":test_non_toplevel"],
    outs = ["4.txt"],
    cmd = "cat $< > $@",
)
EOF

  cat > a.sh <<'EOF'
#!/bin/sh

output="$1"
shift
unused="$1"
shift
inp0="$1"
shift

cat "$inp0" > "$output"
echo "$1" > "$unused"
EOF

  chmod a+x a.sh

  touch 1.txt 2.txt

  CACHEDIR=$(mktemp -d)

  bazel build --disk_cache="$CACHEDIR" --remote_download_toplevel :test || fail "Failed to build :test"

  bazel clean || fail "Failed to clean"

  bazel build --disk_cache="$CACHEDIR" --remote_download_toplevel :test >& $TEST_log

  expect_log "INFO: Build completed successfully"
}

# Test that when testing with --remote_download_minimal, Bazel doesn't
# regenerate the test.xml if the action actually produced it. See
# https://github.com/bazelbuild/bazel/issues/12554
function test_remote_download_minimal_with_test_xml_generation() {
  mkdir -p a

  cat > a/BUILD <<'EOF'
sh_test(
    name = "test0",
    srcs = ["test.sh"],
)

java_test(
    name = "test1",
    srcs = ["JavaTest.java"],
    test_class = "JavaTest",
)
EOF

  cat > a/test.sh <<'EOF'
#!/bin/bash
echo 'Hello'
EOF
  chmod a+x a/test.sh

  cat > a/JavaTest.java <<'EOF'
import org.junit.Test;

public class JavaTest {
    @Test
    public void test() {}
}
EOF

  bazel build \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:test0 //a:test1 >& $TEST_log || fail "Failed to build"

  bazel test \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:test0 >& $TEST_log || fail "Failed to test"
  # 2 remote spawns: 1 for executing the test, 1 for generating the test.xml
  expect_log "2 processes: 2 remote"

  bazel test \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    //a:test1 >& $TEST_log || fail "Failed to test"
  # only 1 remote spawn: test.xml is generated by junit
  expect_log "2 processes: 1 internal, 1 remote"
}

function test_output_file_permission() {
  # Test that permission of output files are always 0555

  mkdir -p a
  cat > a/BUILD <<EOF
genrule(
  name = "foo",
  srcs = [],
  outs = ["foo"],
  cmd = "echo 'foo' > \$@",
)

genrule(
  name = "bar",
  srcs = [":foo"],
  outs = ["bar"],
  cmd = "ls -lL \$(SRCS) > \$@",
  tags = ["no-remote"],
)
EOF

  # no remote execution
  bazel build \
      //a:bar >& $TEST_log || fail "Failed to build"

  ls -l bazel-bin/a/bar >& $TEST_log
  expect_log "-r-xr-xr-x"

  ls -l bazel-bin/a/foo >& $TEST_log
  expect_log "-r-xr-xr-x"

  cat bazel-bin/a/bar >& $TEST_log
  expect_log "-r-xr-xr-x"

  bazel clean >& $TEST_log || fail "Failed to clean"

  # normal remote execution
  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      //a:bar >& $TEST_log || fail "Failed to build"

  ls -l bazel-bin/a/bar >& $TEST_log
  expect_log "-r-xr-xr-x"

  ls -l bazel-bin/a/foo >& $TEST_log
  expect_log "-r-xr-xr-x"

  cat bazel-bin/a/bar >& $TEST_log
  expect_log "-r-xr-xr-x"

  bazel clean >& $TEST_log || fail "Failed to clean"

  # build without bytes
  bazel build \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_download_minimal \
      //a:bar >& $TEST_log || fail "Failed to build"

  ls -l bazel-bin/a/bar >& $TEST_log
  expect_log "-r-xr-xr-x"

  ls -l bazel-bin/a/foo >& $TEST_log
  expect_log "-r-xr-xr-x"

  cat bazel-bin/a/bar >& $TEST_log
  expect_log "-r-xr-xr-x"
}

function test_download_toplevel_when_turn_remote_cache_off() {
  download_toplevel_when_turn_remote_cache_off
}

function test_download_toplevel_when_turn_remote_cache_off_with_metadata() {
  download_toplevel_when_turn_remote_cache_off --experimental_action_cache_store_output_metadata
}

function download_toplevel_when_turn_remote_cache_off() {
  # Test that BwtB doesn't cause build failure if remote cache is disabled in a following build.
  # See https://github.com/bazelbuild/bazel/issues/13882.

  cat > .bazelrc <<EOF
build --verbose_failures
EOF
  mkdir a
  cat > a/BUILD <<'EOF'
genrule(
    name = "producer",
    outs = ["a.txt", "b.txt"],
    cmd = "touch $(OUTS)",
)
genrule(
    name = "consumer",
    outs = ["out.txt"],
    srcs = [":b.txt", "in.txt"],
    cmd = "cat $(SRCS) > $@",
)
EOF
  echo 'foo' > a/in.txt

  # populate the cache
  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    //a:consumer >& $TEST_log || fail "Failed to populate the cache"

  bazel clean >& $TEST_log || fail "Failed to clean"

  # download top level outputs
  bazel build \
    --remote_cache=grpc://localhost:${worker_port} \
    --remote_download_toplevel \
    "$@" \
    //a:consumer >& $TEST_log || fail "Failed to download outputs"
  [[ -f bazel-bin/a/a.txt ]] || [[ -f bazel-bin/a/b.txt ]] \
    && fail "Expected outputs of producer are not downloaded"

  # build without remote cache
  echo 'bar' > a/in.txt
  bazel build \
    --remote_download_toplevel \
    "$@" \
    //a:consumer >& $TEST_log || fail "Failed to build without remote cache"
}

function test_download_top_level_remote_execution_after_local_fetches_inputs() {
  if [[ "$PLATFORM" == "darwin" ]]; then
    # TODO(b/37355380): This test is disabled due to RemoteWorker not supporting
    # setting SDKROOT and DEVELOPER_DIR appropriately, as is required of
    # action executors in order to select the appropriate Xcode toolchain.
    return 0
  fi
  mkdir a
  cat > a/BUILD <<'EOF'
genrule(name="dep", srcs=["not_used"], outs=["dep.c"], cmd="touch $@")
cc_library(name="foo", srcs=["dep.c"])
EOF
  echo hello > a/not_used
  bazel build \
      --experimental_ui_debug_all_events \
      --remote_executor=grpc://localhost:"${worker_port}" \
      --remote_download_toplevel \
      --genrule_strategy=local \
      //a:dep >& "${TEST_log}" || fail "Expected success"
  expect_log "START.*: \[.*\] Executing genrule //a:dep"

  echo there > a/not_used
  # Local compilation requires now remote dep.c to successfully download.
  bazel build \
      --experimental_ui_debug_all_events \
      --remote_executor=grpc://localhost:"${worker_port}" \
      --remote_download_toplevel \
      --genrule_strategy=remote \
      --strategy=CppCompile=local \
      //a:foo >& "${TEST_log}" || fail "Expected success"

  expect_log "START.*: \[.*\] Executing genrule //a:dep"
}

function test_remote_download_regex() {
  mkdir -p a

  cat > a/BUILD <<'EOF'
java_library(
    name = "lib",
    srcs = ["Library.java"],
)
java_test(
    name = "test",
    srcs = ["JavaTest.java"],
    test_class = "JavaTest",
    deps = [":lib"],
)
EOF

  cat > a/Library.java <<'EOF'
public class Library {
  public static boolean TEST = true;
}
EOF

  cat > a/JavaTest.java <<'EOF'
import org.junit.Assert;
import org.junit.Test;
public class JavaTest {
    @Test
    public void test() { Assert.assertTrue(Library.TEST); }
}
EOF
  bazel test \
      --remote_executor=grpc://localhost:${worker_port} \
      --remote_download_minimal \
      //a:test >& $TEST_log || fail "Failed to build"

  [[ ! -e "bazel-bin/a/liblib.jar" ]] || fail "bazel-bin/a/liblib.jar shouldn't exist"
  [[ ! -e "bazel-bin/a/liblib.jdeps" ]] || fail "bazel-bin/a/liblib.jdeps shouldn't exist"

  bazel clean && bazel test \
        --remote_executor=grpc://localhost:${worker_port} \
        --remote_download_minimal \
        --experimental_remote_download_regex=".*" \
        //a:test >& $TEST_log || fail "Failed to build"

  [[ -e "bazel-bin/a/liblib.jar" ]] || fail "bazel-bin/a/liblib.jar file does not exist!"
  [[ -e "bazel-bin/a/liblib.jdeps" ]] || fail "bazel-bin/a/liblib.jdeps file does not exist!"

  bazel clean && bazel test \
    --remote_executor=grpc://localhost:${worker_port} \
    --remote_download_minimal \
    --experimental_remote_download_regex=".*jar$" \
    //a:test >& $TEST_log || fail "Failed to build"

  [[ -e "bazel-bin/a/liblib.jar" ]] || fail "bazel-bin/a/liblib.jar file does not exist!"
  [[ ! -e "bazel-bin/a/liblib.jdeps" ]] || fail "bazel-bin/a/liblib.jdeps shouldn't exist"
}

run_suite "Build without the Bytes tests"
