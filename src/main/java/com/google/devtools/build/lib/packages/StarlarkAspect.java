// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkAspectApi;
import net.starlark.java.eval.EvalException;

/** Represents an aspect which can be attached to a Starlark-defined rule attribute. */
public interface StarlarkAspect extends StarlarkAspectApi {

  /**
   * Attaches this aspect and its required aspects to the given aspects list.
   *
   * @param baseAspectName is the name of the base aspect requiring this aspect, can be {@code null}
   *     if the aspect is directly listed in the aspects list
   * @param aspectsListBuilder is the list to add this aspect to
   * @throws EvalException if this aspect cannot be successfully added to the aspects list.
   */
  void attachToAspectsList(String baseAspectName, AspectsListBuilder aspectsListBuilder)
      throws EvalException;

  /** Returns the aspect class for this aspect. */
  AspectClass getAspectClass();

  /** Returns a set of the names of parameters required to create this aspect. */
  ImmutableSet<String> getParamAttributes();

  /** Returns the name of this aspect. */
  String getName();

  /** Returns a function to extract the aspect parameters values from its base rule. */
  Function<Rule, AspectParameters> getDefaultParametersExtractor();
}
