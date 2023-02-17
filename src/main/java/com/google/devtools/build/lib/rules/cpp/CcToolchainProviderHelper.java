// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.cpp.CcToolchain.AdditionalBuildVariablesComputer;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/** Helper responsible for creating CcToolchainProvider */
public class CcToolchainProviderHelper {

  /**
   * These files (found under the sysroot) may be unconditionally included in every C/C++
   * compilation.
   */
  static final ImmutableList<PathFragment> BUILTIN_INCLUDE_FILE_SUFFIXES =
      ImmutableList.of(PathFragment.create("include/stdc-predef.h"));

  private static final String SYSROOT_START = "%sysroot%/";
  private static final String WORKSPACE_START = "%workspace%/";
  private static final String CROSSTOOL_START = "%crosstool_top%/";
  private static final String PACKAGE_START = "%package(";
  private static final String PACKAGE_END = ")%";

  public static CcToolchainProvider getCcToolchainProvider(
      RuleContext ruleContext, CcToolchainAttributesProvider attributes)
      throws RuleErrorException, InterruptedException {
    BuildConfigurationValue configuration =
        Preconditions.checkNotNull(ruleContext.getConfiguration());
    CppConfiguration cppConfiguration =
        Preconditions.checkNotNull(configuration.getFragment(CppConfiguration.class));

    CcToolchainConfigInfo toolchainConfigInfo = attributes.getCcToolchainConfigInfo();
    ImmutableMap<String, PathFragment> toolPaths;
    CcToolchainFeatures toolchainFeatures;
    PathFragment toolsDirectory =
        getToolsDirectory(
            attributes.getCcToolchainLabel(), configuration.isSiblingRepositoryLayout());
    try {
      toolPaths = computeToolPaths(toolchainConfigInfo, toolsDirectory);
      toolchainFeatures = new CcToolchainFeatures(toolchainConfigInfo, toolsDirectory);
    } catch (EvalException e) {
      throw ruleContext.throwWithRuleError(e);
    }

    FdoContext fdoContext =
        FdoHelper.getFdoContext(
            ruleContext, attributes, configuration, cppConfiguration, toolPaths);
    if (fdoContext == null) {
      return null;
    }

    String runtimeSolibDirBase = attributes.getRuntimeSolibDirBase();
    final PathFragment runtimeSolibDir =
        ruleContext.getBinFragment().getRelative(runtimeSolibDirBase);
    String solibDirectory = "_solib_" + toolchainConfigInfo.getTargetCpu();
    PathFragment defaultSysroot =
        CppConfiguration.computeDefaultSysroot(toolchainConfigInfo.getBuiltinSysroot());
    PathFragment sysroot = calculateSysroot(attributes.getLibcTopLabel(), defaultSysroot);
    PathFragment targetSysroot =
        calculateSysroot(attributes.getTargetLibcTopLabel(), defaultSysroot);

    // Static runtime inputs.
    TransitiveInfoCollection staticRuntimeLib = attributes.getStaticRuntimeLib();
    final NestedSet<Artifact> staticRuntimeLinkInputs;

    if (staticRuntimeLib != null) {
      staticRuntimeLinkInputs = staticRuntimeLib.getProvider(FileProvider.class).getFilesToBuild();
    } else {
      staticRuntimeLinkInputs = null;
    }

    // Dynamic runtime inputs.
    TransitiveInfoCollection dynamicRuntimeLib = attributes.getDynamicRuntimeLib();
    NestedSet<Artifact> dynamicRuntimeLinkSymlinks;
    NestedSetBuilder<Artifact> dynamicRuntimeLinkInputs = NestedSetBuilder.stableOrder();
    if (dynamicRuntimeLib != null) {
      NestedSetBuilder<Artifact> dynamicRuntimeLinkSymlinksBuilder = NestedSetBuilder.stableOrder();
      for (Artifact artifact :
          dynamicRuntimeLib.getProvider(FileProvider.class).getFilesToBuild().toList()) {
        if (CppHelper.SHARED_LIBRARY_FILETYPES.matches(artifact.getFilename())) {
          dynamicRuntimeLinkInputs.add(artifact);
          dynamicRuntimeLinkSymlinksBuilder.add(
              SolibSymlinkAction.getCppRuntimeSymlink(
                  ruleContext, artifact, solibDirectory, runtimeSolibDirBase));
        }
      }
      if (dynamicRuntimeLinkInputs.isEmpty()) {
        dynamicRuntimeLinkSymlinks = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
      } else {
        dynamicRuntimeLinkSymlinks = dynamicRuntimeLinkSymlinksBuilder.build();
      }

    } else {
      dynamicRuntimeLinkSymlinks = null;
    }

    CcCompilationContext.Builder ccCompilationContextBuilder =
        CcCompilationContext.builder(
            ruleContext, ruleContext.getConfiguration(), ruleContext.getLabel());
    CppModuleMap moduleMap = createCrosstoolModuleMap(attributes);
    if (moduleMap != null) {
      ccCompilationContextBuilder.setCppModuleMap(moduleMap);
    }
    final CcCompilationContext ccCompilationContext = ccCompilationContextBuilder.build();

    ImmutableList.Builder<PathFragment> builtInIncludeDirectoriesBuilder = ImmutableList.builder();
    for (String s : toolchainConfigInfo.getCxxBuiltinIncludeDirectories()) {
      try {
        builtInIncludeDirectoriesBuilder.add(
            resolveIncludeDir(
                s, sysroot, toolsDirectory, configuration.isSiblingRepositoryLayout()));
      } catch (InvalidConfigurationException e) {
        ruleContext.ruleError(e.getMessage());
      }
    }
    ImmutableList<PathFragment> builtInIncludeDirectories =
        builtInIncludeDirectoriesBuilder.build();

    PackageSpecificationProvider allowlistForLayeringCheck =
        attributes.getAllowlistForLayeringCheck();

    PackageSpecificationProvider allowlistForLooseHeaderCheck =
        attributes.getAllowlistForLooseHeaderCheck();

    return new CcToolchainProvider(
        cppConfiguration,
        toolchainFeatures,
        toolsDirectory,
        attributes.getAllFiles(),
        attributes.getFullInputsForCrosstool(),
        attributes.getCompilerFiles(),
        attributes.getCompilerFilesWithoutIncludes(),
        attributes.getStripFiles(),
        attributes.getObjcopyFiles(),
        attributes.getAsFiles(),
        attributes.getArFiles(),
        attributes.getFullInputsForLink(),
        attributes.getIfsoBuilder(),
        attributes.getDwpFiles(),
        attributes.getCoverage(),
        attributes.getLibc(),
        attributes.getTargetLibc(),
        staticRuntimeLinkInputs,
        dynamicRuntimeLinkSymlinks,
        runtimeSolibDir,
        ccCompilationContext,
        attributes.isSupportsParamFiles(),
        attributes.isSupportsHeaderParsing(),
        attributes.getAdditionalBuildVariablesComputer(),
        getBuildVariables(
            ruleContext.getConfiguration().getOptions(),
            cppConfiguration,
            sysroot,
            attributes.getAdditionalBuildVariablesComputer()),
        getBuiltinIncludes(attributes.getLibc()),
        getBuiltinIncludes(attributes.getTargetLibc()),
        attributes.getLinkDynamicLibraryTool(),
        builtInIncludeDirectories,
        sysroot,
        targetSysroot,
        fdoContext,
        configuration.isToolConfiguration(),
        attributes.getLicensesProvider(),
        toolPaths,
        toolchainConfigInfo.getToolchainIdentifier(),
        toolchainConfigInfo.getCompiler(),
        toolchainConfigInfo.getAbiLibcVersion(),
        toolchainConfigInfo.getTargetCpu(),
        toolchainConfigInfo.getCcTargetOs(),
        defaultSysroot,
        // The runtime sysroot should really be set from --grte_top. However, currently libc has
        // no way to set the sysroot. The CROSSTOOL file does set the runtime sysroot, in the
        // builtin_sysroot field. This implies that you can not arbitrarily mix and match
        // Crosstool and libc versions, you must always choose compatible ones.
        defaultSysroot,
        toolchainConfigInfo.getTargetLibc(),
        ruleContext.getLabel(),
        solibDirectory,
        toolchainConfigInfo.getAbiVersion(),
        toolchainConfigInfo.getTargetSystemName(),
        computeAdditionalMakeVariables(toolchainConfigInfo),
        computeLegacyCcFlagsMakeVariable(toolchainConfigInfo),
        allowlistForLayeringCheck,
        allowlistForLooseHeaderCheck,
        getStarlarkValueForTool(Tool.OBJCOPY, toolPaths),
        getStarlarkValueForTool(Tool.GCC, toolPaths),
        getStarlarkValueForTool(Tool.CPP, toolPaths),
        getStarlarkValueForTool(Tool.NM, toolPaths),
        getStarlarkValueForTool(Tool.OBJDUMP, toolPaths),
        getStarlarkValueForTool(Tool.AR, toolPaths),
        getStarlarkValueForTool(Tool.STRIP, toolPaths),
        getStarlarkValueForTool(Tool.LD, toolPaths),
        getStarlarkValueForTool(Tool.GCOV, toolPaths));
  }

