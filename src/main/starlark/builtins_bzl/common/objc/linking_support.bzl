# Copyright 2022 The Bazel Authors. All rights reserved.
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

"""apple_common.link_multi_arch_static_library Starlark implementation"""

load("@_builtins//:common/objc/compilation_support.bzl", "compilation_support")

CcInfo = _builtins.toplevel.CcInfo
cc_common = _builtins.toplevel.cc_common
apple_common = _builtins.toplevel.apple_common

def _link_multi_arch_static_library(ctx, split_target_triplets = None):
    """Links a (potentially multi-architecture) static library targeting Apple platforms.

    Rule context is a required parameter due to usage of the cc_common.configure_features API.

    Args:
        ctx: The Starlark rule context.
        split_target_triplets: Dict for split transition keys and target triplet struct (arch,
          platform, environment). These values come from Java (see AppleStarlarkCommon.java) and are
          in place due to no available Starlark API for these values.

          Defaults to None for `apple_static_library` rule usage.

    Returns:
        A Starlark struct containing the following attributes:
            - objc: The Objc provider containing transitive linking information.
            - output_groups: OutputGroupInfo provider from transitive CcInfo validation_artifacts.
            - outputs: List of structs containing the following attributes:
                - library: Artifact representing a linked static library.
                - architecture: Linked static library architecture (e.g. 'arm64', 'x86_64').
                - platform: Linked static library target Apple platform (e.g. 'ios', 'macos').
                - environment: Linked static library environment (e.g. 'device', 'simulator').
    """
    split_deps = ctx.split_attr.deps
    split_avoid_deps = ctx.split_attr.avoid_deps
    child_configs_and_toolchains = ctx.split_attr._child_configuration_dummy

    outputs = []
    sdk_dylib = []
    sdk_framework = []
    weak_sdk_framework = []

    for split_transition_key, child_toolchain in child_configs_and_toolchains.items():
        cc_toolchain = child_toolchain[cc_common.CcToolchainInfo]
        common_variables = compilation_support.build_common_variables(
            ctx = ctx,
            toolchain = cc_toolchain,
            use_pch = True,
            deps = split_deps[split_transition_key],
        )

        avoid_objc_providers = []
        avoid_cc_providers = []

        if len(split_avoid_deps.keys()):
            for dep in split_avoid_deps[split_transition_key]:
                if apple_common.Objc in dep:
                    avoid_objc_providers.append(dep[apple_common.Objc])
                if CcInfo in dep:
                    avoid_cc_providers.append(dep[CcInfo])

        objc_provider = common_variables.objc_provider.subtract_subtrees(
            avoid_objc_providers = avoid_objc_providers,
            avoid_cc_providers = avoid_cc_providers,
        )

        name = ctx.label.name + "-" + cc_toolchain.target_gnu_system_name + "-fl"

        linking_outputs = compilation_support.register_fully_link_action(
            common_variables,
            objc_provider,
            name,
        )

        output = {
            "library": linking_outputs.library_to_link.static_library,
        }

        if split_target_triplets != None:
            target_triplet = split_target_triplets.get(split_transition_key)
            output["platform"] = target_triplet.platform
            output["architecture"] = target_triplet.architecture
            output["environment"] = target_triplet.environment

        outputs.append(struct(**output))
        sdk_dylib.append(objc_provider.sdk_dylib)
        sdk_framework.append(objc_provider.sdk_framework)
        weak_sdk_framework.append(objc_provider.weak_sdk_framework)

    objc_provider = apple_common.new_objc_provider(
        sdk_dylib = depset(transitive = sdk_dylib),
        sdk_framework = depset(transitive = sdk_framework),
        weak_sdk_framework = depset(transitive = weak_sdk_framework),
    )

    header_tokens = []
    for _, deps in split_deps.items():
        for dep in deps:
            if CcInfo in dep:
                header_tokens.append(dep[CcInfo].compilation_context.validation_artifacts)

    output_groups = {"_validation": depset(transitive = header_tokens)}

    return struct(
        outputs = outputs,
        objc = objc_provider,
        output_groups = OutputGroupInfo(**output_groups),
    )

linking_support = struct(
    link_multi_arch_static_library = _link_multi_arch_static_library,
)
