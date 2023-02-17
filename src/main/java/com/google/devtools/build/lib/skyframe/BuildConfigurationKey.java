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

package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.common.options.OptionsParsingException;

/**
 * {@link SkyKey} for {@link com.google.devtools.build.lib.analysis.config.BuildConfigurationValue}.
 */
@AutoCodec
public final class BuildConfigurationKey implements SkyKey {

  /**
   * Creates a new configuration key based on the given options, after applying a platform mapping
   * transformation.
   *
   * @param platformMappingValue sky value that can transform a configuration key based on a
   *     platform mapping
   * @param options the desired configuration
   * @throws OptionsParsingException if the platform mapping cannot be parsed
   */
  public static BuildConfigurationKey withPlatformMapping(
      PlatformMappingValue platformMappingValue, BuildOptions options)
      throws OptionsParsingException {
    return platformMappingValue.map(withoutPlatformMapping(options));
  }

  /**
   * Returns the key for a requested configuration.
   *
   * <p>Callers are responsible for applying the platform mapping or ascertaining that a platform
   * mapping is not required.
   *
   * @param options the {@link BuildOptions} object the {@link BuildOptions} should be rebuilt from
   */
  @AutoCodec.Instantiator
  public static BuildConfigurationKey withoutPlatformMapping(BuildOptions options) {
    return interner.intern(new BuildConfigurationKey(options));
  }

  private static final Interner<BuildConfigurationKey> interner = BlazeInterners.newWeakInterner();

  private final BuildOptions options;

  private BuildConfigurationKey(BuildOptions options) {
    this.options = Preconditions.checkNotNull(options);
  }

  public BuildOptions getOptions() {
    return options;
  }

  @Override
  public SkyFunctionName functionName() {
    return SkyFunctions.BUILD_CONFIGURATION;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BuildConfigurationKey)) {
      return false;
    }
    BuildConfigurationKey otherConfig = (BuildConfigurationKey) o;
    return options.equals(otherConfig.options);
  }

  @Override
  public int hashCode() {
    return options.hashCode();
  }

  @Override
  public String toString() {
    // This format is depended on by integration tests.
    return "BuildConfigurationKey[" + options.checksum() + "]";
  }
}
