// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.java;

import static com.google.common.collect.Iterables.getOnlyElement;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Optional;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.syntax.Location;

/** Information about the system APIs for a Java compilation. */
@Immutable
public final class BootClassPathInfo extends NativeInfo implements StarlarkValue {

  /** Provider singleton constant. */
  public static final Provider PROVIDER = new Provider();

  /** Provider class for {@link BootClassPathInfo} objects. */
  @StarlarkBuiltin(name = "Provider", documented = false, doc = "")
  public static class Provider extends BuiltinProvider<BootClassPathInfo> implements ProviderApi {
    private Provider() {
      super("BootClassPathInfo", BootClassPathInfo.class);
    }

    @StarlarkMethod(
        name = "BootClassPathInfo",
        doc = "The <code>BootClassPathInfo</code> constructor.",
        documented = false,
        parameters = {
          @Param(name = "bootclasspath", positional = false, named = true, defaultValue = "[]"),
          @Param(name = "auxiliary", positional = false, named = true, defaultValue = "[]"),
          @Param(
              name = "system",
              positional = false,
              named = true,
              allowedTypes = {
                @ParamType(type = FileApi.class),
                @ParamType(type = NoneType.class),
                @ParamType(type = Sequence.class),
              },
              defaultValue = "None",
              doc =
                  "The inputs to javac's --system flag, either a directory or a listing of files,"
                      + " which must contain at least 'release', 'lib/modules', and"
                      + " 'lib/jrt-fs.jar'"),
        },
        selfCall = true,
        useStarlarkThread = true)
    public BootClassPathInfo bootClassPathInfo(
        Sequence<?> bootClassPathList,
        Sequence<?> auxiliaryList,
        Object systemOrNone,
        StarlarkThread thread)
        throws EvalException {
      NestedSet<Artifact> systemInputs = getSystemInputs(systemOrNone);
      Optional<PathFragment> systemPath = getSystemPath(systemInputs);
      return new BootClassPathInfo(
          getBootClassPath(bootClassPathList),
          getAuxiliary(auxiliaryList),
          systemInputs,
          systemPath,
          thread.getCallerLocation());
    }

    private static NestedSet<Artifact> getBootClassPath(Sequence<?> bootClassPathList)
        throws EvalException {
      return NestedSetBuilder.wrap(
          Order.STABLE_ORDER, Sequence.cast(bootClassPathList, Artifact.class, "bootclasspath"));
    }

    private static NestedSet<Artifact> getAuxiliary(Sequence<?> auxiliaryList)
        throws EvalException {
      return NestedSetBuilder.wrap(
          Order.STABLE_ORDER, Sequence.cast(auxiliaryList, Artifact.class, "auxiliary"));
    }

    private static NestedSet<Artifact> getSystemInputs(Object systemOrNone) throws EvalException {
      if (systemOrNone == Starlark.NONE) {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
      }
      if (systemOrNone instanceof Artifact) {
        return NestedSetBuilder.create(Order.STABLE_ORDER, (Artifact) systemOrNone);
      }
      if (systemOrNone instanceof Sequence<?>) {
        return NestedSetBuilder.wrap(
            Order.STABLE_ORDER, Sequence.cast(systemOrNone, Artifact.class, "system"));
      }
      throw Starlark.errorf(
          "for system, got %s, want File, sequence, or None", Starlark.type(systemOrNone));
    }

    private static Optional<PathFragment> getSystemPath(NestedSet<Artifact> systemInputs)
        throws EvalException {
      ImmutableList<Artifact> inputs = systemInputs.toList();
      if (inputs.isEmpty()) {
        return Optional.empty();
      }
      if (inputs.size() == 1) {
        Artifact input = getOnlyElement(inputs);
        if (!input.isTreeArtifact()) {
          throw Starlark.errorf("for system, %s is not a directory", input.getExecPathString());
        }
        return Optional.of(input.getExecPath());
      }
      Optional<PathFragment> input =
          inputs.stream()
              .map(Artifact::getExecPath)
              .filter(p -> p.getBaseName().equals("release"))
              .map(PathFragment::getParentDirectory)
              .findAny();
      if (!input.isPresent()) {
        throw Starlark.errorf("for system, expected inputs to contain 'release'");
      }
      return input;
    }
  }

  private final NestedSet<Artifact> bootclasspath;
  private final NestedSet<Artifact> auxiliary;
  private final NestedSet<Artifact> systemInputs;
  private final Optional<PathFragment> systemPath;

  private BootClassPathInfo(
      NestedSet<Artifact> bootclasspath,
      NestedSet<Artifact> auxiliary,
      NestedSet<Artifact> systemInputs,
      Optional<PathFragment> systemPath,
      Location creationLocation) {
    super(creationLocation);
    this.bootclasspath = bootclasspath;
    this.auxiliary = auxiliary;
    this.systemInputs = systemInputs;
    this.systemPath = systemPath;
  }

  @Override
  public Provider getProvider() {
    return PROVIDER;
  }

  public static BootClassPathInfo create(NestedSet<Artifact> bootclasspath) {
    return new BootClassPathInfo(
        bootclasspath,
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        Optional.empty(),
        null);
  }

  public static BootClassPathInfo empty() {
    return new BootClassPathInfo(
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        Optional.empty(),
        null);
  }

  /** The jar files containing classes for system APIs, i.e. a Java <= 8 bootclasspath. */
  public NestedSet<Artifact> bootclasspath() {
    return bootclasspath;
  }

  /**
   * The jar files containing extra classes for system APIs that should not be put in the system
   * image to support split-package compilation scenarios.
   */
  public NestedSet<Artifact> auxiliary() {
    return auxiliary;
  }

  /** An argument to the javac >= 9 {@code --system} flag. */
  public Optional<PathFragment> systemPath() {
    return systemPath;
  }

  /** Contents of the directory that is passed to the javac >= 9 {@code --system} flag. */
  public NestedSet<Artifact> systemInputs() {
    return systemInputs;
  }

  public boolean isEmpty() {
    return bootclasspath.isEmpty();
  }
}
