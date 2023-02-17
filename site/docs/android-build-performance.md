---
layout: documentation
title: Android Build Performance
---

<div style="background-color: #EFCBCB; color: #AE2B2B;  border: 1px solid #AE2B2B; border-radius: 5px; border-left: 10px solid #AE2B2B; padding: 0.5em;">
<b>IMPORTANT:</b> The Bazel docs have moved! Please update your bookmark to <a href="https://bazel.build/docs/android-build-performance" style="color: #0000EE;">https://bazel.build/docs/android-build-performance</a>
<p/>
You can <a href="https://blog.bazel.build/2022/02/17/Launching-new-Bazel-site.html" style="color: #0000EE;">read about</a> the migration, and let us <a href="https://forms.gle/onkAkr2ZwBmcbWXj7" style="color: #0000EE;">know what you think</a>.
</div>


# Android Build Performance

This page contains information on optimizing build performance for Android
apps specifically. For general build performance optimization with Bazel, see
[Optimizing Performance](skylark/performance.html).

## Recommended flags

The flags are in the
[`bazelrc` configuration syntax](guide.html#bazelrc-syntax-and-semantics), so
they can be pasted directly into a `bazelrc` file and invoked with
`--config=<configuration_name>` on the command line.

**Profiling performance**

Bazel writes a JSON trace profile by default to a file called
`command.profile.gz` in Bazel's output base.
See the [JSON Profile documentation](skylark/performance.html#json-profile) for
how to read and interact with the profile.

**Persistent workers for Android build actions**.

A subset of Android build actions has support for
[persistent workers](https://blog.bazel.build/2015/12/10/java-workers.html).

These actions' mnemonics are:

*   DexBuilder
*   Javac
*   Desugar
*   AaptPackage
*   AndroidResourceParser
*   AndroidResourceValidator
*   AndroidResourceCompiler
*   RClassGenerator
*   AndroidResourceLink
*   AndroidAapt2
*   AndroidAssetMerger
*   AndroidResourceMerger
*   AndroidCompiledResourceMerger

Enabling workers can result in better build performance by saving on JVM
startup costs from invoking each of these tools, but at the cost of increased
memory usage on the system by persisting them.

To enable workers for these actions, apply these flags with
`--config=android_workers` on the command line:

```
build:android_workers --strategy=DexBuilder=worker
build:android_workers --strategy=Javac=worker
build:android_workers --strategy=Desugar=worker

# A wrapper flag for these resource processing actions:
# - AndroidResourceParser
# - AndroidResourceValidator
# - AndroidResourceCompiler
# - RClassGenerator
# - AndroidResourceLink
# - AndroidAapt2
# - AndroidAssetMerger
# - AndroidResourceMerger
# - AndroidCompiledResourceMerger
build:android_workers --persistent_android_resource_processor
```

The default number of persistent workers created per action is `4`. We have
[measured improved build performance](https://github.com/bazelbuild/bazel/issues/8586#issuecomment-500070549)
by capping the number of instances for each action to `1` or `2`, although this
may vary depending on the system Bazel is running on, and the project being
built.

To cap the number of instances for an action, apply these flags:

```
build:android_workers --worker_max_instances=DexBuilder=2
build:android_workers --worker_max_instances=Javac=2
build:android_workers --worker_max_instances=Desugar=2
build:android_workers --worker_max_instances=AaptPackage=2
# .. and so on for each action you're interested in.
```

**Using AAPT2**

[`aapt2`](https://developer.android.com/studio/command-line/aapt2) has improved
performance over `aapt` and also creates smaller APKs. To use `aapt2`, use the
`--android_aapt=aapt2` flag or set `aapt2` on the `aapt_version` on
`android_binary` and `android_local_test`.

**SSD optimizations**

The `--experimental_multi_threaded_digest` flag is useful for optimizing digest
computation on SSDs.
