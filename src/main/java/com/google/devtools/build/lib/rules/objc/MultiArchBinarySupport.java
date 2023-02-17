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

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.apple.AppleCommandLineOptions;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration.ConfigurationDistinguisher;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.apple.ApplePlatform.PlatformType;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext;
import com.google.devtools.build.lib.rules.cpp.CcToolchainProvider;
import com.google.devtools.build.lib.rules.cpp.CppSemantics;
import com.google.devtools.build.lib.rules.objc.CompilationSupport.ExtraLinkArgs;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Support utility for creating multi-arch Apple binaries. */
public class MultiArchBinarySupport {
  private final RuleContext ruleContext;
  private final CppSemantics cppSemantics;

  /**
   * Returns all child configurations for this multi-arch target, mapped to the toolchains that they
   * should use.
   */
  static ImmutableMap<BuildConfigurationValue, CcToolchainProvider>
      getChildConfigurationsAndToolchains(RuleContext ruleContext) {
    ImmutableListMultimap<BuildConfigurationValue, CcToolchainProvider> configToProvider =
        ruleContext.getPrerequisitesByConfiguration(
            ObjcRuleClasses.CHILD_CONFIG_ATTR, CcToolchainProvider.PROVIDER);

    ImmutableMap.Builder<BuildConfigurationValue, CcToolchainProvider> result =
        ImmutableMap.builder();
    for (BuildConfigurationValue config : configToProvider.keySet()) {
      CcToolchainProvider toolchain = Iterables.getOnlyElement(configToProvider.get(config));
      result.put(config, toolchain);
    }

    return result.build();
  }

  static <V> ImmutableListMultimap<String, V> transformMap(
      Multimap<BuildConfigurationValue, V> input) {
    ImmutableListMultimap.Builder<String, V> result = ImmutableListMultimap.builder();
    for (Map.Entry<BuildConfigurationValue, V> entry : input.entries()) {
      result.put(entry.getKey().getCpu(), entry.getValue());
    }

    return result.build();
  }

  /** A tuple of values about dependency trees in a specific child configuration. */
  @AutoValue
  abstract static class DependencySpecificConfiguration {
    static DependencySpecificConfiguration create(
        BuildConfigurationValue config,
        CcToolchainProvider toolchain,
        ObjcProvider objcLinkProvider,
        ObjcProvider objcPropagateProvider) {
      return new AutoValue_MultiArchBinarySupport_DependencySpecificConfiguration(
          config, toolchain, objcLinkProvider, objcPropagateProvider);
    }

    /** Returns the child configuration for this tuple. */
    abstract BuildConfigurationValue config();

    /** Returns the cc toolchain for this configuration. */
    abstract CcToolchainProvider toolchain();

    /**
     * Returns the {@link ObjcProvider} to use as input to the support controlling link actoins;
     * dylib symbols should be subtracted from this provider.
     */
    abstract ObjcProvider objcLinkProvider();

    /**
     * Returns the {@link ObjcProvider} to propagate up to dependers; this will not have dylib
     * symbols subtracted, thus signaling that this target is still responsible for those symbols.
     */
    abstract ObjcProvider objcProviderWithDylibSymbols();
  }

  /** @param ruleContext the current rule context */
  public MultiArchBinarySupport(RuleContext ruleContext, CppSemantics cppSemantics) {
    this.ruleContext = ruleContext;
    this.cppSemantics = cppSemantics;
  }

