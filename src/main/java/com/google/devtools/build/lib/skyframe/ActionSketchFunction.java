// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actionsketch.ActionSketch;
import com.google.devtools.build.lib.actionsketch.HashAndVersion;
import com.google.devtools.build.lib.actionsketch.Sketches;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.BigIntegerFingerprintUtils;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * {@link ActionSketchFunction} computes an {@link ActionSketch} for the given Action. This is a
 * transitive hash of the dependent action keys and source file content hashes.
 */
public final class ActionSketchFunction implements SkyFunction {
  private final ActionKeyContext actionKeyContext;

  public ActionSketchFunction(ActionKeyContext actionKeyContext) {
    this.actionKeyContext = actionKeyContext;
  }

  public static SketchKey key(ActionLookupData key) {
    return SketchKey.create(key);
  }

  @AutoCodec.VisibleForSerialization
  @AutoCodec
  static class SketchKey extends AbstractSkyKey<ActionLookupData> {
    private static final LoadingCache<ActionLookupData, SketchKey> keyCache =
        Caffeine.newBuilder()
            .weakKeys()
            .initialCapacity(BlazeInterners.concurrencyLevel())
            .build(SketchKey::new);

    private SketchKey(ActionLookupData arg) {
      super(arg);
    }

    @AutoCodec.VisibleForSerialization
    @AutoCodec.Instantiator
    static SketchKey create(ActionLookupData arg) {
      return keyCache.get(arg);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.ACTION_SKETCH;
    }
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
    ActionLookupData actionLookupData = (ActionLookupData) skyKey.argument();
    ActionLookupValue actionLookupValue =
        ArtifactFunction.getActionLookupValue(actionLookupData.getActionLookupKey(), env);
    if (actionLookupValue == null) {
      return null;
    }

    Action action = actionLookupValue.getAction(actionLookupData.getActionIndex());
    List<Artifact> srcArtifacts = new ArrayList<>();
    List<SketchKey> depActions = new ArrayList<>();
    for (Artifact artifact : action.getInputs().toList()) {
      if (artifact.isSourceArtifact()) {
        srcArtifacts.add(artifact);
      } else {
        depActions.add(SketchKey.create(((DerivedArtifact) artifact).getGeneratingActionKey()));
      }
    }

    Map<SkyKey, SkyValue> srcArtifactValues = env.getValues(srcArtifacts);
    Map<SkyKey, SkyValue> depSketchValues = env.getValues(depActions);
    if (env.valuesMissing()) {
      return null;
    }

    BigInteger transitiveActionKeyHash = Sketches.computeActionKey(action, actionKeyContext);
    BigInteger transitiveSourceHash = BigInteger.ZERO;

    // Incorporate the direct source values.
    for (SkyValue val : srcArtifactValues.values()) {
      FileArtifactValue fileArtifactValue = (FileArtifactValue) val;
      byte[] sourceFingerprint = fileArtifactValue.getValueFingerprint();
      if (sourceFingerprint != null) {
        transitiveSourceHash =
            BigIntegerFingerprintUtils.compose(
                transitiveSourceHash, new BigInteger(1, sourceFingerprint));
      } else {
        transitiveSourceHash = null;
        break;
      }
    }

    // Incorporate the transitive action key and source values.
    for (SkyValue sketchVal : depSketchValues.values()) {
      ActionSketch depSketch = (ActionSketch) sketchVal;
      transitiveActionKeyHash =
          BigIntegerFingerprintUtils.compose(
              transitiveActionKeyHash, depSketch.transitiveActionLookupHash());
      HashAndVersion hashAndVersion = depSketch.transitiveSourceHash();
      if (hashAndVersion != null) {
        transitiveSourceHash =
            BigIntegerFingerprintUtils.composeNullable(transitiveSourceHash, hashAndVersion.hash());
      } else {
        transitiveSourceHash = null;
      }
    }

    return ActionSketch.builder()
        .setTransitiveActionLookupHash(transitiveActionKeyHash)
        .setTransitiveSourceHash(HashAndVersion.createNoVersion(transitiveSourceHash))
        .build();
  }
}
