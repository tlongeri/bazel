# Bazel + Java gRPC

This directory contains the Java gRPC libraries needed by Bazel, sourced from
<https://github.com/grpc/grpc-java>.

| Repo             | Current   |
| ---------------- | --------- |
| `grpc/grpc-java` | `v1.41.0` |

## Updating `third_party/grpc/grpc-java`

This requires 1 pull request which does:

- Update the Java plugin:
  1. Checkout tag `v${GRPC_VERSION_NUM}` from <https://github.com/grpc/grpc-java>
  2. `cp -R <grpc-java git tree>/compiler/src/java_plugin third_party/grpc-java/compiler/src`
- Update the required jars by [retrieving the version from Maven central](https://repo1.maven.org/maven2/io/grpc/)