  /**
   * Registers actions to link a single-platform/architecture Apple binary in a specific
   * configuration.
   *
   * @param dependencySpecificConfiguration a single {@link DependencySpecificConfiguration} that
   *     corresponds to the child configuration to link for this target. Can be obtained via {@link
   *     #getDependencySpecificConfigurations}
   * @param extraLinkArgs the extra linker args to add to link actions linking single-architecture
   *     binaries together
   * @param extraLinkInputs the extra linker inputs to be made available during link actions
   * @param isStampingEnabled whether linkstamping is enabled
   * @param infoCollections a list of provider collections which are propagated from the
   *     dependencies in the requested configuration
   * @param outputMapCollector a map to which output groups created by compile action generation are
   *     added
   * @return an {@link Artifact} representing the single-architecture binary linked from this call
   * @throws RuleErrorException if there are attribute errors in the current rule context
   */
  public Artifact registerConfigurationSpecificLinkActions(
      DependencySpecificConfiguration dependencySpecificConfiguration,
      ExtraLinkArgs extraLinkArgs,
      Iterable<Artifact> extraLinkInputs,
      boolean isStampingEnabled,
      Iterable<TransitiveInfoCollection> infoCollections,
      Map<String, NestedSet<Artifact>> outputMapCollector)
      throws RuleErrorException, InterruptedException {
    IntermediateArtifacts intermediateArtifacts =
        ObjcRuleClasses.intermediateArtifacts(
            ruleContext, dependencySpecificConfiguration.config());
    J2ObjcMappingFileProvider j2ObjcMappingFileProvider =
        J2ObjcMappingFileProvider.union(
            getTypedProviders(infoCollections, J2ObjcMappingFileProvider.PROVIDER));
    J2ObjcEntryClassProvider j2ObjcEntryClassProvider =
        new J2ObjcEntryClassProvider.Builder()
            .addTransitive(getTypedProviders(infoCollections, J2ObjcEntryClassProvider.PROVIDER))
            .build();
    ImmutableList<CcLinkingContext> ccLinkingContexts =
        getTypedProviders(infoCollections, CcInfo.PROVIDER).stream()
            .map(CcInfo::getCcLinkingContext)
            .collect(toImmutableList());

    ObjcProvider objcProvider = dependencySpecificConfiguration.objcLinkProvider();

    CompilationSupport compilationSupport =
        new CompilationSupport.Builder(ruleContext, cppSemantics)
            .setConfig(dependencySpecificConfiguration.config())
            .setToolchainProvider(dependencySpecificConfiguration.toolchain())
            .build();

    compilationSupport
        .registerLinkActions(
            objcProvider,
            ccLinkingContexts,
            j2ObjcMappingFileProvider,
            j2ObjcEntryClassProvider,
            extraLinkArgs,
            extraLinkInputs,
            isStampingEnabled)
        .validateAttributes();
    ruleContext.assertNoErrors();

    return intermediateArtifacts.strippedSingleArchitectureBinary();
  }

  /**
   * Returns a set of {@link DependencySpecificConfiguration} instances that comprise all
   * information about the dependencies for each child configuration. This can be used both to
   * register actions in {@link #registerConfigurationSpecificLinkActions} and collect provider
   * information to be propagated upstream.
   *
   * @param childConfigurationsAndToolchains the set of configurations and toolchains for which
   *     dependencies of the current rule are built
   * @param cpuToDepsCollectionMap a map from child configuration CPU to providers that "deps" of
   *     the current rule have propagated in that configuration
   * @param dylibProviders {@link TransitiveInfoCollection}s that dynamic library dependencies of
   *     the current rule have propagated
   * @throws RuleErrorException if there are attribute errors in the current rule context
   */
  public ImmutableSet<DependencySpecificConfiguration> getDependencySpecificConfigurations(
      Map<BuildConfigurationValue, CcToolchainProvider> childConfigurationsAndToolchains,
      ImmutableListMultimap<String, TransitiveInfoCollection> cpuToDepsCollectionMap,
      ImmutableList<TransitiveInfoCollection> dylibProviders)
      throws RuleErrorException, InterruptedException {
    Iterable<ObjcProvider> dylibObjcProviders = getDylibObjcProviders(dylibProviders);
    ImmutableSet.Builder<DependencySpecificConfiguration> childInfoBuilder = ImmutableSet.builder();

    for (BuildConfigurationValue childToolchainConfig : childConfigurationsAndToolchains.keySet()) {
      String childCpu = childToolchainConfig.getCpu();

      IntermediateArtifacts intermediateArtifacts =
          ObjcRuleClasses.intermediateArtifacts(ruleContext, childToolchainConfig);

      ObjcCommon common =
          common(
              ruleContext,
              childToolchainConfig,
              intermediateArtifacts,
              nullToEmptyList(cpuToDepsCollectionMap.get(childCpu)),
              dylibObjcProviders);
      ObjcProvider objcProviderWithDylibSymbols = common.getObjcProvider();
      ObjcProvider objcProvider =
          objcProviderWithDylibSymbols.subtractSubtrees(dylibObjcProviders, ImmutableList.of());

      childInfoBuilder.add(
          DependencySpecificConfiguration.create(
              childToolchainConfig,
              childConfigurationsAndToolchains.get(childToolchainConfig),
              objcProvider,
              objcProviderWithDylibSymbols));
    }

    return childInfoBuilder.build();
  }

