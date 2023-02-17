// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.rules.objc.ObjcProvider.CC_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FLAG;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FORCE_LOAD_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.Flag.USES_CPP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.IMPORTED_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.J2OBJC_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LINKOPT;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LINKSTAMP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.MODULE_MAP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SDK_DYLIB;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SDK_FRAMEWORK;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SOURCE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.UMBRELLA_HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.WEAK_SDK_FRAMEWORK;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext;
import com.google.devtools.build.lib.rules.cpp.CppModuleMap;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Iterator;
import java.util.List;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkValue;

/**
 * Contains information common to multiple objc_* rules, and provides a unified API for extracting
 * and accessing it.
 */
// TODO(bazel-team): Decompose and subsume area-specific logic and data into the various *Support
// classes. Make sure to distinguish rule output (providers, runfiles, ...) from intermediate,
// rule-internal information. Any provider created by a rule should not be read, only published.
public final class ObjcCommon implements StarlarkValue {

  /** Filters fileset artifacts out of a group of artifacts. */
  public static ImmutableList<Artifact> filterFileset(Iterable<Artifact> artifacts) {
    ImmutableList.Builder<Artifact> inputs = ImmutableList.<Artifact>builder();
    for (Artifact artifact : artifacts) {
      if (!artifact.isFileset()) {
        inputs.add(artifact);
      }
    }
    return inputs.build();
  }

  /**
   * Indicates the purpose the ObjcCommon is used for.
   *
   * <p>The purpose determines whether ObjcCommon.build() will build an ObjcProvider or an
   * ObjcProvider.Builder. In compile-and-link mode, ObjcCommon.build() will output an
   * ObjcProvider.Builder. The builder is expected to combine with the CcCompilationContext from a
   * compile action, to form a complete ObjcProvider. In link-only mode, ObjcCommon can (and does)
   * output the full ObjcProvider.
   */
  public enum Purpose {
    /** The ObjcCommon will be used for compile and link. */
    COMPILE_AND_LINK,
    /** The ObjcCommon will be used for linking only. */
    LINK_ONLY,
  }

  static class Builder {
    private final Purpose purpose;
    private final RuleContext context;
    private final StarlarkSemantics semantics;
    private final BuildConfigurationValue buildConfiguration;
    private Optional<CompilationAttributes> compilationAttributes = Optional.absent();
    private Optional<CompilationArtifacts> compilationArtifacts = Optional.absent();
    private Iterable<ObjcProvider> objcProviders = ImmutableList.of();
    private Iterable<ObjcProvider> runtimeObjcProviders = ImmutableList.of();
    private Iterable<PathFragment> includes = ImmutableList.of();
    private IntermediateArtifacts intermediateArtifacts;
    private boolean alwayslink;
    private Iterable<String> linkopts = ImmutableList.of();
    private boolean hasModuleMap;
    private Iterable<Artifact> extraImportLibraries = ImmutableList.of();
    private Iterable<CcCompilationContext> ccCompilationContexts = ImmutableList.of();
    private Iterable<CcCompilationContext> directCCompilationContexts = ImmutableList.of();
    private Iterable<CcLinkingContext> ccLinkingContexts = ImmutableList.of();
    private Iterable<CcLinkingContext> ccLinkStampContexts = ImmutableList.of();
    private Iterable<CcCompilationContext> ccCompilationContextsForDirectFields =
        ImmutableList.of();

    /**
     * Builder for {@link ObjcCommon} obtaining both attribute data and configuration data from the
     * given rule context.
     */
    Builder(Purpose purpose, RuleContext context) throws InterruptedException {
      this(purpose, context, context.getConfiguration());
    }

