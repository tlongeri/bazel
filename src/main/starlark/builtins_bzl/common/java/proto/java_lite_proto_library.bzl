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

"""A Starlark implementation of the java_lite_proto_library rule.

  TODO(elenairina):
  * Return ProguardSpecProvider from the rule via JavaProvider.
  * Return proto_java from the aspect.
"""

load(":common/java/java_semantics.bzl", "semantics")

PROTO_TOOLCHAIN_ATTR = "_aspect_proto_toolchain_for_javalite"
JAVA_TOOLCHAIN_ATTR = "_java_toolchain"

java_common = _builtins.toplevel.java_common
java_proto_common = _builtins.toplevel.java_proto_common
JavaInfo = _builtins.toplevel.JavaInfo

_JavaProtoAspectInfo = provider("JavaProtoAspectInfo", fields = ["jars"])

def _rule_impl(ctx):
    # Merging the retrieved list of aspect providers from the dependencies.
    java_info = java_common.merge([dep[JavaInfo] for dep in ctx.attr.deps])

    if not ctx.attr.strict_deps:
        java_info = java_common.make_non_strict(java_info)

    # Collect the aspect output files.
    files_to_build = depset(transitive = [dep[_JavaProtoAspectInfo].jars for dep in ctx.attr.deps])

    return [
        DefaultInfo(
            files = files_to_build,  # files to build
            runfiles = ctx.runfiles(
                transitive_files = depset(transitive = [java_info.transitive_runtime_jars]),
            ),
        ),
        java_info,
        OutputGroupInfo(default = depset()),
    ]

def _aspect_impl(target, ctx):
    """Stub aspect that collect all the proto source jars.

    Args:
      target: The configured target.
      ctx: The rule context.

    Returns:
      A source_jars_provider containing all the propagated dependency source jars.
    """
    transitive_files_to_build = [dep[_JavaProtoAspectInfo].jars for dep in ctx.rule.attr.deps]
    files_to_build = []

    # Collect the dependencies' aspect providers.
    deps = [dep[JavaInfo] for dep in ctx.rule.attr.deps]

    if java_proto_common.has_proto_sources(target):
        source_jar = ctx.actions.declare_file(ctx.label.name + "-lite-src.jar")
        java_proto_common.create_java_lite_proto_compile_action(
            ctx,
            target,
            src_jar = source_jar,
            proto_toolchain_attr = PROTO_TOOLCHAIN_ATTR,
            flavour = "javalite",
        )

        output_jar = ctx.actions.declare_file("lib" + ctx.label.name + "-lite.jar")

        files_to_build.extend([source_jar, output_jar])

        # This returns a java provider.
        toolchain_deps = java_proto_common.toolchain_deps(
            ctx,
            proto_toolchain_attr = PROTO_TOOLCHAIN_ATTR,
        )

        java_info = java_common.compile(
            ctx,
            source_jars = [source_jar],
            output = output_jar,
            deps = deps + [toolchain_deps],
            strict_deps = "OFF",
            java_toolchain = ctx.attr._java_toolchain[java_common.JavaToolchainInfo],
        )
    else:
        # If there are no proto sources just pass along the compilation dependencies.
        java_info = java_common.merge(deps)

    return [
        java_info,
        _JavaProtoAspectInfo(jars = depset(files_to_build, transitive = transitive_files_to_build)),
    ]

java_lite_proto_aspect = aspect(
    implementation = _aspect_impl,
    attr_aspects = ["deps"],
    attrs = {
        PROTO_TOOLCHAIN_ATTR: attr.label(
            default = Label(semantics.JAVA_LITE_PROTO_TOOLCHAIN_LABEL),
        ),
        JAVA_TOOLCHAIN_ATTR: attr.label(
            default = Label(semantics.JAVA_TOOLCHAIN_LABEL),
        ),
    },
    fragments = ["proto", "java"],
)

java_lite_proto_library = rule(
    implementation = _rule_impl,
    attrs = {
        "deps": attr.label_list(aspects = [java_lite_proto_aspect]),
        "strict_deps": attr.bool(default = True),
    },
)
