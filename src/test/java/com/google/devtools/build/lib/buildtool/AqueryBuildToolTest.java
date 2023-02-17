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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.buildtool.AqueryProcessor.AqueryActionFilterException;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.query2.aquery.ActionGraphQueryEnvironment;
import com.google.devtools.build.lib.query2.aquery.AqueryOptions;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.commands.AqueryCommand;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery.Code;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for aquery. */
@RunWith(JUnit4.class)
public class AqueryBuildToolTest extends BuildIntegrationTestCase {
  private ImmutableMap<String, QueryFunction> functions;

  @Before
  public final void setFunctions() {
    ImmutableMap.Builder<String, QueryFunction> builder = ImmutableMap.builder();

    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.FUNCTIONS) {
      builder.put(queryFunction.getName(), queryFunction);
    }

    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.AQUERY_FUNCTIONS) {
      builder.put(queryFunction.getName(), queryFunction);
    }

    functions = builder.build();
    runtimeWrapper.addOptionsClass(AqueryOptions.class);
  }

  @Test
  public void testConstructor_wrongAqueryFilterFormat_throwsError() throws Exception {
    QueryExpression expr = QueryParser.parse("deps(inputs('abc', //abc))", functions);

    assertThrows(AqueryActionFilterException.class, () -> new AqueryProcessor(expr));
  }

  @Test
  public void testConstructor_wrongPatternSyntax_throwsError() throws Exception {
    QueryExpression expr = QueryParser.parse("inputs('*abc', //abc)", functions);

    AqueryActionFilterException thrown =
        assertThrows(AqueryActionFilterException.class, () -> new AqueryProcessor(expr));
    assertThat(thrown).hasMessageThat().contains("Wrong query syntax:");
  }

  @Test
  public void testDmpActionGraphFromSkyframe_wrongOutputFormat_returnsFailure() throws Exception {
    addOptions("--output=text");
    CommandEnvironment env = runtimeWrapper.newCommand(AqueryCommand.class);
    AqueryProcessor aqueryProcessor = new AqueryProcessor(null);
    BlazeCommandResult result =
        aqueryProcessor.dumpActionGraphFromSkyframe(env, createNewRequest("aquery"));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getDetailedExitCode().getFailureDetail().getActionQuery().getCode())
        .isEqualTo(Code.SKYFRAME_STATE_PREREQ_UNMET);
  }
}
