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

package com.google.devtools.build.lib.analysis.starlark;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.BazelStarlarkContext;
import com.google.devtools.build.lib.packages.BzlInitThreadContext;
import com.google.devtools.build.lib.packages.BzlVisibility;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkBuildApiGlobals;
import java.util.List;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/**
 * Bazel implementation of {@link StarlarkBuildApiGlobals}: a collection of global Starlark build
 * API functions that belong in the global namespace.
 */
// TODO(brandjon): This should probably be refactored into a StarlarkLibrary#BZL field, analogous to
// StarlarkLibrary#COMMON and StarlarkLibrary#BUILD.
public class BazelBuildApiGlobals implements StarlarkBuildApiGlobals {

  @Override
  public void visibility(Object value, StarlarkThread thread) throws EvalException {
    // Manually check the experimental flag because enableOnlyWithFlag doesn't work for top-level
    // builtins.
    if (!thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_BZL_VISIBILITY)) {
      throw Starlark.errorf("Use of `visibility()` requires --experimental_bzl_visibility");
    }

    // Fail if we're not initializing a .bzl module, or if that .bzl module isn't on the
    // experimental allowlist, or if visibility is already set.
    BzlInitThreadContext context = BzlInitThreadContext.fromOrFailFunction(thread, "visibility");
    PackageIdentifier pkgId = context.getBzlFile().getPackageIdentifier();
    List<String> allowlist =
        thread.getSemantics().get(BuildLanguageOptions.EXPERIMENTAL_BZL_VISIBILITY_ALLOWLIST);
    checkVisibilityAllowlist(pkgId, allowlist);
    if (context.getBzlVisibility() != null) {
      throw Starlark.errorf(".bzl visibility may not be set more than once");
    }

    BzlVisibility bzlVisibility = null;
    // `visibility("public")` and `visibility("private")`
    if (value instanceof String) {
      if (value.equals("public")) {
        bzlVisibility = BzlVisibility.PUBLIC;
      } else if (value.equals("private")) {
        bzlVisibility = BzlVisibility.PRIVATE;
      }
      // `visibility(["//pkg1", "//pkg2", ...])`
    } else if (value instanceof StarlarkList) {
      List<String> packageStrings = Sequence.cast(value, String.class, "visibility list");
      ImmutableList.Builder<PackageIdentifier> packages =
          ImmutableList.builderWithExpectedSize(packageStrings.size());
      for (String packageString : packageStrings) {
        PackageIdentifier packageId;
        // Disallow "@foo//pkg", or even "@//pkg" or "@the_current_repo//pkg".
        if (packageString.startsWith("@")) {
          throw Starlark.errorf("package specifiers cannot begin with '@'");
        }
        try {
          packageId = PackageIdentifier.parse(packageString);
        } catch (LabelSyntaxException ex) {
          throw Starlark.errorf("Invalid package: %s", ex.getMessage());
        }
        // PackageIdentifier.parse() on a string without a repo qualifier returns an identifier in
        // the main repo. Substitute it with our own repo.
        Preconditions.checkState(packageId.getRepository().equals(RepositoryName.MAIN));
        packages.add(
            PackageIdentifier.create(
                context.getBzlFile().getRepository(), packageId.getPackageFragment()));
      }
      bzlVisibility = new BzlVisibility.PackageListBzlVisibility(packages.build());
    }
    if (bzlVisibility == null) {
      throw Starlark.errorf(
          "Invalid bzl-visibility: got '%s', want \"public\", \"private\", or list of package path"
              + " strings",
          Starlark.type(value));
    }
    context.setBzlVisibility(bzlVisibility);
  }

  private void checkVisibilityAllowlist(PackageIdentifier pkgId, List<String> allowlist)
      throws EvalException {
    // The allowlist is represented as a list of strings because BuildLanguageOptions isn't allowed
    // to depend on Label, PackageIdentifier, etc. For simplicity we just convert the strings to
    // PackageIdentifiers here, at linear cost and redundantly for each call to `visibility()`. This
    // is ok because the allowlist is not intended to stay permanent, it is expected to remain
    // small, and calls to visibility() are relatively infrequent.
    boolean foundMatch = false;
    for (String allowedPkgString : allowlist) {
      // TODO(b/22193153): This seems incorrect since parse doesn't take into account any repository
      // map. (This shouldn't matter within Google's monorepo, which doesn't use a repo map.)
      try {
        PackageIdentifier allowedPkgId = PackageIdentifier.parse(allowedPkgString);
        if (pkgId.equals(allowedPkgId)) {
          foundMatch = true;
          break;
        }
      } catch (LabelSyntaxException ex) {
        throw new EvalException("Invalid bzl-visibility allowlist", ex);
      }
    }
    if (!foundMatch) {
      throw Starlark.errorf(
          "`visibility() is not enabled for package %s; consider adding it to "
              + "--experimental_bzl_visibility_allowlist",
          pkgId.getCanonicalForm());
    }
  }

  @Override
  public StarlarkLateBoundDefault<?> configurationField(
      String fragment, String name, StarlarkThread thread) throws EvalException {
    BazelStarlarkContext context = BazelStarlarkContext.from(thread);
    Class<?> fragmentClass = context.getFragmentNameToClass().get(fragment);
    if (fragmentClass == null) {
      throw Starlark.errorf("invalid configuration fragment name '%s'", fragment);
    }
    try {
      return StarlarkLateBoundDefault.forConfigurationField(
          fragmentClass, name, context.getToolsRepository());
    } catch (StarlarkLateBoundDefault.InvalidConfigurationFieldException exception) {
      throw new EvalException(exception);
    }
  }
}
