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

"""
Definition of proto_common module.
"""

load(":common/proto/proto_semantics.bzl", "semantics")

def _create_proto_compile_action(
        ctx,
        proto_info,
        proto_compiler,
        progress_message,
        outputs,
        additional_args = [],
        plugins = [],
        mnemonic = "GenProto",
        strict_imports = False,
        additional_inputs = depset(),
        resource_set = None):
    """Creates proto compile action for compiling *.proto files to language specific sources.

    It uses proto configuration fragment to access protoc_opts and strict_proto_deps flags.

    Args:
      ctx: The rule context, used to create the action and to obtain label.
      proto_info: The 'ProtoInfo' provider of the proto_library target this proto compiler invocation is for.
      proto_compiler: The proto compiler executable.
      progress_message: The progress message to set on the action.
      outputs: The output files generated by the proto compiler.
      additional_args: Additional arguments to add to the action.
        Accepts a list containing: a string, a pair of value and format string,
        or a pair of list of strings and a format string.
      plugins: Additional plugin executables used by proto compiler.
      mnemonic: The mnemonic to set on the action.
      strict_imports: Whether to check for strict imports.
      additional_inputs: Additional inputs to add to the action.
      resource_set: A callback function that is passed to the created action.
        See `ctx.actions.run`, `resource_set` parameter.
    """

    args = ctx.actions.args()
    args.use_param_file(param_file_arg = "@%s")
    args.set_param_file_format("multiline")

    args.add_all(proto_info.transitive_proto_path, map_each = _proto_path_flag)
    # Example: `--proto_path=--proto_path=bazel-bin/target/third_party/pkg/_virtual_imports/subpkg`

    for arg in additional_args:
        if type(arg) == type((None, None)):
            if type(arg[0]) == type([]):
                args.add_joined(arg[0], join_with = "", format_joined = arg[1])
            else:
                args.add(arg[0], format = arg[1])
        else:
            args.add(arg)

    args.add_all(ctx.fragments.proto.protoc_opts())

    # Include maps
    # For each import, include both the import as well as the import relativized against its
    # protoSourceRoot. This ensures that protos can reference either the full path or the short
    # path when including other protos.
    args.add_all(proto_info.transitive_proto_sources(), map_each = _Iimport_path_equals_fullpath)
    # Example: `-Ia.proto=bazel-bin/target/third_party/pkg/_virtual_imports/subpkg/a.proto`

    strict_deps_mode = ctx.fragments.proto.strict_proto_deps()
    strict_deps = strict_deps_mode != "OFF" and strict_deps_mode != "DEFAULT"
    if strict_deps:
        strict_importable_sources = proto_info.strict_importable_sources()
        if strict_importable_sources:
            args.add_joined("--direct_dependencies", strict_importable_sources, map_each = _get_import_path, join_with = ":")
            # Example: `--direct_dependencies a.proto:b.proto`

        else:
            # The proto compiler requires an empty list to turn on strict deps checking
            args.add("--direct_dependencies=")

        # Set `-direct_dependencies_violation_msg=`
        args.add(ctx.label, format = semantics.STRICT_DEPS_FLAG_TEMPLATE)

    if strict_imports:
        if not proto_info.public_import_sources():
            # This line is necessary to trigger the check.
            args.add("--allowed_public_imports=")
        else:
            args.add_joined("--allowed_public_imports", proto_info.public_import_sources(), map_each = _get_import_path, join_with = ":")

    args.add_all(proto_info.direct_sources)

    ctx.actions.run(
        mnemonic = mnemonic,
        progress_message = progress_message,
        executable = proto_compiler,
        arguments = [args],
        inputs = depset(transitive = [proto_info.transitive_sources, additional_inputs]),
        outputs = outputs,
        tools = plugins,
        use_default_shell_env = True,
        resource_set = resource_set,
    )

def _proto_path_flag(path):
    if path == ".":
        return None
    return "--proto_path=%s" % path

def _get_import_path(proto_source):
    return proto_source.import_path()

def _Iimport_path_equals_fullpath(proto_source):
    return "-I%s=%s" % (proto_source.import_path(), proto_source.source_file().path)

proto_common = struct(
    create_proto_compile_action = _create_proto_compile_action,
)