  /**
   * Resolve the given include directory.
   *
   * <p>If it starts with %sysroot%/, that part is replaced with the actual sysroot.
   *
   * <p>If it starts with %workspace%/, that part is replaced with the empty string (essentially
   * making it relative to the build directory).
   *
   * <p>If it starts with %crosstool_top%/ or is any relative path, it is interpreted relative to
   * the crosstool top. The use of assumed-crosstool-relative specifications is considered
   * deprecated, and all such uses should eventually be replaced by "%crosstool_top%/".
   *
   * <p>If it is of the form %package(@repository//my/package)%/folder, then it is interpreted as
   * the named folder in the appropriate package. All of the normal package syntax is supported. The
   * /folder part is optional.
   *
   * <p>It is illegal if it starts with a % and does not match any of the above forms to avoid
   * accidentally silently ignoring misspelled prefixes.
   *
   * <p>If it is absolute, it remains unchanged.
   */
  static PathFragment resolveIncludeDir(
      String s,
      PathFragment sysroot,
      PathFragment crosstoolTopPathFragment,
      boolean siblingRepositoryLayout)
      throws InvalidConfigurationException {
    PathFragment pathPrefix;
    String pathString;
    int packageEndIndex = s.indexOf(PACKAGE_END);
    if (packageEndIndex != -1 && s.startsWith(PACKAGE_START)) {
      String packageString = s.substring(PACKAGE_START.length(), packageEndIndex);
      try {
        // TODO(jungjw): This should probably be getExecPath.
        pathPrefix = PackageIdentifier.parse(packageString).getPackagePath(siblingRepositoryLayout);
      } catch (LabelSyntaxException e) {
        throw new InvalidConfigurationException("The package '" + packageString + "' is not valid");
      }
      int pathStartIndex = packageEndIndex + PACKAGE_END.length();
      if (pathStartIndex + 1 < s.length()) {
        if (s.charAt(pathStartIndex) != '/') {
          throw new InvalidConfigurationException(
              "The path in the package for '" + s + "' is not valid");
        }
        pathString = s.substring(pathStartIndex + 1, s.length());
      } else {
        pathString = "";
      }
    } else if (s.startsWith(SYSROOT_START)) {
      if (sysroot == null) {
        throw new InvalidConfigurationException(
            "A %sysroot% prefix is only allowed if the " + "default_sysroot option is set");
      }
      pathPrefix = sysroot;
      pathString = s.substring(SYSROOT_START.length(), s.length());
    } else if (s.startsWith(WORKSPACE_START)) {
      pathPrefix = PathFragment.EMPTY_FRAGMENT;
      pathString = s.substring(WORKSPACE_START.length(), s.length());
    } else {
      pathPrefix = crosstoolTopPathFragment;
      if (s.startsWith(CROSSTOOL_START)) {
        pathString = s.substring(CROSSTOOL_START.length(), s.length());
      } else if (s.startsWith("%")) {
        throw new InvalidConfigurationException(
            "The include path '" + s + "' has an " + "unrecognized %prefix%");
      } else {
        pathString = s;
      }
    }

    PathFragment path = PathFragment.create(pathString);
    return pathPrefix.getRelative(path);
  }