  /**
   * Returns the ConfigurationDistinguisher that maps directly to the given PlatformType.
   *
   * @throws IllegalArgumentException if the platform type attribute is an unsupported value
   */
  private static ConfigurationDistinguisher configurationDistinguisher(PlatformType platformType) {
    switch (platformType) {
      case IOS:
        return ConfigurationDistinguisher.APPLEBIN_IOS;
      case CATALYST:
        return ConfigurationDistinguisher.APPLEBIN_CATALYST;
      case WATCHOS:
        return ConfigurationDistinguisher.APPLEBIN_WATCHOS;
      case TVOS:
        return ConfigurationDistinguisher.APPLEBIN_TVOS;
      case MACOS:
        return ConfigurationDistinguisher.APPLEBIN_MACOS;
    }
    throw new IllegalArgumentException("Unsupported platform type " + platformType);
  }

  /**
   * Returns the preferred minimum OS version based on information found from inputs.
   *
   * @param buildOptions the build's top-level options
   * @param platformType the platform type attribute found from the given rule being built
   * @param minimumOsVersion a minimum OS version represented to override command line options, if
   *     one has been found
   * @return an {@link DottedVersion.Option} representing the preferred minimum OS version if found,
   *     or null
   * @throws IllegalArgumentException if the platform type attribute is an unsupported value and the
   *     optional minimumOsVersion is not present
   */
  private static DottedVersion.Option minimumOsVersionOption(
      BuildOptionsView buildOptions,
      PlatformType platformType,
      Optional<DottedVersion> minimumOsVersion) {
    if (minimumOsVersion.isPresent()) {
      return DottedVersion.option(minimumOsVersion.get());
    }
    DottedVersion.Option option;
    switch (platformType) {
      case IOS:
      case CATALYST:
        option = buildOptions.get(AppleCommandLineOptions.class).iosMinimumOs;
        break;
      case WATCHOS:
        option = buildOptions.get(AppleCommandLineOptions.class).watchosMinimumOs;
        break;
      case TVOS:
        option = buildOptions.get(AppleCommandLineOptions.class).tvosMinimumOs;
        break;
      case MACOS:
        option = buildOptions.get(AppleCommandLineOptions.class).macosMinimumOs;
        break;
      default:
        throw new IllegalArgumentException("Unsupported platform type " + platformType);
    }
    return option;
  }

  /**
   * Creates a derivative set of build options for the given split transition with default options.
   *
   * @param buildOptions the build's top-level options
   * @param platformType the platform type attribute found from the given rule being built
   * @param minimumOsVersionOption a minimum OS version option for this given split
   * @return an {@link BuildOptionsView} to be used as a basis for a given multi arch binary split
   *     transition
   */
  private static BuildOptionsView defaultBuildOptionsForSplit(
      BuildOptionsView buildOptions,
      PlatformType platformType,
      DottedVersion.Option minimumOsVersionOption) {
    BuildOptionsView splitOptions = buildOptions.clone();

    AppleCommandLineOptions appleCommandLineOptions =
        splitOptions.get(AppleCommandLineOptions.class);
    appleCommandLineOptions.applePlatformType = platformType;
    switch (platformType) {
      case IOS:
      case CATALYST:
        appleCommandLineOptions.iosMinimumOs = minimumOsVersionOption;
        break;
      case WATCHOS:
        appleCommandLineOptions.watchosMinimumOs = minimumOsVersionOption;
        break;
      case TVOS:
        appleCommandLineOptions.tvosMinimumOs = minimumOsVersionOption;
        break;
      case MACOS:
        appleCommandLineOptions.macosMinimumOs = minimumOsVersionOption;
        break;
    }
    return splitOptions;
  }

