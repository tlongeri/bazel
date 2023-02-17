// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.LocationExpander;
import com.google.devtools.build.lib.analysis.TemplateVariableInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.shell.ShellUtils;
import com.google.devtools.build.lib.shell.ShellUtils.TokenizationException;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;

/** Utility methods for Objc rules in Starlark Builtins */
@StarlarkBuiltin(name = "objc_internal", category = DocCategory.BUILTIN, documented = false)
public class ObjcStarlarkInternal implements StarlarkValue {

  public static final String NAME = "objc_internal";

  /**
   * Converts a possibly NoneType object to the real object if it is not NoneType or returns the
   * default value if it is.
   */
  @SuppressWarnings("unchecked")
  public static <T> T convertFromNoneable(Object obj, @Nullable T defaultValue) {
    if (Starlark.UNBOUND == obj || Starlark.isNullOrNone(obj)) {
      return defaultValue;
    }
    return (T) obj;
  }

  @StarlarkMethod(
      name = "create_compilation_attributes",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
      })
  public CompilationAttributes createCompilationAttributes(StarlarkRuleContext starlarkRuleContext)
      throws EvalException {
    CompilationAttributes.Builder builder = new CompilationAttributes.Builder();

    CompilationAttributes.Builder.addHeadersFromRuleContext(
        builder, starlarkRuleContext.getRuleContext());
    CompilationAttributes.Builder.addIncludesFromRuleContext(
        builder, starlarkRuleContext.getRuleContext());
    CompilationAttributes.Builder.addSdkAttributesFromRuleContext(
        builder, starlarkRuleContext.getRuleContext());
    if (starlarkRuleContext.getRuleContext().attributes().has("copts")) {
      Sequence<String> copts =
          expandToolchainAndRuleContextVariables(
              starlarkRuleContext,
              StarlarkList.immutableCopyOf(
                  starlarkRuleContext
                      .getRuleContext()
                      .attributes()
                      .get("copts", Type.STRING_LIST)));
      CompilationAttributes.Builder.addCompileOptionsFromRuleContext(
          builder, starlarkRuleContext.getRuleContext(), copts);
    }
    CompilationAttributes.Builder.addModuleOptionsFromRuleContext(
        builder, starlarkRuleContext.getRuleContext());

    return builder.build();
  }

  @StarlarkMethod(
      name = "expand_toolchain_and_ctx_variables",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
        @Param(name = "flags", positional = false, defaultValue = "[]", named = true),
      })
  public Sequence<String> expandToolchainAndRuleContextVariables(
      StarlarkRuleContext starlarkRuleContext, Sequence<?> flags) throws EvalException {
    if (flags.isEmpty()) {
      return Sequence.cast(flags, String.class, "flags");
    }
    ImmutableMap<String, String> toolchainMap =
        starlarkRuleContext
            .getRuleContext()
            .getPrerequisite("$cc_toolchain", TemplateVariableInfo.PROVIDER)
            .getVariables();
    ImmutableMap<String, String> starlarkRuleContextMap =
        ImmutableMap.<String, String>builder().putAll(starlarkRuleContext.var()).build();
    List<String> expandedFlags = new ArrayList<>();
    for (String flag : Sequence.cast(flags, String.class, "flags")) {

      String expandedFlag =
          LocationExpander.withExecPaths(
                  starlarkRuleContext.getRuleContext(),
                  StarlarkRuleContext.makeLabelMap(
                      ImmutableSet.copyOf(
                          Iterables.concat(
                              starlarkRuleContext.getRuleContext().getPrerequisites("srcs"),
                              starlarkRuleContext.getRuleContext().getPrerequisites("non_arc_srcs"),
                              starlarkRuleContext.getRuleContext().getPrerequisites("hdrs"),
                              starlarkRuleContext.getRuleContext().getPrerequisites("data")))))
              .expand(flag);
      expandedFlag = expandFlag(expandedFlag, toolchainMap, starlarkRuleContextMap);
      try {
        ShellUtils.tokenize(expandedFlags, expandedFlag);
      } catch (TokenizationException e) {
        throw new EvalException(e);
      }
    }
    return StarlarkList.immutableCopyOf(expandedFlags);
  }

  private String expandFlag(
      String flag,
      ImmutableMap<String, String> toolchainMap,
      ImmutableMap<String, String> contextMap) {
    if (!flag.contains("$(")) {
      return flag;
    }
    int beginning = flag.indexOf("$(");
    int end = flag.indexOf(')', beginning);
    String variable = flag.substring(beginning + 2, end);
    String expandedVariable;
    if (toolchainMap.containsKey(variable)) {
      expandedVariable = toolchainMap.get(variable);
    } else {
      expandedVariable = contextMap.get(variable);
    }
    String expandedFlag = flag.replace("$(" + variable + ")", expandedVariable);
    return expandFlag(expandedFlag, toolchainMap, contextMap);
  }

  @StarlarkMethod(
      name = "create_intermediate_artifacts",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
      })
  public IntermediateArtifacts createIntermediateArtifacts(
      StarlarkRuleContext starlarkRuleContext) {
    return ObjcRuleClasses.intermediateArtifacts(starlarkRuleContext.getRuleContext());
  }

  @StarlarkMethod(
      name = "create_compilation_artifacts",
      documented = false,
      parameters = {
        @Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = StarlarkRuleContext.class),
              @ParamType(type = NoneType.class)
            }),
      })
  public CompilationArtifacts createCompilationArtifacts(Object starlarkRuleContextObject) {
    StarlarkRuleContext starlarkRuleContext =
        convertFromNoneable(starlarkRuleContextObject, /* defaultValue= */ null);
    if (starlarkRuleContext != null) {
      return CompilationSupport.compilationArtifacts(starlarkRuleContext.getRuleContext());
    } else {
      return new CompilationArtifacts.Builder().build();
    }
  }

  @StarlarkMethod(
      name = "j2objc_providers_from_deps",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
      })
  public Sequence<NativeInfo> createj2objcProvidersFromDeps(StarlarkRuleContext starlarkRuleContext)
      throws EvalException {
    J2ObjcMappingFileProvider j2ObjcMappingFileProvider =
        J2ObjcMappingFileProvider.union(
            starlarkRuleContext
                .getRuleContext()
                .getPrerequisites("deps", J2ObjcMappingFileProvider.PROVIDER));
    J2ObjcEntryClassProvider j2ObjcEntryClassProvider =
        new J2ObjcEntryClassProvider.Builder()
            .addTransitive(
                starlarkRuleContext
                    .getRuleContext()
                    .getPrerequisites("deps", J2ObjcEntryClassProvider.PROVIDER))
            .build();

    return StarlarkList.immutableCopyOf(
        ImmutableList.of(j2ObjcEntryClassProvider, j2ObjcMappingFileProvider));
  }

  @StarlarkMethod(
      name = "instrumented_files_info",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
        @Param(name = "object_files", positional = false, defaultValue = "[]", named = true),
      })
  public InstrumentedFilesInfo createInstrumentedFilesInfo(
      StarlarkRuleContext starlarkRuleContext, Sequence<?> objectFiles) throws EvalException {
    return CompilationSupport.getInstrumentedFilesProvider(
        starlarkRuleContext.getRuleContext(),
        Sequence.cast(objectFiles, Artifact.class, "object_files").getImmutableList());
  }

  @StarlarkMethod(
      name = "create_compilation_context",
      documented = false,
      parameters = {
        @Param(name = "public_hdrs", positional = false, defaultValue = "[]", named = true),
        @Param(name = "public_textual_hdrs", positional = false, defaultValue = "[]", named = true),
        @Param(name = "private_hdrs", positional = false, defaultValue = "[]", named = true),
        @Param(name = "providers", positional = false, defaultValue = "[]", named = true),
        @Param(
            name = "direct_cc_compilation_contexts",
            positional = false,
            defaultValue = "[]",
            named = true),
        @Param(
            name = "cc_compilation_contexts",
            positional = false,
            defaultValue = "[]",
            named = true),
        @Param(name = "defines", positional = false, defaultValue = "[]", named = true),
        @Param(name = "includes", positional = false, defaultValue = "[]", named = true),
      })
  public ObjcCompilationContext createCompilationContext(
      Sequence<?> publicHdrs,
      Sequence<?> publicTextualHdrs,
      Sequence<?> privateHdrs,
      Sequence<?> providers,
      Sequence<?> directCcCompilationContexts,
      Sequence<?> ccCompilationContexts,
      Sequence<?> defines,
      Sequence<?> includes)
      throws InterruptedException, EvalException {
    return ObjcCompilationContext.builder()
        .addPublicHeaders(Sequence.cast(publicHdrs, Artifact.class, "public_hdrs"))
        .addPublicTextualHeaders(
            Sequence.cast(publicTextualHdrs, Artifact.class, "public_textual_hdrs"))
        .addPrivateHeaders(Sequence.cast(privateHdrs, Artifact.class, "private_hdrs"))
        .addObjcProviders(Sequence.cast(providers, ObjcProvider.class, "providers"))
        .addDirectCcCompilationContexts(
            Sequence.cast(
                directCcCompilationContexts, CcCompilationContext.class, "cc_compilation_contexts"))
        .addCcCompilationContexts(
            Sequence.cast(
                ccCompilationContexts, CcCompilationContext.class, "cc_compilation_contexts"))
        .addDefines(Sequence.cast(defines, String.class, "defines"))
        .addIncludes(
            Sequence.cast(includes, String.class, "includes").stream()
                .map(PathFragment::create)
                .collect(toImmutableList()))
        .build();
  }
}
