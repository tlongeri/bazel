# Copyright 2020 The Bazel Authors. All rights reserved.
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

"""Starlark implementation of cc_import.

We may change the implementation at any moment or even delete this file. Do not
rely on this. Pass the flag --experimental_starlark_cc_import
"""

load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/objc/semantics.bzl", "semantics")

CcInfo = _builtins.toplevel.CcInfo
cc_common = _builtins.toplevel.cc_common

CPP_LINK_STATIC_LIBRARY_ACTION_NAME = "c++-link-static-library"

def _to_list(element):
    if element == None:
        return []
    else:
        return [element]

def _to_depset(element):
    if element == None:
        return depset()
    else:
        return depset([element])

def _is_shared_library_extension_valid(shared_library_name):
    if (shared_library_name.endswith(".so") or
        shared_library_name.endswith(".dll") or
        shared_library_name.endswith(".dylib")):
        return True

    # validate agains the regex "^.+\\.((so)|(dylib))(\\.\\d\\w*)+$",
    # must match VERSIONED_SHARED_LIBRARY.
    for ext in (".so.", ".dylib."):
        name, _, version = shared_library_name.rpartition(ext)
        if name and version:
            version_parts = version.split(".")
            for part in version_parts:
                if not part[0].isdigit():
                    return False
                for c in part[1:].elems():
                    if not (c.isalnum() or c == "_"):
                        return False
            return True

    return False

def _perform_error_checks(
        system_provided,
        shared_library_artifact,
        interface_library_artifact):
    # If the shared library will be provided by system during runtime, users are not supposed to
    # specify shared_library.
    if system_provided and shared_library_artifact != None:
        fail("'shared_library' shouldn't be specified when 'system_provided' is true")

    # If a shared library won't be provided by system during runtime and we are linking the shared
    # library through interface library, the shared library must be specified.
    if (not system_provided and shared_library_artifact == None and
        interface_library_artifact != None):
        fail("'shared_library' should be specified when 'system_provided' is false")

    if (shared_library_artifact != None and
        not _is_shared_library_extension_valid(shared_library_artifact.basename)):
        fail("'shared_library' does not produce any cc_import shared_library files (expected .so, .dylib or .dll)")

def _create_archive_action(
        ctx,
        feature_configuration,
        cc_toolchain,
        output_file,
        object_files):
    archiver_path = cc_common.get_tool_for_action(
        feature_configuration = feature_configuration,
        action_name = CPP_LINK_STATIC_LIBRARY_ACTION_NAME,
    )
    archiver_variables = cc_common.create_link_variables(
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        output_file = output_file.path,
        is_using_linker = False,
    )
    command_line = cc_common.get_memory_inefficient_command_line(
        feature_configuration = feature_configuration,
        action_name = CPP_LINK_STATIC_LIBRARY_ACTION_NAME,
        variables = archiver_variables,
    )
    args = ctx.actions.args()
    args.add_all(command_line)
    args.add_all(object_files)
    args.use_param_file("@%s", use_always = True)

    env = cc_common.get_environment_variables(
        feature_configuration = feature_configuration,
        action_name = CPP_LINK_STATIC_LIBRARY_ACTION_NAME,
        variables = archiver_variables,
    )

    # TODO(bazel-team): PWD=/proc/self/cwd env var is missing, but it is present when an analogous archiving
    # action is created by cc_library
    ctx.actions.run(
        executable = archiver_path,
        arguments = [args],
        env = env,
        inputs = depset(
            direct = object_files,
            transitive = [
                cc_toolchain.all_files,
            ],
        ),
        use_default_shell_env = True,
        outputs = [output_file],
        mnemonic = "CppArchive",
    )

