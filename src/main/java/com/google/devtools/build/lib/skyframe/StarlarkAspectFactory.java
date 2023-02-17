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
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.StarlarkProviderValidationUtil;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleConfiguredTargetUtil;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkValue;

/** A factory for aspects that are defined in Starlark. */
public class StarlarkAspectFactory implements ConfiguredAspectFactory {
  private final StarlarkDefinedAspect starlarkAspect;

  StarlarkAspectFactory(StarlarkDefinedAspect starlarkAspect) {
    this.starlarkAspect = starlarkAspect;
  }

  @Override
  public ConfiguredAspect create(
      ConfiguredTargetAndData ctadBase,
      RuleContext ruleContext,
      AspectParameters parameters,
      RepositoryName toolsRepository)
      throws InterruptedException, ActionConflictException {
    StarlarkRuleContext ctx;
    try {
      ctx = ruleContext.initStarlarkRuleContext();
    } catch (RuleErrorException e) {
      // TODO(bazel-team): Doesn't this double-log the message, if the exception was created by
      // RuleContext#throwWithRuleError?
      ruleContext.ruleError(e.getMessage());
      return null;
    }
    try {
      Object aspectStarlarkObject =
          Starlark.fastcall(
              ruleContext.getStarlarkThread(),
              starlarkAspect.getImplementation(),
              /*positional=*/ new Object[] {ctadBase.getConfiguredTarget(), ctx},
              /*named=*/ new Object[0]);

      // If allowing analysis failures, targets should be created somewhat normally, and errors
      // will be propagated via a hook elsewhere as AnalysisFailureInfo.
      boolean allowAnalysisFailures = ruleContext.getConfiguration().allowAnalysisFailures();

      if (ruleContext.hasErrors() && !allowAnalysisFailures) {
        return null;
      } else if (!(aspectStarlarkObject instanceof StructImpl)
          && !(aspectStarlarkObject instanceof Iterable)
          && !(aspectStarlarkObject instanceof Info)) {
        ruleContext.ruleError(
            String.format(
                "Aspect implementation should return a struct, a list, or a provider "
                    + "instance, but got %s",
                Starlark.type(aspectStarlarkObject)));
        return null;
      }
      return createAspect(aspectStarlarkObject, ruleContext);
    } catch (EvalException e) {
      ruleContext.ruleError("\n" + e.getMessageWithStack());
      return null;
    }
  }

  private static ConfiguredAspect createAspect(Object aspectStarlarkObject, RuleContext ruleContext)
      throws EvalException, ActionConflictException, InterruptedException {

    ConfiguredAspect.Builder builder = new ConfiguredAspect.Builder(ruleContext);

    if (aspectStarlarkObject instanceof Iterable) {
      addDeclaredProviders(builder, (Iterable) aspectStarlarkObject);
    } else {
      // Either an old-style struct or a single declared provider (not in a list)
      Info info = (Info) aspectStarlarkObject;
      if (info.getProvider().getKey().equals(StructProvider.STRUCT.getKey())) {
        // Old-style struct, that may contain declared providers.
        StructImpl struct = (StructImpl) aspectStarlarkObject;
        for (String field : struct.getFieldNames()) {
          if (field.equals("output_groups")) {
            addOutputGroups(struct.getValue(field), builder);
          } else if (field.equals("providers")) {
            Object providers = struct.getValue(field);
            // TODO(adonovan): can we be more specific than iterable, and use Sequence.cast?
            if (!(providers instanceof Iterable)) {
              throw Starlark.errorf(
                  "The value for \"providers\" should be a list of declared providers, "
                      + "got %s instead",
                  Starlark.type(providers));
            }
            addDeclaredProviders(builder, (Iterable<?>) providers);
          } else {
            builder.addStarlarkTransitiveInfo(field, struct.getValue(field));
          }
        }
      } else {
        builder.addStarlarkDeclaredProvider(info);
      }
    }

    ConfiguredAspect configuredAspect = builder.build();
    StarlarkProviderValidationUtil.validateArtifacts(ruleContext);
    return configuredAspect;
  }

  private static void addDeclaredProviders(
      ConfiguredAspect.Builder builder, Iterable<?> aspectStarlarkObject) throws EvalException {
    int i = 0;
    for (Object o : aspectStarlarkObject) {
      if (!(o instanceof Info)) {
        throw Starlark.errorf(
            "A return value of an aspect implementation function should be "
                + "a sequence of declared providers, instead got a %s at index %d",
            Starlark.type(o), i);
      }
      builder.addStarlarkDeclaredProvider((Info) o);
      i++;
    }
  }

  private static void addOutputGroups(Object outputGroups, ConfiguredAspect.Builder builder)
      throws EvalException {
    for (Map.Entry<String, StarlarkValue> entry :
        Dict.cast(outputGroups, String.class, StarlarkValue.class, "output_groups").entrySet()) {
      builder.addOutputGroup(
          entry.getKey(),
          StarlarkRuleConfiguredTargetUtil.convertToOutputGroupValue(
              entry.getKey(), entry.getValue()));
    }
  }
}
