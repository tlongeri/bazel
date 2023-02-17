Project: /_project.yaml
Book: /_book.yaml

# Backward Compatibility

This page provides information on how to handle backward compatibility,
including migrating from one release to another and how to communicate
incompatible changes.

Bazel is evolving. Minor versions released as part of an
[LTS major version](/release/versioning#lts-releases) are fully backward-compatible.
Changes between major LTS releases may contain incompatible changes that require
some migration effort. For more information on how the Bazel release cadence
works, see
[Announcing Bazel Long Term Support (LTS) releases](https://blog.bazel.build/2020/11/10/long-term-support-release.html).

## Summary {:#summary}

1. It is recommended to use `--incompatible_*` flags for breaking changes.
1. For every `--incompatible_*` flag, a GitHub issue explains
   the change in behavior and aims to provide a migration recipe.
1. APIs and behavior guarded by an `--experimental_*` flag can change at any time.
1. Never run production builds with `--experimental_*`  or `--incompatible_*` flags.

## How to follow this policy {:#policy}

* [For Bazel users - how to update Bazel](/versions/updating-bazel)
* [For contributors - best practices for incompatible changes](/contribute/breaking-changes)
* [For release managers - how to update issue labels and release](https://github.com/bazelbuild/continuous-integration/tree/master/docs/release-playbook.%6D%64){: .external}

## What is stable functionality? {:#stable-functionality}

In general, APIs or behaviors without `--experimental_...` flags are considered
stable, supported features in Bazel.

This includes:

* Starlark language and APIs
* Rules bundled with Bazel
* Bazel APIs such as Remote Execution APIs or Build Event Protocol
* Flags and their semantics

## Incompatible changes and migration recipes {:#incompatible-changes}

For every incompatible change in a new release, the Bazel team aims to provide a
_migration recipe_ that helps you update your code
(`BUILD` and `.bzl` files, as well as any Bazel usage in scripts,
usage of Bazel API, and so on).

Incompatible changes should have an associated `--incompatible_*` flag and a
corresponding GitHub issue.

## Communicating incompatible changes {:#communicating-incompatible-changes}

The primary source of information about incompatible changes are GitHub issues
marked with an ["incompatible-change" label](https://github.com/bazelbuild/bazel/issues?q=label%3Aincompatible-change){: .external}.

For every incompatible change, the issue specifies the following:
* Name of the flag controlling the incompatible change
* Description of the changed functionality
* Migration recipe

The incompatible change issue is closed when the incompatible flag is flipped at
HEAD. All incompatible changes that are expected to happen in release X.Y
are marked with a label "breaking-change-X.Y"."
