---
layout: documentation
title: Debugging remote cache hits for local execution
---

<div style="background-color: #EFCBCB; color: #AE2B2B;  border: 1px solid #AE2B2B; border-radius: 5px; border-left: 10px solid #AE2B2B; padding: 0.5em;">
<b>IMPORTANT:</b> The Bazel docs have moved! Please update your bookmark to <a href="https://bazel.build/docs/remote-caching-debug" style="color: #0000EE;">https://bazel.build/docs/remote-caching-debug</a>
<p/>
You can <a href="https://blog.bazel.build/2022/02/17/Launching-new-Bazel-site.html" style="color: #0000EE;">read about</a> the migration, and let us <a href="https://forms.gle/onkAkr2ZwBmcbWXj7" style="color: #0000EE;">know what you think</a>.
</div>


# Debugging Remote Cache Hits for Local Execution

This page describes how to investigate cache misses in the context of local
execution.

This page assumes that you have a build and/or test that successfully builds
locally and is set up to utilize remote caching, and that you want to ensure
that the remote cache is being effectively utilized.

For tips on how to check your cache hit rate and how to compare the execution logs between two Bazel invocations, see [Debugging Remote Cache Hits for Remote Execution](/remote-execution-caching-debug.html). Everything presented in that guide also applies to remote caching with local execution. However, local execution presents some additional challenges.

## Checking your cache hit rate

Successful remote cache hits will show up in the status line, similarly to
[Cache Hits rate with Remote
Execution](/remote-execution-caching-debug.html#checking-your-cache-hit-rate).
In the standard output of your Bazel run, you will see something like the
following:

        INFO: 7 processes: 3 remote cache hit, 4 linux-sandbox.

This means that out of 7 attempted actions, 3 got a remote cache hit and 4
actions did not have cache hits and were executed locally using `linux-sandbox`
strategy. Local cache hits are not included in this summary. If you are getting
0 processes (or a number lower than expected), run `bazel clean` followed by
your build/test command.

## Troubleshooting cache hits

If you are not getting the cache hit rate you are expecting, do the following:

### Ensure successful communication with the remote endpoint

To ensure your build is successfully communicating with the remote cache, follow
the steps in this section.

1. Check your output for warnings

   With remote execution, a failure to talk to the remote endpoint would fail
   your build. On the other hand, a cacheable local build would not fail if it
   cannot cache. Check the output of your Bazel invocation for warnings, such
   as:

        WARNING: Error reading from the remote cache:

   or

        WARNING: Error writing to the remote cache:

   Such warnings will be followed by the error message detailing the connection
   problem that should help you debug: for example, mistyped endpoint name or
   incorrectly set credentials. Find and address any such errors. If the error
   message you see does not give you enough information, try adding
   `--verbose_failures`.

2. Follow the steps from [Troubleshooting cache hits for remote
   execution](/remote-execution-caching-debug.html#troubleshooting-cache-hits) to
   ensure that your cache-writing Bazel invocations are able to get cache hits
   on the same machine and across machines.

3. Ensure your cache-reading Bazel invocations can get cache hits.

   a. Since cache-reading Bazel invocations will have a different command-line set
      up, take additional care to ensure that they are properly set up to
      communicate with the remote cache. Ensure the `--remote_cache` flag is set
      and there are no warnings in the output.

   b. Ensure your cache-reading Bazel invocations build the same targets as the
      cache-writing Bazel invocations.

   c. Follow the same steps as to [ensure caching across
      machines](/remote-execution-caching-debug.html#ensure-caching-across-machines),
      to ensure caching from your cache-writing Bazel invocation to your
      cache-reading Bazel invocation.

