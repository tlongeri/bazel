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

package com.google.devtools.build.lib.packages;

import static net.starlark.java.eval.Starlark.NONE;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.LabelValidator;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.packages.Package.NameConflictException;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.starlarkbuildapi.WorkspaceGlobalsApi;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** A collection of global Starlark build API functions that apply to WORKSPACE files. */
public class WorkspaceGlobals implements WorkspaceGlobalsApi {

  // Must start with a letter. Can contain ASCII letters and digits, underscore, dash, and dot.
  private static final Pattern LEGAL_WORKSPACE_NAME = Pattern.compile("^\\p{Alpha}[-.\\w]*$");

  private final boolean allowOverride;
  private final RuleFactory ruleFactory;

  public WorkspaceGlobals(boolean allowOverride, RuleFactory ruleFactory) {
    this.allowOverride = allowOverride;
    this.ruleFactory = ruleFactory;
  }

  @Override
  public void workspace(
      String name,
      StarlarkThread thread)
      throws EvalException, InterruptedException {
    if (!allowOverride) {
      throw Starlark.errorf(
          "workspace() function should be used only at the top of the WORKSPACE file");
    }
    if (!isLegalWorkspaceName(name)) {
      throw Starlark.errorf("%s is not a legal workspace name", name);
    }
    String errorMessage = LabelValidator.validateTargetName(name);
    if (errorMessage != null) {
      throw Starlark.errorf("%s", errorMessage);
    }
    PackageFactory.getContext(thread).pkgBuilder.setWorkspaceName(name);
    Package.Builder builder = PackageFactory.getContext(thread).pkgBuilder;
    RuleClass localRepositoryRuleClass = ruleFactory.getRuleClass("local_repository");
    RuleClass bindRuleClass = ruleFactory.getRuleClass("bind");
    Map<String, Object> kwargs = ImmutableMap.of("name", name, "path", ".");
    try {
      // This effectively adds a "local_repository(name = "<ws>", path = ".")"
      // definition to the WORKSPACE file.
      WorkspaceFactoryHelper.createAndAddRepositoryRule(
          builder,
          localRepositoryRuleClass,
          bindRuleClass,
          kwargs,
          thread.getSemantics(),
          thread.getCallStack());
    } catch (InvalidRuleException | NameConflictException | LabelSyntaxException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
    // Add entry in repository map from "@name" --> "@" to avoid issue where bazel
    // treats references to @name as a separate external repo
    builder.addRepositoryMappingEntry(RepositoryName.MAIN, name, RepositoryName.MAIN);
  }

  private static RepositoryName getRepositoryName(@Nullable Label label) {
    if (label == null) {
      // registration happened directly in the main WORKSPACE
      return RepositoryName.MAIN;
    }

    // registeration happened in a loaded bzl file
    return label.getRepository();
  }

  private static ImmutableList<TargetPattern> parsePatterns(
      List<String> patterns, Package.Builder builder, StarlarkThread thread) throws EvalException {
    BazelModuleContext bzlModule =
        BazelModuleContext.of(Module.ofInnermostEnclosingStarlarkFunction(thread));
    RepositoryName myName = getRepositoryName((bzlModule != null ? bzlModule.label() : null));
    RepositoryMapping renaming = builder.getRepositoryMappingFor(myName);
    TargetPattern.Parser parser =
        new TargetPattern.Parser(PathFragment.EMPTY_FRAGMENT, myName, renaming);
    ImmutableList.Builder<TargetPattern> parsedPatterns = ImmutableList.builder();
    for (String pattern : patterns) {
      try {
        parsedPatterns.add(parser.parse(pattern));
      } catch (TargetParsingException e) {
        throw Starlark.errorf("error parsing target pattern \"%s\": %s", pattern, e.getMessage());
      }
    }
    return parsedPatterns.build();
  }

  @Override
  public void registerExecutionPlatforms(Sequence<?> platformLabels, StarlarkThread thread)
      throws EvalException {
    // Add to the package definition for later.
    Package.Builder builder = PackageFactory.getContext(thread).pkgBuilder;
    List<String> patterns = Sequence.cast(platformLabels, String.class, "platform_labels");
    builder.addRegisteredExecutionPlatforms(parsePatterns(patterns, builder, thread));
  }

  @Override
  public void registerToolchains(Sequence<?> toolchainLabels, StarlarkThread thread)
      throws EvalException {
    // Add to the package definition for later.
    Package.Builder builder = PackageFactory.getContext(thread).pkgBuilder;
    List<String> patterns = Sequence.cast(toolchainLabels, String.class, "toolchain_labels");
    builder.addRegisteredToolchains(parsePatterns(patterns, builder, thread));
  }

  @Override
  public void bind(String name, Object actual, StarlarkThread thread)
      throws EvalException, InterruptedException {
    Label nameLabel;
    try {
      nameLabel = Label.parseAbsolute("//external:" + name, ImmutableMap.of());
    } catch (LabelSyntaxException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
    try {
      Package.Builder builder = PackageFactory.getContext(thread).pkgBuilder;
      RuleClass ruleClass = ruleFactory.getRuleClass("bind");
      WorkspaceFactoryHelper.addBindRule(
          builder,
          ruleClass,
          nameLabel,
          actual == NONE ? null : Label.parseAbsolute((String) actual, ImmutableMap.of()),
          thread.getSemantics(),
          thread.getCallStack());
    } catch (InvalidRuleException | Package.NameConflictException | LabelSyntaxException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
  }

  /**
   * Returns true if the given name is a valid workspace name.
   */
  public static boolean isLegalWorkspaceName(String name) {
    return LEGAL_WORKSPACE_NAME.matcher(name).matches();
  }
}
