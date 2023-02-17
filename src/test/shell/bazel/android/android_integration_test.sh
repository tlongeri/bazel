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

# For these tests to run do the following:
#
#   1. Install an Android SDK from https://developer.android.com
#   2. Set the $ANDROID_HOME environment variable
#   3. Uncomment the line in WORKSPACE containing android_sdk_repository
#
# Note that if the environment is not set up as above android_integration_test
# will silently be ignored and will be shown as passing.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "${CURRENT_DIR}/android_helper.sh" \
  || { echo "android_helper.sh not found!" >&2; exit 1; }
fail_if_no_android_sdk

source "${CURRENT_DIR}/../../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

if [[ "$1" = '--with_platforms' ]]; then
  resolve_android_toolchains_with_platforms
fi

function test_sdk_library_deps() {
  create_new_workspace
  setup_android_sdk_support

  mkdir -p java/a
  cat > java/a/BUILD<<EOF
android_library(
    name = "a",
    exports = ["@androidsdk//com.android.support:mediarouter-v7-24.0.0"],
)
EOF

  bazel build --nobuild //java/a:a || fail "build failed"
}

function test_allow_custom_manifest_name() {
  create_new_workspace
  setup_android_sdk_support
  create_android_binary
  mv java/bazel/AndroidManifest.xml java/bazel/SomeOtherName.xml

  # macOS requires an argument for the backup file extension.
  sed -i'' -e 's/AndroidManifest/SomeOtherName/' java/bazel/BUILD

  bazel build //java/bazel:bin || fail "Build failed" \
    "Failed to build android_binary with custom Android manifest file name"
}

function test_legacy_multidex() {
  create_new_workspace
  setup_android_sdk_support
  create_android_binary
  mkdir -p java/bazel/multidex
  cat > java/bazel/multidex/BUILD <<EOF
android_binary(
    name = "bin",
    manifest = "AndroidManifest.xml",
    multidex = "legacy",
    deps = ["//java/bazel:lib"],
)
EOF
  cat > java/bazel/multidex/AndroidManifest.xml <<EOF
<manifest package="bazel.multidex" />
EOF
  assert_build //java/bazel/multidex:bin
}

function write_hello_android_files() {
  mkdir -p java/com/example/hello
  mkdir -p java/com/example/hello/res/values
  cat > java/com/example/hello/res/values/strings.xml <<'EOF'
<resources>
    <string name="app_name">HelloWorld</string>
    <string name="title_activity_main">Hello Main</string>
</resources>
EOF

  cat > java/com/example/hello/AndroidManifest.xml <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.hello"
    android:versionCode="1"
    android:versionName="1.0" >

    <uses-sdk
        android:minSdkVersion="7"
        android:targetSdkVersion="18" />

    <application android:label="@string/app_name">
        <activity
            android:name="com.example.hello.MainActivity"
            android:label="@string/title_activity_main" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

  cat > java/com/example/hello/MainActivity.java <<'EOF'
package com.example.hello;

import android.app.Activity;

public class MainActivity extends Activity {
}
EOF

}

function test_d8_dexes_hello_android() {
  write_hello_android_files
  setup_android_sdk_support
  cat > java/com/example/hello/BUILD <<'EOF'
android_binary(
    name = 'hello',
    manifest = "AndroidManifest.xml",
    srcs = ['MainActivity.java'],
    resource_files = glob(["res/**"]),
)
EOF

  bazel clean
  bazel build --define=android_standalone_dexing_tool=d8_compat_dx \
      //java/com/example/hello:hello || fail "build failed"
}

function test_d8_dexes_and_desugars_hello_android() {
  write_hello_android_files
  setup_android_sdk_support
  cat > java/com/example/hello/BUILD <<'EOF'
android_binary(
    name = 'hello',
    manifest = "AndroidManifest.xml",
    srcs = ['MainActivity.java'],
    resource_files = glob(["res/**"]),
)
EOF

  bazel clean
  # Note: D8 desugaring with persistent workers is not currently supported, so
  # need to add --strategy=Desugar=sandboxed
  bazel build --define=android_standalone_dexing_tool=d8_compat_dx \
      --define=android_desugaring_tool=d8 \
      --strategy=Desugar=sandboxed \
      //java/com/example/hello:hello || fail "build failed"
}

function test_android_tools_version() {
  create_new_workspace
  setup_android_sdk_support

  label="1.2.3 4.5.6 1000000000000000000000000000000000000002"
  bazel build --embed_label="$label" //tools/android/runtime_deps:version.txt
  actual="$(cat bazel-bin/tools/android/runtime_deps/version.txt)"
  expected="bazel_android_tools_version 1.2.3
bazel_repo_commit 1000000000000000000000000000000000000002
built_with_bazel_version 4.5.6"
  assert_equals "$expected" "$actual"
}

run_suite "Android integration tests"
