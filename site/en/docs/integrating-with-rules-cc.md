Project: /_project.yaml
Book: /_book.yaml

# Integrating with C++ Rules

This page describes how to integrate with C++ rules on various levels.

## Accessing the C++ toolchain {:#access-c-toolchain}

Because of
[ongoing migration of C++ rules](https://github.com/bazelbuild/bazel/issues/6516){: .external}
to [platforms](/docs/platforms) and
[toolchains](/docs/toolchains), you
should use the helper function available at
[@bazel_tools//tools/cpp:toolchain_utils.bzl](https://source.bazel.build/bazel/+/main:tools/cpp/toolchain_utils.bzl;l=23),
which works both when toolchains are disabled and enabled. To depend on a C++
toolchain in your rule, add a
[`Label`](/rules/lib/attr#label)
attribute named `_cc_toolchain` and point it
to `@bazel_tools//tools/cpp:current_cc_toolchain` (an instance of
`cc_toolchain_alias` rule, that points to the currently selected C++ toolchain).
Then, in the rule implementation, use
[`find_cpp_toolchain(ctx)`](https://source.bazel.build/bazel/+/main:tools/cpp/toolchain_utils.bzl;l=23)
to get the
[`CcToolchainInfo`](/rules/lib/CcToolchainInfo).
A complete working example can be found
[in the rules_cc examples](https://github.com/bazelbuild/rules_cc/blob/main/examples/write_cc_toolchain_cpu/write_cc_toolchain_cpu.bzl){: .external}.

## Generating command lines and environment variables using the C++ toolchain {:#generate-command-lines-toolchain}

Typically, you would integrate with the C++ toolchain to have the same
command line flags as C++ rules do, but without using C++ actions directly.
This is because when writing our own actions, they must behave
consistently with the C++ toolchain - for example, passing C++ command line
flags to a tool that invokes the C++ compiler behind the scenes.

C++ rules use a special way of constructing command lines based on [feature
configuration](/docs/cc-toolchain-config-reference). To construct a command line,
you need the following:

* `features` and `action_configs` - these come from the `CcToolchainConfigInfo`
  and encapsulated in `CcToolchainInfo`
* `FeatureConfiguration` - returned by [cc_common.configure_features](/rules/lib/cc_common#configure_features)
* cc toolchain config variables - returned by
  [cc_common.create_compile_variables](/rules/lib/cc_common#create_compile_variables)
  or
  [cc_common.create_link_variables](/rules/lib/cc_common#create_link_variables).

There still are tool-specific getters, such as
[compiler_executable](/rules/lib/CcToolchainInfo#compiler_executable).
Prefer `get_tool_for_action` over these, as tool-specific getters will
eventually be removed.

A complete working example can be found
[in the rules_cc examples](https://github.com/bazelbuild/rules_cc/blob/main/examples/my_c_compile/my_c_compile.bzl){: .external}.

## Implementing Starlark rules that depend on C++ rules and/or that C++ rules can depend on {:#implement-starlark-rules}

Most C++ rules provide
[`CcInfo`](/rules/lib/CcInfo),
a provider containing [`CompilationContext`](/rules/lib/CompilationContext)
and
[`LinkingContext`](/rules/lib/LinkingContext).
Through these it is possible to access information such as all transitive headers
or libraries to link. From `CcInfo` and from the `CcToolchainInfo` custom
Starlark rules should be able to get all the information they need.

If a custom Starlark rule provides `CcInfo`, it's a signal to the C++ rules that
they can also depend on it. Be careful, however - if you only need to propagate
`CcInfo` through the graph to the binary rule that then makes use of it, wrap
`CcInfo` in a different provider. For example, if `java_library` rule wanted
to propagate native dependencies up to the `java_binary`, it shouldn't provide
`CcInfo` directly (`cc_binary` depending on `java_library` doesn't make sense),
it should wrap it in, for example, `JavaCcInfo`.

A complete working example can be found
[in the rules_cc examples](https://github.com/bazelbuild/rules_cc/blob/main/examples/my_c_archive/my_c_archive.bzl){: .external}.


## Reusing logic and actions of C++ rules {:#reuse-logic-c-rules}

_Not stable yet; This section will be updated once the API stabilizes. Follow
[#4570](https://github.com/bazelbuild/bazel/issues/4570){: .external} for up-to-date
information._
