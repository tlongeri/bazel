// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.proto;

/** Constants used in Proto rules. */
public final class ProtoConstants {
  /** Default label for proto compiler. */
  static final String DEFAULT_PROTOC_LABEL =  "@com_google_protobuf//:protoc";

  /**
   * This constant is used in ProtoCompileActionBuilder to generate an error message that's
   * displayed when a strict proto deps violation occurs.
   *
   * <p>%1$s is replaced with the label of the proto_library rule that's currently being built.
   *
   * <p>%%s is replaced with the literal "%s", which is passed to the proto-compiler, which replaces
   * it with the .proto file that violates strict proto deps.
   */
  static final String STRICT_PROTO_DEPS_VIOLATION_MESSAGE =
      "%%s is imported, but %1$s doesn't directly depend on a proto_library that 'srcs' it.";

  private ProtoConstants() {}
}
