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

"""objc_import Starlark implementation replacing native"""

load("@_builtins//:common/objc/attrs.bzl", "common_attrs")
load("@_builtins//:common/objc/compilation_support.bzl", "compilation_support")
load("@_builtins//:common/cc/cc_helper.bzl", "cc_helper")

objc_internal = _builtins.internal.objc_internal
CcInfo = _builtins.toplevel.CcInfo
cc_common = _builtins.toplevel.cc_common

def _objc_import_impl(ctx):
    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    common_variables = compilation_support.build_common_variables(
        ctx = ctx,
        deps = ctx.attr.deps,
        toolchain = cc_toolchain,
        alwayslink = ctx.attr.alwayslink,
        extra_import_libraries = ctx.files.archives,
        empty_compilation_artifacts = True,
    )

    compilation_support.validate_attributes(common_variables)

    (compilation_context, _, _, _) = compilation_support.register_compile_and_archive_actions(
        common_variables,
    )

    libraries_to_link = []
    for archive in ctx.files.archives:
        library_to_link = cc_common.create_library_to_link(
            actions = ctx.actions,
            cc_toolchain = cc_toolchain,
            static_library = archive,
            alwayslink = common_variables.alwayslink,
        )
        libraries_to_link.append(library_to_link)

    linking_context = cc_common.create_linking_context(
        linker_inputs = depset(
            direct = [
                cc_common.create_linker_input(
                    owner = ctx.label,
                    libraries = depset(libraries_to_link),
                    user_link_flags = common_variables.objc_linking_context.linkopts,
                ),
            ],
        ),
    )

    cc_info = cc_common.merge_cc_infos(
        direct_cc_infos = [
            CcInfo(
                compilation_context = compilation_context,
                linking_context = linking_context,
            ),
        ],
        cc_infos = [dep[CcInfo] for dep in ctx.attr.deps],
    )

    return [
        cc_info,
        common_variables.objc_provider,
    ]

objc_import = rule(
    implementation = _objc_import_impl,
    attrs = common_attrs.union(
        {
            "archives": attr.label_list(allow_empty = False, mandatory = True, allow_files = [".a"]),
        },
        common_attrs.CC_TOOLCHAIN_RULE,
        common_attrs.LICENSES,
        common_attrs.COMPILE_DEPENDENCY_RULE,
        common_attrs.INCLUDE_SCANNING_RULE,
        common_attrs.SDK_FRAMEWORK_DEPENDER_RULE,
        common_attrs.XCRUN_RULE,
    ),
    fragments = ["objc", "apple", "cpp"],
    toolchains = cc_helper.use_cpp_toolchain(),
    incompatible_use_toolchain_transition = True,
)