def _cc_import_impl(ctx):
    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )

    _perform_error_checks(
        ctx.attr.system_provided,
        ctx.file.shared_library,
        ctx.file.interface_library,
    )

    pic_static_library = ctx.file.pic_static_library or None
    static_library = ctx.file.static_library or None

    if ctx.files.pic_objects and not pic_static_library:
        lib_name = "lib" + ctx.label.name + ".pic.a"
        pic_static_library = ctx.actions.declare_file(lib_name)
        _create_archive_action(ctx, feature_configuration, cc_toolchain, pic_static_library, ctx.files.pic_objects)

    if ctx.files.objects and not static_library:
        lib_name = "lib" + ctx.label.name + ".a"
        static_library = ctx.actions.declare_file(lib_name)
        _create_archive_action(ctx, feature_configuration, cc_toolchain, static_library, ctx.files.objects)

    not_none_artifact_to_link = False

    # Check if there is something to link, if not skip that part.
    if static_library != None or pic_static_library != None or ctx.file.interface_library != None or ctx.file.shared_library != None:
        not_none_artifact_to_link = True

    linking_context = None
    if not_none_artifact_to_link:
        library_to_link = cc_common.create_library_to_link(
            actions = ctx.actions,
            feature_configuration = feature_configuration,
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo],
            static_library = static_library,
            pic_static_library = pic_static_library,
            interface_library = ctx.file.interface_library,
            dynamic_library = ctx.file.shared_library,
            pic_objects = ctx.files.pic_objects,
            objects = ctx.files.objects,
            alwayslink = ctx.attr.alwayslink,
        )

        linker_input = cc_common.create_linker_input(
            libraries = depset([library_to_link]),
            user_link_flags = depset(ctx.attr.linkopts),
            owner = ctx.label,
        )

        linking_context = cc_common.create_linking_context(
            linker_inputs = depset([linker_input]),
        )

    (compilation_context, compilation_outputs) = cc_common.compile(
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        public_hdrs = ctx.files.hdrs,
        includes = ctx.attr.includes,
        name = ctx.label.name,
        grep_includes = ctx.attr._grep_includes.files_to_run.executable,
    )

    this_cc_info = CcInfo(compilation_context = compilation_context, linking_context = linking_context)
    cc_infos = [this_cc_info]

    for dep in ctx.attr.deps:
        cc_infos.append(dep[CcInfo])
    merged_cc_info = cc_common.merge_cc_infos(direct_cc_infos = [this_cc_info], cc_infos = cc_infos)

    return [merged_cc_info]

cc_import = rule(
    implementation = _cc_import_impl,
    attrs = {
        "hdrs": attr.label_list(
            allow_files = True,
            flags = ["ORDER_INDEPENDENT", "DIRECT_COMPILE_TIME_INPUT"],
        ),
        "static_library": attr.label(allow_single_file = [".a", ".lib"]),
        "pic_static_library": attr.label(allow_single_file = [".pic.a", ".pic.lib"]),
        "shared_library": attr.label(allow_single_file = True),
        "interface_library": attr.label(
            allow_single_file = [".ifso", ".tbd", ".lib", ".so", ".dylib"],
        ),
        "pic_objects": attr.label_list(
            allow_files = [".o", ".pic.o"],
        ),
        "objects": attr.label_list(
            allow_files = [".o", ".nopic.o"],
        ),
        "system_provided": attr.bool(default = False),
        "alwayslink": attr.bool(default = False),
        "linkopts": attr.string_list(),
        "includes": attr.string_list(),
        "deps": attr.label_list(),
        "data": attr.label_list(
            allow_files = True,
            flags = ["SKIP_CONSTRAINTS_OVERRIDE"],
        ),
        "_grep_includes": attr.label(
            allow_files = True,
            executable = True,
            cfg = "exec",
            default = Label("@" + semantics.get_repo() + "//tools/cpp:grep-includes"),
        ),
        "_cc_toolchain": attr.label(default = "@" + semantics.get_repo() + "//tools/cpp:current_cc_toolchain"),
    },
    toolchains = ["@" + semantics.get_repo() + "//tools/cpp:toolchain_type"],  # copybara-use-repo-external-label
    fragments = ["cpp"],
    incompatible_use_toolchain_transition = True,
)
