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
Definition of java_library rule.
"""

load(":common/java/java_common.bzl", "JAVA_COMMON_DEP", "collect_resources", "construct_defaultinfo")
load(":common/rule_util.bzl", "create_rule")
load(":common/java/java_semantics.bzl", "semantics")
load(":common/java/proguard_validation.bzl", "VALIDATE_PROGUARD_SPECS")

JavaInfo = _builtins.toplevel.JavaInfo
JavaPluginInfo = _builtins.toplevel.JavaPluginInfo
CcInfo = _builtins.toplevel.CcInfo

def _java_library_rule_impl(ctx):
    if not ctx.attr.srcs and ctx.attr.deps:
        fail("deps not allowed without srcs; move to runtime_deps?")

    semantics.check_rule(ctx)
    semantics.check_dependency_rule_kinds(ctx, "java_library")

    extra_resources = semantics.preprocess(ctx)

    base_info = JAVA_COMMON_DEP.call(
        ctx,
        srcs = ctx.files.srcs,
        deps = ctx.attr.deps,
        runtime_deps = ctx.attr.runtime_deps,
        exports = ctx.attr.exports,
        exported_plugins = ctx.attr.exported_plugins,
        resources = collect_resources(ctx, extra_resources),
        plugins = ctx.attr.plugins,
        javacopts = ctx.attr.javacopts,
        neverlink = ctx.attr.neverlink,
    )

    proguard_specs_provider = VALIDATE_PROGUARD_SPECS.call(
        ctx,
        proguard_specs = ctx.files.proguard_specs,
        transitive_attrs = [ctx.attr.deps, ctx.attr.runtime_deps, ctx.attr.exports, ctx.attr.plugins, ctx.attr.exported_plugins],
    )
    base_info.output_groups["_hidden_top_level_INTERNAL_"] = proguard_specs_provider.specs
    base_info.extra_providers.append(proguard_specs_provider)

    java_info = semantics.postprocess(ctx, base_info)

    default_info = construct_defaultinfo(
        ctx,
        base_info.files_to_build,
        ctx.attr.neverlink,
        base_info.has_sources_or_resources,
        ctx.attr.exports,
        ctx.attr.runtime_deps,
    )

    return [
        default_info,
        java_info,
        base_info.instrumented_files_info,
        OutputGroupInfo(**base_info.output_groups),
    ] + base_info.extra_providers

java_library = create_rule(
    _java_library_rule_impl,
    attrs = dict(
        {
            "licenses": attr.license() if hasattr(attr, "license") else attr.string_list(),
        },
        **semantics.EXTRA_ATTRIBUTES
    ),
    deps = [JAVA_COMMON_DEP, VALIDATE_PROGUARD_SPECS] + semantics.EXTRA_DEPS,
    provides = [JavaInfo],
    outputs = {
        "classjar": "lib%{name}.jar",
        "sourcejar": "lib%{name}-src.jar",
    },
    compile_one_filetype = [".java"],
)