  /**
   * Creates a split transition mapping based on --apple_platforms and --platforms.
   *
   * @param buildOptions the build's top-level options
   * @param platformType the platform type attribute found from the given rule being built
   * @param minimumOsVersion a minimum OS version represented to override command line options, if
   *     one has been found
   * @param applePlatforms the {@link List} of {@link Label}s representing Apple platforms to split
   *     on
   * @return an {@link ImmutableMap<String, BuildOptions>} representing the split transition for all
   *     platforms found
   */
  public static ImmutableMap<String, BuildOptions> handleApplePlatforms(
      BuildOptionsView buildOptions,
      PlatformType platformType,
      Optional<DottedVersion> minimumOsVersion,
      List<Label> applePlatforms) {
    ImmutableMap.Builder<String, BuildOptions> splitBuildOptions = ImmutableMap.builder();

    ConfigurationDistinguisher configurationDistinguisher =
        configurationDistinguisher(platformType);
    DottedVersion.Option minimumOsVersionOption =
        minimumOsVersionOption(buildOptions, platformType, minimumOsVersion);

    for (Label platform : ImmutableSortedSet.copyOf(applePlatforms)) {
      BuildOptionsView splitOptions =
          defaultBuildOptionsForSplit(buildOptions, platformType, minimumOsVersionOption);

      // Disable multi-platform options for child configurations.
      splitOptions.get(AppleCommandLineOptions.class).applePlatforms = ImmutableList.of();

      // The cpu flag will be set by platform mapping if a mapping exists.
      splitOptions.get(PlatformOptions.class).platforms = ImmutableList.of(platform);
      if (splitOptions.get(ObjcCommandLineOptions.class).enableCcDeps) {
        // Only set the (CC-compilation) configs for dependencies if explicitly required by the
        // user.
        // This helps users of the iOS rules who do not depend on CC rules as these config values
        // require additional flags to work (e.g. a custom crosstool) which now only need to be
        // set if this feature is explicitly requested.
        AppleCrosstoolTransition.setAppleCrosstoolTransitionPlatformConfiguration(
            buildOptions, splitOptions, platform);
      }
      AppleCommandLineOptions appleCommandLineOptions =
          splitOptions.get(AppleCommandLineOptions.class);
      // Set the configuration distinguisher last, as the method
      // setAppleCrosstoolTransitionPlatformConfiguration will set this value to the Apple CROSSTOOL
      // configuration distinguisher, and we want to make sure it's set for the right platform
      // instead in this split transition.
      appleCommandLineOptions.configurationDistinguisher = configurationDistinguisher;

      splitBuildOptions.put(platform.getCanonicalForm(), splitOptions.underlying());
    }

    return splitBuildOptions.build();
  }

  /**
   * Creates a split transition mapping based on Apple cpu options.
   *
   * @param buildOptions the build's top-level options
   * @param platformType the platform type attribute found from the given rule being built
   * @param minimumOsVersion a minimum OS version represented to override command line options, if
   *     one has been found
   * @return an {@link ImmutableMap<String, BuildOptions>} representing the split transition for all
   *     architectures found from cpu flags
   */
  public static ImmutableMap<String, BuildOptions> handleAppleCpus(
      BuildOptionsView buildOptions,
      PlatformType platformType,
      Optional<DottedVersion> minimumOsVersion) {
    List<String> cpus;
    ConfigurationDistinguisher configurationDistinguisher =
        configurationDistinguisher(platformType);
    DottedVersion.Option minimumOsVersionOption =
        minimumOsVersionOption(buildOptions, platformType, minimumOsVersion);

    switch (platformType) {
      case IOS:
        cpus = buildOptions.get(AppleCommandLineOptions.class).iosMultiCpus;
        if (cpus.isEmpty()) {
          cpus =
              ImmutableList.of(
                  AppleConfiguration.iosCpuFromCpu(buildOptions.get(CoreOptions.class).cpu));
        }
        DottedVersion actualMinimumOsVersion = DottedVersion.maybeUnwrap(minimumOsVersionOption);
        if (actualMinimumOsVersion != null
            && actualMinimumOsVersion.compareTo(DottedVersion.fromStringUnchecked("11.0")) >= 0) {
          List<String> non32BitCpus =
              cpus.stream()
                  .filter(cpu -> !ApplePlatform.is32Bit(PlatformType.IOS, cpu))
                  .collect(Collectors.toList());
          if (!non32BitCpus.isEmpty()) {
            // TODO(b/65969900): Throw an exception here. Ideally, there would be an applicable
            // exception to throw during configuration creation, but instead this validation needs
            // to be deferred to later.
            cpus = non32BitCpus;
          }
        }
        break;
      case WATCHOS:
        cpus = buildOptions.get(AppleCommandLineOptions.class).watchosCpus;
        if (cpus.isEmpty()) {
          cpus = ImmutableList.of(AppleCommandLineOptions.DEFAULT_WATCHOS_CPU);
        }
        break;
      case TVOS:
        cpus = buildOptions.get(AppleCommandLineOptions.class).tvosCpus;
        if (cpus.isEmpty()) {
          cpus = ImmutableList.of(AppleCommandLineOptions.DEFAULT_TVOS_CPU);
        }
        break;
      case MACOS:
        cpus = buildOptions.get(AppleCommandLineOptions.class).macosCpus;
        if (cpus.isEmpty()) {
          cpus = ImmutableList.of(AppleCommandLineOptions.DEFAULT_MACOS_CPU);
        }
        break;
      case CATALYST:
        cpus = buildOptions.get(AppleCommandLineOptions.class).catalystCpus;
        if (cpus.isEmpty()) {
          cpus = ImmutableList.of(AppleCommandLineOptions.DEFAULT_CATALYST_CPU);
        }
        break;
      default:
        throw new IllegalArgumentException("Unsupported platform type " + platformType);
    }

    // There may be some duplicate flag values.
    cpus = ImmutableSortedSet.copyOf(cpus).asList();
    ImmutableMap.Builder<String, BuildOptions> splitBuildOptions = ImmutableMap.builder();
    for (String cpu : cpus) {
      BuildOptionsView splitOptions =
          defaultBuildOptionsForSplit(buildOptions, platformType, minimumOsVersionOption);

      AppleCommandLineOptions appleCommandLineOptions =
          splitOptions.get(AppleCommandLineOptions.class);

      appleCommandLineOptions.appleSplitCpu = cpu;
      // If the new configuration does not use the apple crosstool, then it needs ios_cpu to be
      // to decide architecture.
      // TODO(b/29355778, b/28403953): Use a crosstool for any apple rule. Deprecate ios_cpu.
      appleCommandLineOptions.iosCpu = cpu;

      String platformCpu = ApplePlatform.cpuStringForTarget(platformType, cpu);
      if (splitOptions.get(ObjcCommandLineOptions.class).enableCcDeps) {
        // Only set the (CC-compilation) CPU for dependencies if explicitly required by the user.
        // This helps users of the iOS rules who do not depend on CC rules as these CPU values
        // require additional flags to work (e.g. a custom crosstool) which now only need to be
        // set if this feature is explicitly requested.
        AppleCrosstoolTransition.setAppleCrosstoolTransitionCpuConfiguration(
            buildOptions, splitOptions, platformCpu);
      }
      // Set the configuration distinguisher last, as setAppleCrosstoolTransitionCpuConfiguration
      // will set this value to the Apple CROSSTOOL configuration distinguisher, and we want to make
      // sure it's set for the right platform instead in this split transition.
      appleCommandLineOptions.configurationDistinguisher = configurationDistinguisher;

      splitBuildOptions.put(platformCpu, splitOptions.underlying());
    }
    return splitBuildOptions.build();
  }

