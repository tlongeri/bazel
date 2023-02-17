// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.configuredtargets.AbstractConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkActionFactory;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.BazelModuleContext;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaCommonApi;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaToolchainStarlarkApiProviderApi;
import java.util.Objects;
import java.util.stream.Collectors;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/** A module that contains Starlark utilities for Java support. */
public class JavaStarlarkCommon
    implements JavaCommonApi<
        Artifact,
        JavaInfo,
        JavaToolchainProvider,
        ConstraintValueInfo,
        StarlarkRuleContext,
        StarlarkActionFactory> {

  private static final ImmutableSet<String> PRIVATE_STARLARKIFACTION_ALLOWLIST =
      ImmutableSet.of("bazel_internal/test");
  private final JavaSemantics javaSemantics;

  public JavaStarlarkCommon(JavaSemantics javaSemantics) {
    this.javaSemantics = javaSemantics;
  }

  @Override
  public Provider getJavaProvider() {
    return JavaInfo.PROVIDER;
  }

  @Override
  public JavaInfo createJavaCompileAction(
      StarlarkRuleContext starlarkRuleContext,
      Sequence<?> sourceJars, // <Artifact> expected
      Sequence<?> sourceFiles, // <Artifact> expected
      Artifact outputJar,
      Object outputSourceJar,
      Sequence<?> javacOpts, // <String> expected
      Sequence<?> deps, // <JavaInfo> expected
      Sequence<?> runtimeDeps, // <JavaInfo> expected
      Sequence<?> exports, // <JavaInfo> expected
      Sequence<?> plugins, // <JavaPluginInfo> expected
      Sequence<?> exportedPlugins, // <JavaPluginInfo> expected
      Sequence<?> nativeLibraries, // <CcInfo> expected.
      Sequence<?> annotationProcessorAdditionalInputs, // <Artifact> expected
      Sequence<?> annotationProcessorAdditionalOutputs, // <Artifact> expected
      String strictDepsMode,
      JavaToolchainProvider javaToolchain,
      Object hostJavabase,
      Sequence<?> sourcepathEntries, // <Artifact> expected
      Sequence<?> resources, // <Artifact> expected
      Sequence<?> classpathResources, // <Artifact> expected
      Boolean neverlink,
      Boolean enableAnnotationProcessing,
      Boolean enableCompileJarAction,
      StarlarkThread thread)
      throws EvalException, InterruptedException {

    boolean acceptJavaInfo =
        !starlarkRuleContext
            .getRuleContext()
            .getFragment(JavaConfiguration.class)
            .requireJavaPluginInfo();

    final ImmutableList<JavaPluginInfo> pluginsParam;
    if (acceptJavaInfo && !plugins.isEmpty() && plugins.get(0) instanceof JavaInfo) {
      // Handle deprecated case where plugins is given a list of JavaInfos
      pluginsParam =
          Sequence.cast(plugins, JavaInfo.class, "plugins").stream()
              .map(JavaInfo::getJavaPluginInfo)
              .filter(Objects::nonNull)
              .collect(toImmutableList());
    } else {
      pluginsParam = Sequence.cast(plugins, JavaPluginInfo.class, "plugins").getImmutableList();
    }

    final ImmutableList<JavaPluginInfo> exportedPluginsParam;
    if (acceptJavaInfo
        && !exportedPlugins.isEmpty()
        && exportedPlugins.get(0) instanceof JavaInfo) {
      // Handle deprecated case where exported_plugins is given a list of JavaInfos
      exportedPluginsParam =
          Sequence.cast(exportedPlugins, JavaInfo.class, "exported_plugins").stream()
              .map(JavaInfo::getJavaPluginInfo)
              .filter(Objects::nonNull)
              .collect(toImmutableList());
    } else {
      exportedPluginsParam =
          Sequence.cast(exportedPlugins, JavaPluginInfo.class, "exported_plugins")
              .getImmutableList();
    }
    // checks for private API access
    if (!enableCompileJarAction || !classpathResources.isEmpty()) {
      checkPrivateAccess(thread);
    }
    return JavaInfoBuildHelper.getInstance()
        .createJavaCompileAction(
            starlarkRuleContext,
            Sequence.cast(sourceJars, Artifact.class, "source_jars"),
            Sequence.cast(sourceFiles, Artifact.class, "source_files"),
            outputJar,
            outputSourceJar == Starlark.NONE ? null : (Artifact) outputSourceJar,
            Sequence.cast(javacOpts, String.class, "javac_opts"),
            Sequence.cast(deps, JavaInfo.class, "deps"),
            Sequence.cast(runtimeDeps, JavaInfo.class, "runtime_deps"),
            Sequence.cast(exports, JavaInfo.class, "exports"),
            pluginsParam,
            exportedPluginsParam,
            Sequence.cast(nativeLibraries, CcInfo.class, "native_libraries"),
            Sequence.cast(
                annotationProcessorAdditionalInputs,
                Artifact.class,
                "annotation_processor_additional_inputs"),
            Sequence.cast(
                annotationProcessorAdditionalOutputs,
                Artifact.class,
                "annotation_processor_additional_outputs"),
            strictDepsMode,
            javaToolchain,
            ImmutableList.copyOf(Sequence.cast(sourcepathEntries, Artifact.class, "sourcepath")),
            Sequence.cast(resources, Artifact.class, "resources"),
            Sequence.cast(classpathResources, Artifact.class, "classpath_resources"),
            neverlink,
            enableAnnotationProcessing,
            enableCompileJarAction,
            javaSemantics,
            thread);
  }

  @Override
  public Artifact runIjar(
      StarlarkActionFactory actions,
      Artifact jar,
      Object targetLabel,
      JavaToolchainProvider javaToolchain)
      throws EvalException {
    return JavaInfoBuildHelper.getInstance()
        .buildIjar(
            actions, jar, targetLabel != Starlark.NONE ? (Label) targetLabel : null, javaToolchain);
  }

  @Override
  public Artifact stampJar(
      StarlarkActionFactory actions,
      Artifact jar,
      Label targetLabel,
      JavaToolchainProvider javaToolchain)
      throws EvalException {
    return JavaInfoBuildHelper.getInstance().stampJar(actions, jar, targetLabel, javaToolchain);
  }

  @Override
  public Artifact packSources(
      StarlarkActionFactory actions,
      Object outputJar,
      Object outputSourceJar,
      Sequence<?> sourceFiles, // <Artifact> expected.
      Sequence<?> sourceJars, // <Artifact> expected.
      JavaToolchainProvider javaToolchain,
      Object hostJavabase)
      throws EvalException {
    return JavaInfoBuildHelper.getInstance()
        .packSourceFiles(
            actions,
            outputJar instanceof Artifact ? (Artifact) outputJar : null,
            outputSourceJar instanceof Artifact ? (Artifact) outputSourceJar : null,
            Sequence.cast(sourceFiles, Artifact.class, "sources"),
            Sequence.cast(sourceJars, Artifact.class, "source_jars"),
            javaToolchain);
  }

  @Override
  // TODO(b/78512644): migrate callers to passing explicit javacopts or using custom toolchains, and
  // delete
  public ImmutableList<String> getDefaultJavacOpts(JavaToolchainProvider javaToolchain)
      throws EvalException {
    // We don't have a rule context if the default_javac_opts.java_toolchain parameter is set
    return ((JavaToolchainProvider) javaToolchain).getJavacOptions(/* ruleContext= */ null);
  }

  @Override
  public JavaInfo mergeJavaProviders(
      Sequence<?> providers, /* <JavaInfo> expected. */ StarlarkThread thread)
      throws EvalException {
    return JavaInfo.merge(Sequence.cast(providers, JavaInfo.class, "providers"));
  }

  // TODO(b/65113771): Remove this method because it's incorrect.
  @Override
  public JavaInfo makeNonStrict(JavaInfo javaInfo) {
    return JavaInfo.Builder.copyOf(javaInfo)
        // Overwrites the old provider.
        .addProvider(
            JavaCompilationArgsProvider.class,
            JavaCompilationArgsProvider.makeNonStrict(
                javaInfo.getProvider(JavaCompilationArgsProvider.class)))
        .build();
  }

  @Override
  public ProviderApi getJavaPluginProvider() {
    return JavaPluginInfo.PROVIDER;
  }

  @Override
  public Provider getJavaToolchainProvider() {
    return JavaToolchainProvider.PROVIDER;
  }

  @Override
  public Provider getJavaRuntimeProvider() {
    return JavaRuntimeInfo.PROVIDER;
  }

  @Override
  public ProviderApi getMessageBundleInfo() {
    // No implementation in Bazel. This method not callable in Starlark except through
    // (discouraged) use of --experimental_google_legacy_api.
    return null;
  }

  @Override
  public JavaInfo addConstraints(JavaInfo javaInfo, Sequence<?> constraints) throws EvalException {
    // No implementation in Bazel. This method not callable in Starlark except through
    // (discouraged) use of --experimental_google_legacy_api.
    return null;
  }

  @Override
  public Sequence<String> getConstraints(JavaInfo javaInfo) {
    // No implementation in Bazel. This method not callable in Starlark except through
    // (discouraged) use of --experimental_google_legacy_api.
    return StarlarkList.empty();
  }

  @Override
  public JavaInfo setAnnotationProcessing(
      JavaInfo javaInfo,
      boolean enabled,
      Sequence<?> processorClassnames,
      Object processorClasspath,
      Object classJar,
      Object sourceJar)
      throws EvalException {
    // No implementation in Bazel. This method not callable in Starlark except through
    // (discouraged) use of --experimental_google_legacy_api.
    return null;
  }

  @Override
  public Label getJavaToolchainLabel(JavaToolchainStarlarkApiProviderApi toolchain)
      throws EvalException {
    // No implementation in Bazel. This method not callable in Starlark except through
    // (discouraged) use of --experimental_google_legacy_api.
    return null;
  }

  @Override
  public ProviderApi getBootClassPathInfo() {
    return BootClassPathInfo.PROVIDER;
  }

  @Override
  public String getTargetKind(Object target, StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    if (target instanceof AbstractConfiguredTarget) {
      return ((AbstractConfiguredTarget) target).getRuleClassString();
    }
    return "";
  }

  static void checkPrivateAccess(StarlarkThread thread) throws EvalException {
    Label label =
        ((BazelModuleContext) Module.ofInnermostEnclosingStarlarkFunction(thread).getClientData())
            .label();
    if (!PRIVATE_STARLARKIFACTION_ALLOWLIST.contains(label.getPackageName())
        && !label.getPackageIdentifier().getRepository().getName().equals("_builtins")) {
      throw Starlark.errorf("Rule in '%s' cannot use private API", label.getPackageName());
    }
  }

  @Override
  public JavaInfo toJavaBinaryInfo(JavaInfo javaInfo, StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    JavaRuleOutputJarsProvider ruleOutputs =
        JavaRuleOutputJarsProvider.builder()
            .addJavaOutput(
                javaInfo.getJavaOutputs().stream()
                    .map(
                        output ->
                            JavaOutput.create(
                                output.getClassJar(),
                                null,
                                null,
                                output.getGeneratedClassJar(),
                                output.getGeneratedSourceJar(),
                                output.getNativeHeadersJar(),
                                output.getManifestProto(),
                                output.getJdeps(),
                                output.getSourceJars()))
                    .collect(Collectors.toList()))
            .build();
    JavaInfo.Builder builder = JavaInfo.Builder.create();
    if (javaInfo.getProvider(JavaCompilationInfoProvider.class) != null) {
      builder.addProvider(JavaCompilationInfoProvider.class, javaInfo.getCompilationInfoProvider());
    } else if (javaInfo.getProvider(JavaCompilationArgsProvider.class) != null) {
      builder.addProvider(
          JavaCompilationArgsProvider.class,
          javaInfo.getProvider(JavaCompilationArgsProvider.class));
    }
    if (javaInfo.getProvider(JavaGenJarsProvider.class) != null) {
      builder.addProvider(JavaGenJarsProvider.class, javaInfo.getGenJarsProvider());
    }
    return builder
        .addProvider(JavaCcInfoProvider.class, javaInfo.getProvider(JavaCcInfoProvider.class))
        .addProvider(
            JavaSourceJarsProvider.class, javaInfo.getProvider(JavaSourceJarsProvider.class))
        .addProvider(JavaRuleOutputJarsProvider.class, ruleOutputs)
        .addTransitiveOnlyRuntimeJars(javaInfo.getTransitiveOnlyRuntimeJars())
        .build();
  }

  @Override
  public Sequence<Artifact> getBuildInfo(StarlarkRuleContext ruleContext, StarlarkThread thread)
      throws EvalException, InterruptedException {
    checkPrivateAccess(thread);
    return StarlarkList.immutableCopyOf(
        ruleContext.getRuleContext().getBuildInfo(JavaBuildInfoFactory.KEY));
  }
}
