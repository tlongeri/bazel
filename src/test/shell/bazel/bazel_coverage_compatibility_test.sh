#!/bin/bash
#
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

set -eu

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function set_up_sh_test_coverage() {
  cat <<EOF > BUILD
constraint_setting(name = "incompatible_setting")

constraint_value(
    name = "incompatible",
    constraint_setting = ":incompatible_setting",
)

sh_test(
    name = "compatible_test",
    srcs = ["compatible_test.sh"],
)

sh_test(
    name = "incompatible_test",
    srcs = ["incompatible_test.sh"],
    target_compatible_with = [":incompatible"],
)
EOF
  cat <<EOF > compatible_test.sh
#!/bin/bash
exit 0
EOF
  cat <<EOF > incompatible_test.sh
#!/bin/bash
exit 1
EOF
  chmod +x compatible_test.sh
  chmod +x incompatible_test.sh
}

# Validates that coverage skips incompatible tests.  This is a regression test for
# https://github.com/bazelbuild/bazel/issues/15385.
function test_sh_test_coverage() {
  set_up_sh_test_coverage
  bazel coverage --test_output=all --combined_report=lcov //:all &>$TEST_log \
    || fail "Coverage for //:all failed"
  expect_log "INFO: Build completed successfully"
  expect_log "//:compatible_test .* PASSED"
  expect_log "//:incompatible_test .* SKIPPED"
}

run_suite "test tests"
