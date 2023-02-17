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

"""Attributes common to Objc rules"""

load("@_builtins//:common/objc/semantics.bzl", "semantics")

CcInfo = _builtins.toplevel.CcInfo
AppleDynamicFrameworkInfo = _builtins.toplevel.apple_common.AppleDynamicFramework
TemplateVariableInfo = _builtins.toplevel.platform_common.TemplateVariableInfo

# Private attribute required by `objc_internal.expand_toolchain_and_ctx_variables`
_CC_TOOLCHAIN_RULE = {
    "_cc_toolchain": attr.label(
        default = "@" + semantics.get_repo() + "//tools/cpp:current_cc_toolchain",
    ),
}

_COMPILING_RULE = {
    "srcs": attr.label_list(
        allow_files = [
            # NON_CPP_SOURCES
            ".m",
            ".c",
            # CPP_SOURCES
            ".cc",
            ".cpp",
            ".mm",
            ".cxx",
            ".C",
            # ASSEMBLY_SOURCES
            ".s",
            ".S",
            ".asm",
            # OBJECT_FILE_SOURCES
            ".o",
            # HEADERS
            ".h",
            ".inc",
            ".hpp",
            ".hh",
        ],
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
    ),
    "non_arc_srcs": attr.label_list(
        allow_files = [".m", ".mm"],
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
    ),
    "pch": attr.label(
        allow_single_file = [".pch"],
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
    ),
    "runtime_deps": attr.label_list(
        providers = [AppleDynamicFrameworkInfo],
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
    ),
    "defines": attr.string_list(),
    "enable_modules": attr.bool(),
    "linkopts": attr.string_list(),
    "module_map": attr.label(allow_files = [".modulemap"]),
    "module_name": attr.string(),
    # How many rules use this in the depot?
    "stamp": attr.bool(),
}

_COMPILE_DEPENDENCY_RULE = {
    "hdrs": attr.label_list(
        allow_files = True,
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
    ),
    "textual_hdrs": attr.label_list(
        allow_files = True,
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
    ),
    "includes": attr.string_list(),
    "sdk_includes": attr.string_list(),
    "deps": attr.label_list(
        providers = [CcInfo],
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
    ),
}

_INCLUDE_SCANNING_RULE = {
    "_grep_includes": attr.label(
        allow_single_file = True,
        cfg = "exec",
        default = "@" + semantics.get_repo() + "//tools/cpp:grep-includes",
        executable = True,
    ),
}

_SDK_FRAMEWORK_DEPENDER_RULE = {
    "sdk_frameworks": attr.string_list(),
    "weak_sdk_frameworks": attr.string_list(),
    "sdk_dylibs": attr.string_list(),
}

_COPTS_RULE = {
    "copts": attr.string_list(),
}

_XCRUN_RULE = {
    "_xcrunwrapper": attr.label(
        cfg = "exec",
        default = "@" + semantics.get_repo() + "//tools/objc:xcrunwrapper",
        executable = True,
    ),
    "alwayslink": attr.bool(),
    "_xcode_config": attr.label(
        default = configuration_field(
            fragment = "apple",
            name = "xcode_config_label",
        ),
    ),
}

_PLATFORM_RULE = {
    "platform_type": attr.string(mandatory = True),
    "minimum_os_version": attr.string(),
}

def _union(*dictionaries):
    result = {}
    for dictionary in dictionaries:
        result.update(dictionary)
    return result

common_attrs = struct(
    union = _union,
    CC_TOOLCHAIN_RULE = _CC_TOOLCHAIN_RULE,
    COMPILING_RULE = _COMPILING_RULE,
    COMPILE_DEPENDENCY_RULE = _COMPILE_DEPENDENCY_RULE,
    INCLUDE_SCANNING_RULE = _INCLUDE_SCANNING_RULE,
    SDK_FRAMEWORK_DEPENDER_RULE = _SDK_FRAMEWORK_DEPENDER_RULE,
    COPTS_RULE = _COPTS_RULE,
    XCRUN_RULE = _XCRUN_RULE,
    LICENSES = semantics.get_licenses_attr(),
    PLATFORM_RULE = _PLATFORM_RULE,
)