  private static String getStarlarkValueForTool(
      Tool tool, ImmutableMap<String, PathFragment> toolPaths) {
    PathFragment toolPath = getToolPathFragment(toolPaths, tool);
    return toolPath != null ? toolPath.getPathString() : "";
  }

  private static PathFragment calculateSysroot(Label libcTopLabel, PathFragment defaultSysroot) {
    if (libcTopLabel == null) {
      return defaultSysroot;
    }

    return libcTopLabel.getPackageFragment();
  }

  private static ImmutableList<Artifact> getBuiltinIncludes(NestedSet<Artifact> libc) {
    ImmutableList.Builder<Artifact> result = ImmutableList.builder();
    for (Artifact artifact : libc.toList()) {
      for (PathFragment suffix : BUILTIN_INCLUDE_FILE_SUFFIXES) {
        if (artifact.getExecPath().endsWith(suffix)) {
          result.add(artifact);
          break;
        }
      }
    }

    return result.build();
  }

  private static CppModuleMap createCrosstoolModuleMap(CcToolchainAttributesProvider attributes) {
    if (attributes.getModuleMap() == null) {
      return null;
    }
    Artifact moduleMapArtifact = attributes.getModuleMapArtifact();
    if (moduleMapArtifact == null) {
      return null;
    }
    return new CppModuleMap(moduleMapArtifact, "crosstool");
  }

  /**
   * Returns {@link CcToolchainVariables} instance with build variables that only depend on the
   * toolchain.
   *
   * @throws RuleErrorException if there are configuration errors making it impossible to resolve
   *     certain build variables of this toolchain
   */
  static CcToolchainVariables getBuildVariables(
      BuildOptions buildOptions,
      CppConfiguration cppConfiguration,
      PathFragment sysroot,
      AdditionalBuildVariablesComputer additionalBuildVariablesComputer) {
    CcToolchainVariables.Builder variables = CcToolchainVariables.builder();

    String minOsVersion = cppConfiguration.getMinimumOsVersion();
    if (minOsVersion != null) {
      variables.addStringVariable(CcCommon.MINIMUM_OS_VERSION_VARIABLE_NAME, minOsVersion);
    }

    if (sysroot != null) {
      variables.addStringVariable(CcCommon.SYSROOT_VARIABLE_NAME, sysroot.getPathString());
    }

    variables.addAllNonTransitive(additionalBuildVariablesComputer.apply(buildOptions));

    return variables.build();
  }

