Project: /_project.yaml
Book: /_book.yaml

# Getting Started with Bazel Docker Container

This page provides details on the contents of the Bazel container, how to build
the [abseil-cpp](https://github.com/abseil/abseil-cpp){: .external} project using Bazel
inside the Bazel container, and how to build this project directly
from the host machine using the Bazel container with directory mounting.

## Build Abseil project from your host machine with directory mounting {:#build-abseil}

The instructions in this section allow you to build using the Bazel container
with the sources checked out in your host environment. A container is started up
for each build command you execute. Build results are cached in your host
environment so they can be reused across builds.

Clone the project to a directory in your host machine.

```posix-terminal
git clone https://github.com/abseil/abseil-cpp.git /src/workspace
```

Create a folder that will have cached results to be shared across builds.

```posix-terminal
mkdir -p /tmp/build_output/
```

Use the Bazel container to build the project and make the build
outputs available in the output folder in your host machine.

```posix-terminal
docker run \
  -e USER="$(id -u)" \
  -u="$(id -u)" \
  -v /src/workspace:/src/workspace \
  -v /tmp/build_output:/tmp/build_output \
  -w /src/workspace \
  l.gcr.io/google/bazel:latest \
  --output_user_root=/tmp/build_output \
  build //absl/...
```

Build the project with sanitizers by adding the `--config={{ "<var>" }}asan{{ "</var>" }}|{{ "<var>" }}tsan{{ "</var>" }}|{{ "<var>" }}msan{{ "</var>" }}` build
flag to select AddressSanitizer (asan), ThreadSanitizer (tsan) or
MemorySanitizer (msan) accordingly.

```posix-terminal
docker run \
  -e USER="$(id -u)" \
  -u="$(id -u)" \
  -v /src/workspace:/src/workspace \
  -v /tmp/build_output:/tmp/build_output \
  -w /src/workspace \
  l.gcr.io/google/bazel:latest \
  --output_user_root=/tmp/build_output \
  build --config={asan | tsan | msan} -- //absl/... -//absl/types:variant_test
```

## Build Abseil project from inside the container {:#build-abseil-inside-container}

The instructions in this section allow you to build using the Bazel container
with the sources inside the container. By starting a container at the beginning
of your developement workflow and doing changes in the worskpace within the
container, build results will be cached.

Start a shell in the Bazel container:

```posix-terminal
docker run --interactive --entrypoint=/bin/bash l.gcr.io/google/bazel:latest
```

Each container id is unique. In the instructions bellow, the container was 5a99103747c6.

Clone the project.

```posix-terminal
root@5a99103747c6:~# git clone https://github.com/abseil/abseil-cpp.git && cd abseil-cpp/
```

Do a regular build.

```posix-terminal
root@5a99103747c6:~/abseil-cpp# bazel build //absl/...
```

Build the project with sanitizers by adding the `--config={{ "<var>" }}asan{{ "</var>" }}|{{ "<var>" }}tsan{{ "</var>" }}|{{ "<var>" }}msan{{ "</var>" }}`
build flag to select AddressSanitizer (asan), ThreadSanitizer (tsan) or
MemorySanitizer (msan) accordingly.

```posix-terminal
root@5a99103747c6:~/abseil-cpp# bazel build --config=--config={asan | tsan | msan} -- //absl/... -//absl/types:variant_test
```

## Explore the Bazel container {:#explore-bazel-container}

If you haven't already, start an interactive shell inside the Bazel container.

```posix-terminal
docker run -it --entrypoint=/bin/bash l.gcr.io/google/bazel:latest
root@5a99103747c6:/#
```

Explore the container contents.

```posix-terminal
root@5a99103747c6:/# clang --version
clang version 8.0.0 (trunk 340178)
Target: x86_64-unknown-linux-gnu
Thread model: posix
InstalledDir: /usr/local/bin

root@5a99103747c6:/# java -version
openjdk version "1.8.0_181"
OpenJDK Runtime Environment (build 1.8.0_181-8u181-b13-0ubuntu0.16.04.1-b13)
OpenJDK 64-Bit Server VM (build 25.181-b13, mixed mode)

root@5a99103747c6:/# python -V
Python 2.7.12

root@5a99103747c6:/# python3 -V
Python 3.6.6

root@5a99103747c6:/# bazel version
Extracting Bazel installation...
Build label: 0.17.1
Build target: bazel-out/k8-opt/bin/src/main/java/com/google/devtools/build/lib/bazel/BazelServer_deploy.jar
Build time: Fri Sep 14 10:39:25 2018 (1536921565)
Build timestamp: 1536921565
Build timestamp as int: 1536921565
```
