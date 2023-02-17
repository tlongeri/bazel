Project: /_project.yaml
Book: /_book.yaml

# Client/server implementation


The Bazel system is implemented as a long-lived server process. This allows it
to perform many optimizations not possible with a batch-oriented implementation,
such as caching of BUILD files, dependency graphs, and other metadata from one
build to the next. This improves the speed of incremental builds, and allows
different commands, such as `build` and `query` to share the same cache of
loaded packages, making queries very fast.

When you run `bazel`, you're running the client. The client finds the server
based on the output base, which by default is determined by the path of the base
workspace directory and your userid, so if you build in multiple workspaces,
you'll have multiple output bases and thus multiple Bazel server processes.
Multiple users on the same workstation can build concurrently in the same
workspace because their output bases will differ (different userids). If the
client cannot find a running server instance, it starts a new one. The server
process will stop after a period of inactivity (3 hours, by default, which can
be modified using the startup option `--max_idle_secs`).

For the most part, the fact that there is a server running is invisible to the
user, but sometimes it helps to bear this in mind. For example, if you're
running scripts that perform a lot of automated builds in different directories,
it's important to ensure that you don't accumulate a lot of idle servers; you
can do this by explicitly shutting them down when you're finished with them, or
by specifying a short timeout period.

The name of a Bazel server process appears in the output of `ps x` or `ps -e f`
as <code>bazel(<i>dirname</i>)</code>, where _dirname_ is the basename of the
directory enclosing the root of your workspace directory. For example:

```posix-terminal
ps -e f
16143 ?        Sl     3:00 bazel(src-johndoe2) -server -Djava.library.path=...
```

This makes it easier to find out which server process belongs to a given
workspace. (Beware that with certain other options to `ps`, Bazel server
processes may be named just `java`.) Bazel servers can be stopped using the
[shutdown](/docs/user-manual#shutdown) command.

When running `bazel`, the client first checks that the server is the appropriate
version; if not, the server is stopped and a new one started. This ensures that
the use of a long-running server process doesn't interfere with proper
versioning.