    /**
     * Builder for {@link ObjcCommon} obtaining attribute data from the rule context and
     * configuration data from the given configuration object for use in situations where a single
     * target's outputs are under multiple configurations.
     */
    Builder(Purpose purpose, RuleContext context, BuildConfigurationValue buildConfiguration)
        throws InterruptedException {
      this.purpose = purpose;
      this.context = Preconditions.checkNotNull(context);
      this.semantics = context.getAnalysisEnvironment().getStarlarkSemantics();
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    public Builder setCompilationAttributes(CompilationAttributes baseCompilationAttributes) {
      Preconditions.checkState(
          !this.compilationAttributes.isPresent(),
          "compilationAttributes is already set to: %s",
          this.compilationAttributes);
      this.compilationAttributes = Optional.of(baseCompilationAttributes);
      return this;
    }

    Builder setCompilationArtifacts(CompilationArtifacts compilationArtifacts) {
      Preconditions.checkState(
          !this.compilationArtifacts.isPresent(),
          "compilationArtifacts is already set to: %s",
          this.compilationArtifacts);
      this.compilationArtifacts = Optional.of(compilationArtifacts);
      return this;
    }

    private static ImmutableList<CcCompilationContext> getCcCompilationContexts(
        Iterable<CcInfo> ccInfos) {
      return Streams.stream(ccInfos)
          .map(CcInfo::getCcCompilationContext)
          .collect(ImmutableList.toImmutableList());
    }

    Builder addDirectCcCompilationContexts(Iterable<CcInfo> ccInfos) {
      // TODO(waltl): Support direct CcCompilationContexts in CcCompilationHelper.
      Preconditions.checkState(
          this.purpose.equals(Purpose.LINK_ONLY),
          "direct CcCompilationContext is only supported for LINK_ONLY purpose");
      this.directCCompilationContexts =
          Iterables.concat(this.directCCompilationContexts, getCcCompilationContexts(ccInfos));
      return this;
    }

    Builder addCcCompilationContexts(Iterable<CcInfo> ccInfos) {
      this.ccCompilationContexts =
          Iterables.concat(this.ccCompilationContexts, getCcCompilationContexts(ccInfos));
      return this;
    }

    Builder addCcCompilationContextsForDirectFields(Iterable<CcInfo> ccInfos) {
      this.ccCompilationContextsForDirectFields =
          Iterables.concat(
              this.ccCompilationContextsForDirectFields, getCcCompilationContexts(ccInfos));
      return this;
    }

    Builder addDeps(List<? extends TransitiveInfoCollection> deps) {
      ImmutableList.Builder<ObjcProvider> objcProviders = ImmutableList.builder();
      ImmutableList.Builder<CcInfo> ccInfos = ImmutableList.builder();
      ImmutableList.Builder<CcInfo> ccInfosForDirectFields = ImmutableList.builder();
      ImmutableList.Builder<CcLinkingContext> ccLinkingContexts = ImmutableList.builder();
      ImmutableList.Builder<CcLinkingContext> ccLinkStampContexts = ImmutableList.builder();

      for (TransitiveInfoCollection dep : deps) {
        if (dep.get(ObjcProvider.STARLARK_CONSTRUCTOR) != null) {
          addAnyProviders(objcProviders, dep, ObjcProvider.STARLARK_CONSTRUCTOR);
        } else {
          // This is the way we inject cc_library attributes into direct fields.
          addAnyProviders(ccInfosForDirectFields, dep, CcInfo.PROVIDER);
          // We only use CcInfo's linking info if there is no ObjcProvider.  This is required so
          // that objc_library archives do not get treated as if they are from cc targets.
          addAnyContexts(ccLinkingContexts, dep, CcInfo.PROVIDER, CcInfo::getCcLinkingContext);
        }
        addAnyProviders(ccInfos, dep, CcInfo.PROVIDER);
        // Temporary solution to specially handle LinkStamps, so that they don't get dropped.  When
        // linking info has been fully migrated to CcInfo, we can drop this.
        addAnyContexts(ccLinkStampContexts, dep, CcInfo.PROVIDER, CcInfo::getCcLinkingContext);
      }
      addObjcProviders(objcProviders.build());
      addCcCompilationContexts(ccInfos.build());
      addCcCompilationContextsForDirectFields(ccInfosForDirectFields.build());
      this.ccLinkingContexts = Iterables.concat(this.ccLinkingContexts, ccLinkingContexts.build());
      this.ccLinkStampContexts =
          Iterables.concat(this.ccLinkStampContexts, ccLinkStampContexts.build());

      return this;
    }

    private <T extends Info> ImmutableList.Builder<T> addAnyProviders(
        ImmutableList.Builder<T> listBuilder,
        TransitiveInfoCollection collection,
        BuiltinProvider<T> providerClass) {
      T provider = collection.get(providerClass);
      if (provider != null) {
        listBuilder.add(provider);
      }
      return listBuilder;
    }

    private <T extends Info, U> ImmutableList.Builder<U> addAnyContexts(
        ImmutableList.Builder<U> listBuilder,
        TransitiveInfoCollection collection,
        BuiltinProvider<T> providerClass,
        Function<T, U> getContext) {
      T provider = collection.get(providerClass);
      if (provider != null) {
        listBuilder.add(getContext.apply(provider));
      }
      return listBuilder;
    }

    /**
     * Add providers which will be exposed both to the declaring rule and to any dependers on the
     * declaring rule.
     */
    Builder addObjcProviders(Iterable<ObjcProvider> objcProviders) {
      this.objcProviders = Iterables.concat(this.objcProviders, objcProviders);
      return this;
    }

    /** Adds includes to be passed into compile actions with {@code -I}. */
    public Builder addIncludes(NestedSet<PathFragment> includes) {
      // The includes are copied to a new list in the .build() method, so flattening here should be
      // benign.
      this.includes = Iterables.concat(this.includes, includes.toList());
      return this;
    }

    /** Adds includes to be passed into compile actions with {@code -I}. */
    public Builder addIncludes(Iterable<PathFragment> includes) {
      this.includes = Iterables.concat(this.includes, includes);
      return this;
    }

    Builder setIntermediateArtifacts(IntermediateArtifacts intermediateArtifacts) {
      this.intermediateArtifacts = intermediateArtifacts;
      return this;
    }

    Builder setAlwayslink(boolean alwayslink) {
      this.alwayslink = alwayslink;
      return this;
    }

    /**
     * Specifies that this target has a clang module map. This should be called if this target
     * compiles sources or exposes headers for other targets to use. Note that this does not add
     * the action to generate the module map. It simply indicates that it should be added to the
     * provider.
     */
    Builder setHasModuleMap() {
      this.hasModuleMap = true;
      return this;
    }

    ObjcCommon build() {

      ObjcCompilationContext.Builder objcCompilationContextBuilder =
          ObjcCompilationContext.builder();

      ObjcProvider.Builder objcProvider = new ObjcProvider.Builder(semantics);

      objcProvider
          .addAll(IMPORTED_LIBRARY, extraImportLibraries)
          .addTransitiveAndPropagate(objcProviders);

      objcCompilationContextBuilder
          .addIncludes(includes)
          .addObjcProviders(objcProviders)
          .addObjcProviders(runtimeObjcProviders)
          .addDirectCcCompilationContexts(directCCompilationContexts)
          // TODO(bazel-team): This pulls in stl via
          // CcCompilationHelper.getStlCcCompilationContext(), but probably shouldn't.
          .addCcCompilationContexts(ccCompilationContexts);

      for (CcCompilationContext headerProvider : ccCompilationContextsForDirectFields) {
        objcProvider.addAllDirect(HEADER, headerProvider.getDeclaredIncludeSrcs().toList());
      }

      for (CcLinkingContext linkProvider : ccLinkingContexts) {
        ImmutableList<String> linkOpts = linkProvider.getFlattenedUserLinkFlags();
        addLinkoptsToObjcProvider(linkOpts, objcProvider);
        objcProvider
            .addTransitiveAndPropagate(
                CC_LIBRARY,
                NestedSetBuilder.<LibraryToLink>linkOrder()
                    .addTransitive(linkProvider.getLibraries())
                    .build());
      }
      addLinkoptsToObjcProvider(linkopts, objcProvider);

      for (CcLinkingContext ccLinkStampContext : ccLinkStampContexts) {
        objcProvider.addAll(LINKSTAMP, ccLinkStampContext.getLinkstamps());
      }

      if (compilationAttributes.isPresent()) {
        CompilationAttributes attributes = compilationAttributes.get();
        PathFragment usrIncludeDir = PathFragment.create(AppleToolchain.sdkDir() + "/usr/include/");
        Iterable<PathFragment> sdkIncludes =
            Iterables.transform(
                attributes.sdkIncludes().toList(), (p) -> usrIncludeDir.getRelative(p));
        objcProvider
            .addAll(SDK_FRAMEWORK, attributes.sdkFrameworks())
            .addAll(WEAK_SDK_FRAMEWORK, attributes.weakSdkFrameworks())
            .addAll(SDK_DYLIB, attributes.sdkDylibs())
            .addAllDirect(HEADER, attributes.hdrs().toList())
            .addAllDirect(HEADER, attributes.textualHdrs().toList());
        objcCompilationContextBuilder
            .addPublicHeaders(filterFileset(attributes.hdrs().toList()))
            .addPublicTextualHeaders(filterFileset(attributes.textualHdrs().toList()))
            .addDefines(attributes.defines())
            .addIncludes(
                attributes
                    .headerSearchPaths(
                        buildConfiguration.getGenfilesFragment(context.getRepository()))
                    .toList())
            .addIncludes(sdkIncludes);
      }

      for (CompilationArtifacts artifacts : compilationArtifacts.asSet()) {
        Iterable<Artifact> allSources =
            Iterables.concat(
                artifacts.getSrcs(), artifacts.getNonArcSrcs(), artifacts.getPrivateHdrs());
        objcProvider
            .addAll(LIBRARY, artifacts.getArchive().asSet())
            .addAll(SOURCE, allSources)
            .addAllDirect(SOURCE, allSources)
            .addAllDirect(HEADER, filterFileset(artifacts.getAdditionalHdrs().toList()));
        objcCompilationContextBuilder.addPublicHeaders(
            filterFileset(artifacts.getAdditionalHdrs().toList()));
        objcCompilationContextBuilder.addPrivateHeaders(artifacts.getPrivateHdrs());

        if (artifacts.getArchive().isPresent()
            && J2ObjcLibrary.J2OBJC_SUPPORTED_RULES.contains(context.getRule().getRuleClass())) {
          objcProvider.addAll(J2OBJC_LIBRARY, artifacts.getArchive().asSet());
        }

        boolean usesCpp = false;
        for (Artifact sourceFile :
            Iterables.concat(artifacts.getSrcs(), artifacts.getNonArcSrcs())) {
          usesCpp = usesCpp || ObjcRuleClasses.CPP_SOURCES.matches(sourceFile.getExecPath());
        }
        if (usesCpp) {
          objcProvider.add(FLAG, USES_CPP);
        }
      }

      if (alwayslink) {
        for (CompilationArtifacts artifacts : compilationArtifacts.asSet()) {
          for (Artifact archive : artifacts.getArchive().asSet()) {
            objcProvider.add(FORCE_LOAD_LIBRARY, archive);
          }
        }
        for (Artifact archive : extraImportLibraries) {
          objcProvider.add(FORCE_LOAD_LIBRARY, archive);
        }
      }

      if (hasModuleMap) {
        CppModuleMap moduleMap = intermediateArtifacts.swiftModuleMap();
        Optional<Artifact> umbrellaHeader = moduleMap.getUmbrellaHeader();
        if (umbrellaHeader.isPresent()) {
          objcProvider.add(UMBRELLA_HEADER, umbrellaHeader.get());
        }
        objcProvider.add(MODULE_MAP, moduleMap.getArtifact());
        objcProvider.addDirect(MODULE_MAP, moduleMap.getArtifact());
      }

      ObjcCompilationContext objcCompilationContext = objcCompilationContextBuilder.build();

      return new ObjcCommon(
          purpose, objcProvider.build(), objcCompilationContext, compilationArtifacts);
    }

    private void addLinkoptsToObjcProvider(
        Iterable<String> linkopts, ObjcProvider.Builder objcProvider) {
      ImmutableSet.Builder<String> frameworkLinkOpts = new ImmutableSet.Builder<>();
      ImmutableList.Builder<String> nonFrameworkLinkOpts = new ImmutableList.Builder<>();
      // Add any framework flags as frameworks directly, rather than as linkopts.
      for (Iterator<String> iterator = linkopts.iterator(); iterator.hasNext(); ) {
        String arg = iterator.next();
        if (arg.equals("-framework") && iterator.hasNext()) {
          frameworkLinkOpts.add(iterator.next());
        } else {
          nonFrameworkLinkOpts.add(arg);
        }
      }

      objcProvider
          .addAll(SDK_FRAMEWORK, frameworkLinkOpts.build())
          .addAll(LINKOPT, nonFrameworkLinkOpts.build());
    }
  }

