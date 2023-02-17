#!/bin/bash -eu
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

# This integration test exists so that we can run our Starlark tests
# for cc_import with Bazel built from head. Once the Stararlark
# implementation can rely on release Bazel, we can add the tests directly.

function setup_tests() {
  setup_skylib_support

  src="$TEST_SRCDIR/io_bazel/$1"
  dest="${2:-$1}"
  if [ ! -e "$src" ]; then
    echo "copy_tests() - $src does not exist" 1>&2; exit 1
  fi

  if [ ! -e "$dest" ]; then
    mkdir -p "$(dirname "$dest")"
    cp  -r "$src" "$dest"
  fi
  chmod -R ugo+rwx "$dest"

  for file in $(find $dest/* -name *.builtin_test); do
    mv $file ${file%%.builtin_test}
  done
}
