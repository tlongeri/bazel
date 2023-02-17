//Copyright 2016 The Bazel Authors. All rights reserved.
//
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.

package com.google.devtools.build.lib.bazel.repository;

import com.google.auto.value.AutoValue;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.List;

/**
 * Command-line options for repositories.
 */
public class RepositoryOptions extends OptionsBase {

  @Option(
      name = "repository_cache",
      oldName = "experimental_repository_cache",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      converter = OptionsUtils.PathFragmentConverter.class,
      help =
          "Specifies the cache location of the downloaded values obtained "
              + "during the fetching of external repositories. An empty string "
              + "as argument requests the cache to be disabled.")
  public PathFragment experimentalRepositoryCache;

  @Option(
      name = "registry",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.CHANGES_INPUTS},
      help =
          "Specifies the registries to use to locate Bazel module dependencies. The order is"
              + " important: modules will be looked up in earlier registries first, and only fall"
              + " back to later registries when they're missing from the earlier ones.")
  public List<String> registries;

  @Option(
      name = "experimental_repository_cache_hardlinks",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "If set, the repository cache will hardlink the file in case of a"
              + " cache hit, rather than copying. This is intended to save disk space.")
  public boolean useHardlinks;

  @Option(
      name = "experimental_repository_disable_download",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.UNKNOWN},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "If set, downloading external repositories is not allowed.")
  public boolean disableDownload;

  @Option(
      name = "experimental_repository_downloader_retries",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.UNKNOWN},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "The maximum number of attempts to retry a download error. If set to 0, retries are"
              + " disabled.")
  public int repositoryDownloaderRetries;

  @Option(
      name = "distdir",
      oldName = "experimental_distdir",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      converter = OptionsUtils.PathFragmentConverter.class,
      help =
          "Additional places to search for archives before accessing the network "
              + "to download them.")
  public List<PathFragment> experimentalDistdir;

  @Option(
      name = "http_timeout_scaling",
      defaultValue = "1.0",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help = "Scale all timeouts related to http downloads by the given factor")
  public double httpTimeoutScaling;

  @Option(
    name = "override_repository",
    defaultValue = "null",
    allowMultiple = true,
    converter = RepositoryOverrideConverter.class,
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    help = "Overrides a repository with a local directory."
  )
  public List<RepositoryOverride> repositoryOverrides;

  @Option(
      name = "experimental_scale_timeouts",
      defaultValue = "1.0",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "Scale all timeouts in Starlark repository rules by this factor."
              + " In this way, external repositories can be made working on machines"
              + " that are slower than the rule author expected, without changing the"
              + " source code")
  public double experimentalScaleTimeouts;

  @Option(
      name = "experimental_repository_hash_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If non-empty, specifies a file containing a resolved value, against which"
              + " the repository directory hashes should be verified")
  public String repositoryHashFile;

  @Option(
      name = "experimental_verify_repository_rules",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If list of repository rules for which the hash of the output directory should be"
              + " verified, provided a file is specified by"
              + " --experimental_repository_hash_file.")
  public List<String> experimentalVerifyRepositoryRules;

  @Option(
      name = "experimental_resolved_file_instead_of_workspace",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.GENERIC_INPUTS,
      effectTags = {OptionEffectTag.CHANGES_INPUTS},
      help = "If non-empty read the specified resolved file instead of the WORKSPACE file")
  public String experimentalResolvedFileInsteadOfWorkspace;

  @Option(
      name = "experimental_downloader_config",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Specify a file to configure the remote downloader with. This file consists of lines, "
              + "each of which starts with a directive (`allow`, `block` or `rewrite`) followed "
              + "by either a host name (for `allow` and `block`) or two patterns, one to match "
              + "against, and one to use as a substitute URL, with back-references starting from "
              + "`$1`. It is possible for multiple `rewrite` directives for the same URL to be "
              + "give, and in this case multiple URLs will be returned.")
  public String downloaderConfig;

  @Option(
      name = "experimental_enable_bzlmod",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "If true, Bazel tries to load external repositories from the Bzlmod system before "
              + "looking into the WORKSPACE file.")
  public boolean enableBzlmod;

  @Option(
      name = "ignore_dev_dependency",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "If true, Bazel ignores `bazel_dep` and `use_extension` declared as `dev_dependency` in "
              + "the MODULE.bazel of the root module. Note that, those dev dependencies are always "
              + "ignored in the MODULE.bazel if it's not the root module regardless of the value "
              + "of this flag.")
  public boolean ignoreDevDependency;

  @Option(
      name = "check_direct_dependencies",
      defaultValue = "warning",
      converter = CheckDirectDepsMode.Converter.class,
      documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "Check if the direct `bazel_dep` dependencies declared in the root module are the same"
              + " versions you get in the resolved dependency graph. Valid values are `off` to"
              + " disable the check, `warning` to print a warning when mismatch detected or `error`"
              + " to escalate it to a resolution failure.")
  public CheckDirectDepsMode checkDirectDependencies;

  /** An enum for specifying different modes for checking direct dependency accuracy. */
  public enum CheckDirectDepsMode {
    OFF, // Don't check direct dependency accuracy.
    WARNING, // Print warning when mismatch.
    ERROR; // Throw an error when mismatch.

    /** Converts to {@link CheckDirectDepsMode}. */
    public static class Converter extends EnumConverter<CheckDirectDepsMode> {
      public Converter() {
        super(CheckDirectDepsMode.class, "direct deps check mode");
      }
    }
  }

  /**
   * Converts from an equals-separated pair of strings into RepositoryName->PathFragment mapping.
   */
  public static class RepositoryOverrideConverter implements Converter<RepositoryOverride> {

    @Override
    public RepositoryOverride convert(String input) throws OptionsParsingException {
      String[] pieces = input.split("=", 2);
      if (pieces.length != 2) {
        throw new OptionsParsingException(
            "Repository overrides must be of the form 'repository-name=path'", input);
      }
      PathFragment path = PathFragment.create(pieces[1]);
      if (!path.isAbsolute()) {
        throw new OptionsParsingException(
            "Repository override directory must be an absolute path", input);
      }
      try {
        return RepositoryOverride.create(RepositoryName.create(pieces[0]), path);
      } catch (LabelSyntaxException e) {
        throw new OptionsParsingException("Invalid repository name given to override", input);
      }
    }

    @Override
    public String getTypeDescription() {
      return "an equals-separated mapping of repository name to path";
    }
  }

  /**
   * A repository override, represented by a name and an absolute path to a repository.
   */
  @AutoValue
  public abstract static class RepositoryOverride {

    private static RepositoryOverride create(RepositoryName repositoryName, PathFragment path) {
      return new AutoValue_RepositoryOptions_RepositoryOverride(repositoryName, path);
    }

    public abstract RepositoryName repositoryName();
    public abstract PathFragment path();
  }
}