  private final Purpose purpose;
  private final ObjcProvider objcProvider;
  private final ObjcCompilationContext objcCompilationContext;

  private final Optional<CompilationArtifacts> compilationArtifacts;

  private ObjcCommon(
      Purpose purpose,
      ObjcProvider objcProvider,
      ObjcCompilationContext objcCompilationContext,
      Optional<CompilationArtifacts> compilationArtifacts) {
    this.purpose = purpose;
    this.objcProvider = Preconditions.checkNotNull(objcProvider);
    this.objcCompilationContext = Preconditions.checkNotNull(objcCompilationContext);
    this.compilationArtifacts = Preconditions.checkNotNull(compilationArtifacts);
  }

  public Purpose getPurpose() {
    return purpose;
  }

  public ObjcProvider getObjcProvider() {
    return objcProvider;
  }

  public ObjcCompilationContext getObjcCompilationContext() {
    return objcCompilationContext;
  }

  public CcCompilationContext getCcCompilationContext() {
    return objcCompilationContext.createCcCompilationContext();
  }

  public Optional<CompilationArtifacts> getCompilationArtifacts() {
    return compilationArtifacts;
  }

  /**
   * Returns an {@link Optional} containing the compiled {@code .a} file, or
   * {@link Optional#absent()} if this object contains no {@link CompilationArtifacts} or the
   * compilation information has no sources.
   */
  public Optional<Artifact> getCompiledArchive() {
    if (compilationArtifacts.isPresent()) {
      return compilationArtifacts.get().getArchive();
    }
    return Optional.absent();
  }
}