  private static Iterable<ObjcProvider> getDylibObjcProviders(
      ImmutableList<TransitiveInfoCollection> transitiveInfoCollections) {
    // Dylibs.
    ImmutableList<ObjcProvider> frameworkObjcProviders =
        getTypedProviders(transitiveInfoCollections, AppleDynamicFrameworkInfo.STARLARK_CONSTRUCTOR)
            .stream()
            .map(frameworkProvider -> frameworkProvider.getDepsObjcProvider())
            .collect(ImmutableList.toImmutableList());
    // Bundle Loaders.
    ImmutableList<ObjcProvider> executableObjcProviders =
        getTypedProviders(transitiveInfoCollections, AppleExecutableBinaryInfo.STARLARK_CONSTRUCTOR)
            .stream()
            .map(frameworkProvider -> frameworkProvider.getDepsObjcProvider())
            .collect(ImmutableList.toImmutableList());

    return Iterables.concat(
        frameworkObjcProviders,
        executableObjcProviders,
        getTypedProviders(transitiveInfoCollections, ObjcProvider.STARLARK_CONSTRUCTOR));
  }

  private ObjcCommon common(
      RuleContext ruleContext,
      BuildConfigurationValue buildConfiguration,
      IntermediateArtifacts intermediateArtifacts,
      List<? extends TransitiveInfoCollection> propagatedDeps,
      Iterable<ObjcProvider> additionalDepProviders)
      throws InterruptedException {

    ObjcCommon.Builder commonBuilder =
        new ObjcCommon.Builder(ObjcCommon.Purpose.LINK_ONLY, ruleContext, buildConfiguration)
            .setCompilationAttributes(
                CompilationAttributes.Builder.fromRuleContext(ruleContext).build())
            .addDeps(propagatedDeps)
            .addObjcProviders(additionalDepProviders)
            .setIntermediateArtifacts(intermediateArtifacts)
            .setAlwayslink(false);

    return commonBuilder.build();
  }

  private <T> List<T> nullToEmptyList(List<T> inputList) {
    return inputList != null ? inputList : ImmutableList.<T>of();
  }

  private static <T extends Info> ImmutableList<T> getTypedProviders(
      Iterable<TransitiveInfoCollection> infoCollections, BuiltinProvider<T> providerClass) {
    return Streams.stream(infoCollections)
        .filter(infoCollection -> infoCollection.get(providerClass) != null)
        .map(infoCollection -> infoCollection.get(providerClass))
        .collect(ImmutableList.toImmutableList());
  }
}
