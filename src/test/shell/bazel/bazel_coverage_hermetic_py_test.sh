#!/bin/bash
#
# Copyright 2015 The Bazel Authors. All rights reserved.
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

set -eu

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# Fetch hermetic python and register toolchain.
function set_up() {
    cat >>WORKSPACE <<EOF
register_toolchains(
    "//:python_toolchain",
)
EOF
}

# Returns the path of the code coverage report that was generated by Bazel by
# looking at the current $TEST_log. The method fails if TEST_log does not
# contain any coverage report for a passed test.
function get_coverage_file_path_from_test_log() {
  local ending_part
  ending_part="$(sed -n -e '/PASSED/,$p' "$TEST_log")"

  local coverage_file_path
  coverage_file_path=$(grep -Eo "/[/a-zA-Z0-9\.\_\-]+\.dat$" <<< "$ending_part")
  [[ -e "$coverage_file_path" ]] || fail "Coverage output file does not exist!"
  echo "$coverage_file_path"
}

function set_up_py_test_coverage() {
  # Set up python toolchain.
  cat <<EOF > BUILD
load("@bazel_tools//tools/python:toolchain.bzl", "py_runtime_pair")

py_runtime(
    name = "py3_runtime",
    coverage_tool = ":mock_coverage",
    interpreter_path = "$(which python3)",
    python_version = "PY3",
)

py_runtime_pair(
    name = "python_runtimes",
    py2_runtime = None,
    py3_runtime = ":py3_runtime",
)

toolchain(
    name = "python_toolchain",
    toolchain = ":python_runtimes",
    toolchain_type = "@bazel_tools//tools/python:toolchain_type",
)
EOF
  # Add a py_library and test.
  cat <<EOF >> BUILD
py_library(
    name = "hello",
    srcs = ["hello.py"],
)

py_library(
    name = "mock_coverage",
    srcs = ["mock_coverage.py"],
    deps = [":coverage_support"],
)

py_library(
    name = "coverage_support",
    srcs = ["coverage_support.py"],
)

py_test(
    name = "hello_test",
    srcs = ["hello_test.py"],
    deps = [":hello"],
)
EOF
  echo "# fake dependency" > coverage_support.py
  cat <<EOF > mock_coverage.py
#!/usr/bin/env python3
import argparse
import os
import subprocess
import sys
import coverage_support
parser = argparse.ArgumentParser()
mode = sys.argv[1]
del(sys.argv[1])
parser.add_argument("--rcfile", type=str)
parser.add_argument("--append", action="store_true")
parser.add_argument("--branch", action="store_true")
parser.add_argument("--output", "-o", type=str)
parser.add_argument("target", nargs="*")
args = parser.parse_args()
tmp_cov_file = os.path.join(os.environ["COVERAGE_DIR"], "tmp.out")
if mode == "run":
  subprocess.check_call([sys.executable]+args.target)
  with open(tmp_cov_file, "a") as tmp:
    tmp.write("TN:\nSF:")
    tmp.write(os.path.join(os.path.dirname(os.path.realpath(args.target[0])), "hello.py"))
    tmp.write("""
FNF:0
FNH:0
DA:1,1,fi+A0ud2xABMExsbhdW38w
DA:2,1,3qA2I6CcUyJmcd1vpeVcRA
DA:4,1,nFnrj5CwYCqkvbVhPUFVVw
DA:5,0,RmWioilSA3bI5NbLlwiuSA
LH:3
LF:4
end_of_record
""")
else:
  with open(args.output, "w") as out_file:
    with open(tmp_cov_file, "r") as in_file:
      out_file.write(in_file.read())
EOF
  cat <<EOF > hello.py
def Hello():
  print("Hello, world!")

def Goodbye():
  print("Goodbye, world!")
EOF
  cat <<EOF > hello_test.py
import unittest
import hello

class Tests(unittest.TestCase):
  def testHello(self):
    hello.Hello()

if __name__ == "__main__":
  unittest.main()
EOF
  cat <<EOF > expected.dat
SF:hello.py
FNF:0
FNH:0
DA:1,1,fi+A0ud2xABMExsbhdW38w
DA:2,1,3qA2I6CcUyJmcd1vpeVcRA
DA:4,1,nFnrj5CwYCqkvbVhPUFVVw
DA:5,0,RmWioilSA3bI5NbLlwiuSA
LH:3
LF:4
end_of_record
EOF
}

function test_py_test_coverage() {
  set_up_py_test_coverage
  bazel coverage --test_output=all //:hello_test &>$TEST_log || fail "Coverage for //:hello_test failed"
  local coverage_file_path
  coverage_file_path="$( get_coverage_file_path_from_test_log )"
  diff expected.dat "$coverage_file_path" >> $TEST_log
  cmp expected.dat "$coverage_file_path" || fail "Coverage output file is different than the expected file for py_library."
}

run_suite "test tests"