  private static ImmutableMap<String, PathFragment> computeToolPaths(
      CcToolchainConfigInfo ccToolchainConfigInfo, PathFragment crosstoolTopPathFragment)
      throws EvalException {
    Map<String, PathFragment> toolPathsCollector = Maps.newHashMap();
    for (Pair<String, String> tool : ccToolchainConfigInfo.getToolPaths()) {
      String pathStr = tool.getSecond();
      if (!PathFragment.isNormalized(pathStr)) {
        throw new IllegalArgumentException("The include path '" + pathStr + "' is not normalized.");
      }
      PathFragment path = PathFragment.create(pathStr);
      toolPathsCollector.put(tool.getFirst(), crosstoolTopPathFragment.getRelative(path));
    }

    if (toolPathsCollector.isEmpty()) {
      // If no paths are specified, we just use the names of the tools as the path.
      for (CppConfiguration.Tool tool : CppConfiguration.Tool.values()) {
        toolPathsCollector.put(
            tool.getNamePart(), crosstoolTopPathFragment.getRelative(tool.getNamePart()));
      }
    } else {
      Iterable<CppConfiguration.Tool> neededTools =
          Iterables.filter(
              EnumSet.allOf(CppConfiguration.Tool.class),
              tool -> {
                if (tool == CppConfiguration.Tool.DWP) {
                  // TODO(hlopko): check dwp tool in analysis when per_object_debug_info is enabled.
                  return false;
                } else if (tool == CppConfiguration.Tool.LLVM_PROFDATA || tool == Tool.LLVM_COV) {
                  // TODO(tmsriram): Fix this to check if this is a llvm crosstool
                  // and return true.  This needs changes to crosstool_config.proto.
                  return false;
                } else if (tool == CppConfiguration.Tool.GCOVTOOL
                    || tool == CppConfiguration.Tool.GCOV
                    || tool == CppConfiguration.Tool.OBJCOPY) {
                  // gcov-tool and objcopy are optional, don't check whether they're present
                  return false;
                } else {
                  return true;
                }
              });
      for (CppConfiguration.Tool tool : neededTools) {
        if (!toolPathsCollector.containsKey(tool.getNamePart())) {
          throw Starlark.errorf("Tool path for '%s' is missing", tool.getNamePart());
        }
      }
    }
    return ImmutableMap.copyOf(toolPathsCollector);
  }

  /**
   * Returns the path fragment that is either absolute or relative to the execution root that can be
   * used to execute the given tool.
   */
  static PathFragment getToolPathFragment(ImmutableMap<String, PathFragment> toolPaths, Tool tool) {
    return toolPaths.get(tool.getNamePart());
  }

  static PathFragment getToolsDirectory(Label ccToolchainLabel, boolean siblingRepositoryLayout) {
    return ccToolchainLabel.getPackageIdentifier().getExecPath(siblingRepositoryLayout);
  }

  private static ImmutableMap<String, String> computeAdditionalMakeVariables(
      CcToolchainConfigInfo ccToolchainConfigInfo) {
    Map<String, String> makeVariablesBuilder = new HashMap<>();
    // The following are to be used to allow some build rules to avoid the limits on stack frame
    // sizes and variable-length arrays.
    // These variables are initialized here, but may be overridden by the getMakeVariables() checks.
    makeVariablesBuilder.put("STACK_FRAME_UNLIMITED", "");
    makeVariablesBuilder.put(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME, "");
    for (Pair<String, String> variable : ccToolchainConfigInfo.getMakeVariables()) {
      makeVariablesBuilder.put(variable.getFirst(), variable.getSecond());
    }
    makeVariablesBuilder.remove(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME);

    return ImmutableMap.copyOf(makeVariablesBuilder);
  }

  // TODO(b/65151735): Remove when cc_flags is entirely from features.
  private static String computeLegacyCcFlagsMakeVariable(
      CcToolchainConfigInfo ccToolchainConfigInfo) {
    String legacyCcFlags = "";
    // Needs to ensure the last value with the name is used, to match the previous logic in
    // computeAdditionalMakeVariables.
    for (Pair<String, String> variable : ccToolchainConfigInfo.getMakeVariables()) {
      if (variable.getFirst().equals(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME)) {
        legacyCcFlags = variable.getSecond();
      }
    }

    return legacyCcFlags;
  }
}
