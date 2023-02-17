#!/bin/bash

# Copyright 2019 The Bazel Authors. All rights reserved.
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

# A script to upload a given java_tools zip on GCS. Used by the java_tools_binaries
# Buildkite pipeline. It is not recommended to run this script manually.

# Script used by the "java_tools binaries" Buildkite pipeline to build the java tools archives
# and upload them on GCS.
#
# The script has to be executed directly without invoking bazel:
# $ src/upload_all_java_tools.sh
#
# The script cannot be invoked through a sh_binary using bazel because git
# cannot be used through a sh_binary.

set -euo pipefail

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r platform=windows
  ;;
linux*)
  declare -r platform=linux
  ;;
*)
  declare -r platform=other
  ;;
esac

echo Platform: $platform

if [[ "$platform" == "windows" ]]; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

commit_hash=$(git rev-parse HEAD)
timestamp=$(date +%s)
bazel_version=$(bazel info release | cut -d' ' -f2)

JAVA_BUILD_OPTS="--tool_java_language_version=8 --java_language_version=8"

# Passing the same commit_hash and timestamp to all targets to mark all the artifacts
# uploaded on GCS with the same identifier.

bazel build ${JAVA_BUILD_OPTS} //src:java_tools_zip
zip_path=${PWD}/bazel-bin/src/java_tools.zip

bazel build ${JAVA_BUILD_OPTS} //src:java_tools_prebuilt_zip
prebuilt_zip_path=${PWD}/bazel-bin/src/java_tools_prebuilt.zip

if [[ "$platform" == "windows" ]]; then
    # Windows needs "file:///c:/foo/bar".
    file_url="file:///$(cygpath -m ${zip_path})"
    prebuilt_file_url="file:///$(cygpath -m ${prebuilt_zip_path})"
else
    # Non-Windows needs "file:///foo/bar".
    file_url="file://${zip_path}"
    prebuilt_file_url="file://${prebuilt_zip_path}"
fi

# Skip for now, as the test is broken on Windows.
# See https://github.com/bazelbuild/bazel/issues/12244 for details
if [[ "$platform" != "windows" ]]; then
    JAVA_VERSIONS=`cat src/test/shell/bazel/BUILD | grep '^JAVA_VERSIONS = ' | sed -e 's/JAVA_VERSIONS = //' | sed -e 's/["(),]//g'`
    for java_version in $JAVA_VERSIONS; do
        bazel test --verbose_failures --test_output=all --nocache_test_results \
            //src/test/shell/bazel:bazel_java_test_local_java_tools_jdk${java_version} \
            --define=LOCAL_JAVA_TOOLS_ZIP_URL="${file_url}" \
            --define=LOCAL_JAVA_TOOLS_PREBUILT_ZIP_URL="${prebuilt_file_url}"
    done
fi

bazel run ${JAVA_BUILD_OPTS} //src:upload_java_tools_prebuilt -- \
    --commit_hash ${commit_hash} \
    --timestamp ${timestamp} \
    --bazel_version ${bazel_version}

if [[ "$platform" == "linux" ]]; then
    bazel run ${JAVA_BUILD_OPTS} //src:upload_java_tools -- \
        --commit_hash ${commit_hash} \
        --timestamp ${timestamp} \
        --bazel_version ${bazel_version}

    bazel run ${JAVA_BUILD_OPTS} //src:upload_java_tools_dist -- \
        --commit_hash ${commit_hash} \
        --timestamp ${timestamp} \
        --bazel_version ${bazel_version}
fi
