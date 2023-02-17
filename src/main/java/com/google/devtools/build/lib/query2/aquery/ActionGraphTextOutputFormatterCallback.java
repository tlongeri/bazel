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
package com.google.devtools.build.lib.query2.aquery;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.util.StringUtil.decodeBytestringUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.SourceManifestAction;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.Substitution;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.skyframe.RuleConfiguredTargetValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.CommandDescriptionForm;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.util.ShellEscaper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;
import net.starlark.java.eval.EvalException;

/** Output callback for aquery, prints human readable output. */
class ActionGraphTextOutputFormatterCallback extends AqueryThreadsafeCallback {

  private final ActionKeyContext actionKeyContext = new ActionKeyContext();
  private final AqueryActionFilter actionFilters;
  private final RepositoryMapping mainRepoMapping;
  private Map<String, String> paramFileNameToContentMap;

  ActionGraphTextOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      AqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<KeyedConfiguredTargetValue> accessor,
      AqueryActionFilter actionFilters,
      RepositoryMapping mainRepoMapping) {
    super(eventHandler, options, out, skyframeExecutor, accessor);
    this.actionFilters = actionFilters;
    this.mainRepoMapping = mainRepoMapping;
  }

  @Override
  public String getName() {
    return "text";
  }

  @Override
  public void processOutput(Iterable<KeyedConfiguredTargetValue> partialResult)
      throws IOException, InterruptedException {
    try {
      // Enabling includeParamFiles should enable includeCommandline by default.
      options.includeCommandline |= options.includeParamFiles;

      for (KeyedConfiguredTargetValue keyedConfiguredTargetValue : partialResult) {
        ConfiguredTargetValue configuredTargetValue =
            keyedConfiguredTargetValue.getConfiguredTargetValue();
        if (!(configuredTargetValue instanceof RuleConfiguredTargetValue)) {
          // We have to include non-rule values in the graph to visit their dependencies, but they
          // don't have any actions to print out.
          continue;
        }
        for (ActionAnalysisMetadata action :
            ((RuleConfiguredTargetValue) configuredTargetValue).getActions()) {
          writeAction(action, printStream);
        }
        if (options.useAspects) {
          for (AspectValue aspectValue : accessor.getAspectValues(keyedConfiguredTargetValue)) {
            if (aspectValue != null) {
              for (ActionAnalysisMetadata action : aspectValue.getActions()) {
                writeAction(action, printStream);
              }
            }
          }
        }
      }
    } catch (CommandLineExpansionException | EvalException e) {
      throw new IOException(e.getMessage());
    }
  }

  private void writeAction(ActionAnalysisMetadata action, PrintStream printStream)
      throws IOException, CommandLineExpansionException, InterruptedException, EvalException {
    if (options.includeParamFiles && action instanceof ParameterFileWriteAction) {
      ParameterFileWriteAction parameterFileWriteAction = (ParameterFileWriteAction) action;

      String fileContent = String.join(" \\\n    ", parameterFileWriteAction.getArguments());
      String paramFileName = action.getPrimaryOutput().getExecPathString();

      getParamFileNameToContentMap().put(paramFileName, fileContent);
    }

    if (!AqueryUtils.matchesAqueryFilters(action, actionFilters)) {
      return;
    }

    ActionOwner actionOwner = action.getOwner();
    StringBuilder stringBuilder = new StringBuilder();
    stringBuilder
        .append(action.prettyPrint())
        .append('\n')
        .append("  Mnemonic: ")
        .append(action.getMnemonic())
        .append('\n');

    if (actionOwner != null) {
      BuildEvent configuration = actionOwner.getConfiguration();
      BuildEventStreamProtos.Configuration configProto =
          configuration.asStreamProto(/*context=*/ null).getConfiguration();

      stringBuilder
          .append("  Target: ")
          .append(actionOwner.getLabel().getDisplayForm(mainRepoMapping))
          .append('\n')
          .append("  Configuration: ")
          .append(configProto.getMnemonic())
          .append('\n');
      if (actionOwner.getExecutionPlatform() != null) {
        stringBuilder
            .append("  Execution platform: ")
            .append(actionOwner.getExecutionPlatform().label().getDisplayForm(mainRepoMapping))
            .append("\n");
      }

      // In the case of aspect-on-aspect, AspectDescriptors are listed in
      // topological order of the dependency graph.
      // e.g. [A -> B] would imply that aspect A is applied on top of aspect B.
      ImmutableList<AspectDescriptor> aspectDescriptors =
          actionOwner.getAspectDescriptors().reverse();
      if (!aspectDescriptors.isEmpty()) {
        stringBuilder
            .append("  AspectDescriptors: [")
            .append(
                aspectDescriptors.stream()
                    .map(
                        aspectDescriptor -> {
                          StringBuilder aspectDescription = new StringBuilder();
                          aspectDescription
                              .append(aspectDescriptor.getAspectClass().getName())
                              .append('(')
                              .append(
                                  aspectDescriptor
                                      .getParameters()
                                      .getAttributes()
                                      .entries()
                                      .stream()
                                      .map(
                                          parameter ->
                                              parameter.getKey()
                                                  + "='"
                                                  + parameter.getValue()
                                                  + "'")
                                      .collect(Collectors.joining(", ")))
                              .append(')');
                          return aspectDescription.toString();
                        })
                    .collect(Collectors.joining("\n    -> ")))
            .append("]\n");
      }
    }

    if (action instanceof ActionExecutionMetadata) {
      ActionExecutionMetadata actionExecutionMetadata = (ActionExecutionMetadata) action;
      stringBuilder
          .append("  ActionKey: ")
          .append(actionExecutionMetadata.getKey(actionKeyContext, /*artifactExpander=*/ null))
          .append('\n');
    }

    if (options.includeArtifacts) {
      stringBuilder
          .append("  Inputs: [")
          .append(
              action.getInputs().toList().stream()
                  .map(input -> escapeBytestringUtf8(input.getExecPathString()))
                  .sorted()
                  .collect(Collectors.joining(", ")))
          .append("]\n")
          .append("  Outputs: [")
          .append(
              action.getOutputs().stream()
                  .map(
                      output ->
                          escapeBytestringUtf8(
                              output.isTreeArtifact()
                                  ? output.getExecPathString() + " (TreeArtifact)"
                                  : output.getExecPathString()))
                  .sorted()
                  .collect(Collectors.joining(", ")))
          .append("]\n");
    }

    if (action instanceof AbstractAction) {
      AbstractAction abstractAction = (AbstractAction) action;
      // TODO(twerth): This handles the fixed environment. We probably want to output the inherited
      // environment as well.
      Iterable<Map.Entry<String, String>> fixedEnvironment =
          abstractAction.getEnvironment().getFixedEnv().entrySet();
      if (!Iterables.isEmpty(fixedEnvironment)) {
        stringBuilder
            .append("  Environment: [")
            .append(
                Streams.stream(fixedEnvironment)
                    .map(
                        environmentVariable ->
                            escapeBytestringUtf8(
                                environmentVariable.getKey()
                                    + "="
                                    + environmentVariable.getValue()))
                    .sorted()
                    .collect(Collectors.joining(", ")))
            .append("]\n");
      }
      ImmutableSet<Entry<String, String>> executionInfoSpecifiers =
          abstractAction.getExecutionInfo().entrySet();
      if (!executionInfoSpecifiers.isEmpty()) {
        stringBuilder
            .append("  ExecutionInfo: {")
            .append(
                executionInfoSpecifiers.stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(
                        e ->
                            String.format(
                                "%s: %s",
                                ShellEscaper.escapeString(e.getKey()),
                                ShellEscaper.escapeString(e.getValue())))
                    .collect(Collectors.joining(", ")))
            .append("}\n");
      }
    }
    if (options.includeCommandline && action instanceof CommandAction) {
      stringBuilder
          .append("  Command Line: ")
          .append(
              CommandFailureUtils.describeCommand(
                  CommandDescriptionForm.COMPLETE,
                  /* prettyPrintArgs= */ true,
                  ((CommandAction) action)
                      .getArguments().stream()
                          .map(a -> escapeBytestringUtf8(a))
                          .collect(toImmutableList()),
                  /* environment= */ null,
                  /* cwd= */ null,
                  action.getOwner().getConfigurationChecksum(),
                  action.getExecutionPlatform() == null
                      ? null
                      : Objects.toString(action.getExecutionPlatform().label())))
          .append("\n");
    }

    if (options.includeParamFiles) {
      // Assumption: if an Action takes a param file as an input, it will be used
      // to provide params to the command.
      for (Artifact input : action.getInputs().toList()) {
        String inputFileName = input.getExecPathString();
        if (getParamFileNameToContentMap().containsKey(inputFileName)) {
          stringBuilder
              .append("  Params File Content (")
              .append(inputFileName)
              .append("):\n    ")
              .append(getParamFileNameToContentMap().get(inputFileName))
              .append("\n");
        }
      }
    }
    Map<String, String> executionInfo = action.getExecutionInfo();
    if (!executionInfo.isEmpty()) {
      stringBuilder
          .append("  ExecutionInfo: {")
          .append(
              executionInfo.entrySet().stream()
                  .sorted(Map.Entry.comparingByKey())
                  .map(
                      e ->
                          String.format(
                              "%s: %s",
                              ShellEscaper.escapeString(e.getKey()),
                              ShellEscaper.escapeString(e.getValue())))
                  .collect(Collectors.joining(", ")))
          .append("}\n");
    }

    if (action instanceof TemplateExpansionAction) {
      stringBuilder
          .append("  Template: ")
          .append(((TemplateExpansionAction) action).getTemplate())
          .append("\n");
      stringBuilder.append("  Substitutions: [\n");
      for (Substitution substitution : ((TemplateExpansionAction) action).getSubstitutions()) {
        stringBuilder
            .append("    {")
            .append(substitution.getKey())
            .append(": ")
            .append(substitution.getValue())
            .append("}\n");
      }
      stringBuilder.append("  ]\n");
    }

    if (options.includeFileWriteContents && action instanceof FileWriteAction) {
      FileWriteAction fileWriteAction = (FileWriteAction) action;
      stringBuilder
          .append("  FileWriteContents: [")
          .append(
              Base64.getEncoder().encodeToString(fileWriteAction.getFileContents().getBytes(UTF_8)))
          .append("]\n");
    }
    if (options.includeFileWriteContents && action instanceof SourceManifestAction) {
      SourceManifestAction sourceManifestAction = (SourceManifestAction) action;
      stringBuilder
          .append("  FileWriteContents: [")
          .append(
              Base64.getEncoder()
                  .encodeToString(
                      sourceManifestAction.getFileContentsAsString(eventHandler).getBytes(UTF_8)))
          .append("]\n");
    }

    stringBuilder.append('\n');

    printStream.write(stringBuilder.toString().getBytes(UTF_8));
  }

  /** Lazy initialization of paramFileNameToContentMap. */
  private Map<String, String> getParamFileNameToContentMap() {
    if (paramFileNameToContentMap == null) {
      paramFileNameToContentMap = new HashMap<>();
    }
    return paramFileNameToContentMap;
  }

  /**
   * Decode a bytestring that might contain UTF-8, and escape any characters outside the basic
   * printable ASCII range.
   *
   * <p>This function is intended for human consumption in debug output that needs to be durable
   * against unusual encoding settings, and does not guarantee that the escaping process is
   * reverseable.
   *
   * <p>Characters other than printable ASCII but within the Basic Multilingual Plane are formatted
   * with `\\uXXXX`. Characters outside the BMP are formatted as `\\UXXXXXXXX`.
   */
  public static String escapeBytestringUtf8(String maybeUtf8) {
    if (maybeUtf8.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
      return maybeUtf8;
    }

    final String decoded = decodeBytestringUtf8(maybeUtf8);
    final StringBuilder sb = new StringBuilder(decoded.length() * 8);
    decoded
        .codePoints()
        .forEach(
            c -> {
              if (c >= 0x20 && c < 0x7F) {
                sb.appendCodePoint(c);
              } else if (c <= 0xFFFF) {
                sb.append(String.format("\\u%04X", c));
              } else {
                sb.append(String.format("\\U%08X", c));
              }
            });
    return sb.toString();
  }
}
