Project: /_project.yaml
Book: /_book.yaml
# Bazel roadmap
## Overview
The Bazel project constantly evolves in response to your needs — developing features and providing support while maintaining, refactoring, and improving the performance of the core product.

With these changes, we’re looking to keep our open-source community informed and included. This roadmap describes current initiatives and predictions for the future of Bazel development, giving you visibility into current priorities and ongoing projects.

This roadmap snapshots targets, and should not be taken as guarantees. Priorities are subject to change in response to developer and customer feedback, or new market opportunities.

To be notified of new features — including updates to this roadmap — join the [Google Group](https://groups.google.com/g/bazel-discuss) community.

## Q4 — Bazel 6.0 Release

Q4 brings Bazel 6.0 — the new [long term support (LTS)](https://bazel.build/release/versioning) version. Bazel 6.0 plans to include new powerful and community-requested features for managing dependencies, developing with Android, and more.

### Bzlmod: external dependency management system

[Bzlmod](https://bazel.build/docs/bzlmod) automatically resolves transitive dependencies, allowing projects to scale while staying fast and resource-efficient. Introduced experimentally in Bazel 5.0, Bzlmod will be generally available and  provide a solution for the [diamond dependency problem](https://docs.google.com/document/d/1moQfNcEIttsk6vYanNKIy3ZuK53hQUFq1b1r0rmsYVg/edit#heading=h.lgyp7ubwxmjc).

*   Bzlmod goes from ‘experimental’ to ‘generally available’
*   Includes support for `rules\_jvm\_external`, allowing users to download Maven dependencies for Java projects
*   [Bzlmod Migration Guide](https://docs.google.com/document/d/1JtXIVnXyFZ4bmbiBCr5gsTH4-opZAFf5DMMb-54kES0/edit?usp=gmail) provides tools, scripts, and documentation to teams looking to adopt Bzlmod
*   The [Bazel central repository](https://github.com/bazelbuild/bazel-central-registry) hosts core Bazel `BUILD` rules (`rules\_jvm\_external`, `rules\_go`, `rules\_python`, `rules\_nodejs`) and key dependencies required for Bzlmod

For more on this development, watch the [Bzlmod community update](https://www.youtube.com/watch?v=MuW5XNcFukE) or read the [original design doc](https://docs.google.com/document/d/1moQfNcEIttsk6vYanNKIy3ZuK53hQUFq1b1r0rmsYVg/edit#heading=h.lgyp7ubwxmjc).

### Android app build with Bazel

Bazel 6.0 will include improved tooling and merged-in community feature contributions. Anticipating further adoption and a growing codebase, the Bazel team will prioritize integration of Android build tools with Bazel Android rules.

*   Updates D8 to v. 3.3.28 and sets it as the [default dexer](https://github.com/bazelbuild/bazel/issues/10240).
*   Merges to main community feature contributions added in 5.X including support for:
    *   Persistent workers with D8
    *   Desugaring using D8
    *   Merging "uses-permissions" tags in Android manifests
    *   Multiplex workers in Android resource processing

### Optional toolchains

Our Developer Satisfaction survey showed that rule authors want support for further toolchain development. Bazel 6.0 will allow authors to write rules using an [optional, high performance toolchain](https://bazel.build/docs/toolchains#optional-toolchains) when available with a fallback implementation for other platforms.

### Bazel-JetBrains\* IntelliJ IDEA support

JetBrains has partnered with Bazel to co-maintain the [Bazel IntelliJ IDEA plugin](https://plugins.jetbrains.com/plugin/8609-bazel), supporting the goal of increasing community stewardship and opening up capacity for feature requests and development.

*   IntelliJ plugin v. 2022.2 provides support for the latest JetBrains plugin release
*   Increases compatibility with remote development
*   Furthers community-driven development for in-flight features such as Scala support

For more on this development, read the Bazel-JetBrains [blog announcement](https://blog.bazel.build/2022/07/11/Bazel-IntelliJ-Update.html).

## Future development

Looking ahead, the Bazel team has begun development or anticipates prioritizing the following features in 2023 and beyond.

### Improving Bazel's Android build rules 

Continue to invest in the Android app development experience, focusing on the workflow through build, test, and deployment.

*   Migration to and support for R8
*   Updates to the Android rules, including translation to the Starlark language
*   Support for App Bundle
*   Support for recent NDK versions
*   Test code coverage

### OSS license compliance tools

Developers requested a robust license compliance checker to ensure the availability and security of included packages. This project provides a set of rules and tools to help identify and mitigate compliance and license risks associated with a given software component. Target features include:

*   The ability to audit the packages used by a given target
*   The ability to build organization specific license compliance checks.

See the in-progress [rules\_license implementation](https://github.com/bazelbuild/rules_license) on Github.

### Bzlmod: external dependency management system

At launch, Bzlmod improves the scalability and reliability of transitive dependencies. Over the next three years, Bzlmod aims to replace `WORKSPACE` as the default Bazel workspace dependency management subsystem. Targeted features include:

*   Support for hermetic builds
*   Vendor/offline mode pinning versioned references rules to a local copy
*   Bazel Central Registry includes regular community contribution and adoption of key Bazel rules & projects
*   Bzlmod becomes the default tool for building Bazel projects

### Signed builds

Bazel will provide trusted binaries for Windows and Mac signed with Google keys. This feature enables multi-platform developers/dev-ops to identify the source of Bazel binaries and protect their systems from malicious, unverified binaries.

### Standardized Platforms API

The new Platforms API will standardize the architecture configuration for multi-language, multi-platform builds. With this feature, developers can reduce costly development-time errors and complexity in their large builds.

### Build analysis metrics

Bazel telemetry will provide analysis-phase time metrics, letting developers optimize their own build performance.

### Remote execution with “Builds without the Bytes”

[Builds without the Bytes](https://github.com/bazelbuild/bazel/issues/6862) will optimize performance by only allowing Bazel to download needed artifacts, preventing builds from bottlenecking on network bandwidth. Features added for remote builds include:

*   Use asynchronous download to let local and remote actions kick off as soon as they’ve downloaded their dependent outputs
*   Add Symlinks support
*   Retrieve intermediate outputs from remote actions once a build completes

_\*Copyright © 2022 JetBrains s.r.o. JetBrains and IntelliJ are registered trademarks of JetBrains s.r.o._
