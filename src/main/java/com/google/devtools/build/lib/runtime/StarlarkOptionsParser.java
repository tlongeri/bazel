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

package com.google.devtools.build.lib.runtime;

import static com.google.devtools.build.lib.analysis.config.CoreOptionConverters.BUILD_SETTING_CONVERTERS;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.cmdline.LabelValidator;
import com.google.devtools.build.lib.cmdline.LabelValidator.BadLabelException;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.BuildSetting;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An options parser for starlark defined options. Takes a mutable {@link OptionsParser} that has
 * already parsed all native options (including those needed for loading). This class is in charge
 * of parsing and setting the starlark options for this {@link OptionsParser}.
 */
public class StarlarkOptionsParser {

  private final SkyframeExecutor skyframeExecutor;
  private final PathFragment relativeWorkingDirectory;
  private final ExtendedEventHandler reporter;
  private final OptionsParser nativeOptionsParser;

  // Result of #parse, store the parsed options and their values.
  private final Map<String, Object> starlarkOptions = new TreeMap<>();
  // Map of parsed starlark options to their loaded BuildSetting objects (used for canonicalization)
  private final Map<String, BuildSetting> parsedBuildSettings = new HashMap<>();

  /**
   * {@link ExtendedEventHandler} override that passes through "normal" events but not events that
   * would go to the build event proto.
   *
   * <p>Starlark flags are conceptually options but still need target pattern evaluation in {@link
   * com.google.devtools.build.lib.skyframe.TargetPatternPhaseFunction} to translate their labels to
   * actual targets. If we pass the {@link #post}able events that function calls, that would produce
   * "target loaded" and "target configured" events in the build event proto output that consumers
   * can confuse with actual targets requested by the build.
   *
   * <p>This is important because downstream services (like a continuous integration tool or build
   * results dashboard) read these messages to reconcile which requested targets were built. If they
   * determine Blaze tried to build {@code //foo //bar} then see a "target configured" message for
   * some other target {@code //my_starlark_flag}, they might show misleading messages like "Built 3
   * of 2 requested targets.".
   *
   * <p>Hence this class. By dropping those events, we restrict all info and error reporting logic
   * to the options parsing pipeline.
   */
  private static class NonPostingEventHandler implements ExtendedEventHandler {
    private final ExtendedEventHandler delegate;

    NonPostingEventHandler(ExtendedEventHandler delegate) {
      this.delegate = delegate;
    }

    @Override
    public void handle(Event e) {
      delegate.handle(e);
    }

    @Override
    public void post(ExtendedEventHandler.Postable e) {}
  }

  // Local cache of build settings so we don't repeatedly load them.
  private final Map<String, Target> buildSettings = new HashMap<>();

  private StarlarkOptionsParser(
      SkyframeExecutor skyframeExecutor,
      PathFragment relativeWorkingDirectory,
      ExtendedEventHandler reporter,
      OptionsParser nativeOptionsParser) {
    this.skyframeExecutor = skyframeExecutor;
    this.relativeWorkingDirectory = relativeWorkingDirectory;
    this.reporter = new NonPostingEventHandler(reporter);
    this.nativeOptionsParser = nativeOptionsParser;
  }

  public static StarlarkOptionsParser newStarlarkOptionsParser(
      CommandEnvironment env, OptionsParser optionsParser) {
    return new StarlarkOptionsParser(
        env.getSkyframeExecutor(),
        env.getRelativeWorkingDirectory(),
        env.getReporter(),
        optionsParser);
  }

  /** Parses all pre "--" residue for Starlark options. */
  // TODO(blaze-configurability): This method somewhat reinvents the wheel of
  // OptionsParserImpl.identifyOptionAndPossibleArgument. Consider combining. This would probably
  // require multiple rounds of parsing to fit starlark-defined options into native option format.
  @VisibleForTesting
  public void parse(ExtendedEventHandler eventHandler) throws OptionsParsingException {
    ImmutableList.Builder<String> residue = new ImmutableList.Builder<>();
    // Map of <option name (label), <unparsed option value, loaded option>>.
    Multimap<String, Pair<String, Target>> unparsedOptions = LinkedListMultimap.create();

    // sort the old residue into starlark flags and legitimate residue
    for (String arg : nativeOptionsParser.getPreDoubleDashResidue()) {
      // TODO(bazel-team): support single dash options?
      if (!arg.startsWith("--")) {
        residue.add(arg);
        continue;
      }

      parseArg(arg, unparsedOptions, eventHandler);
    }

    List<String> postDoubleDashResidue = nativeOptionsParser.getPostDoubleDashResidue();
    residue.addAll(postDoubleDashResidue);
    nativeOptionsParser.setResidue(residue.build(), postDoubleDashResidue);

    if (unparsedOptions.isEmpty()) {
      return;
    }

    // Map of flag label as a string to its loaded target and set value after parsing.
    HashMap<String, Pair<Target, Object>> buildSettingWithTargetAndValue = new HashMap<>();
    for (Map.Entry<String, Pair<String, Target>> option : unparsedOptions.entries()) {
      String loadedFlag = option.getKey();
      String unparsedValue = option.getValue().first;
      Target buildSettingTarget = option.getValue().second;
      BuildSetting buildSetting =
          buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting();
      // Do not recognize internal options, which are treated as if they did not exist.
      if (!buildSetting.isFlag()) {
        throw new OptionsParsingException(
            String.format("Unrecognized option: %s=%s", loadedFlag, unparsedValue));
      }
      Type<?> type = buildSetting.getType();
      if (buildSetting.isRepeatableFlag()) {
        type = Preconditions.checkNotNull(type.getListElementType());
      }
      Converter<?> converter = BUILD_SETTING_CONVERTERS.get(type);
      Object value;
      try {
        value = converter.convert(unparsedValue, nativeOptionsParser.getConversionContext());
      } catch (OptionsParsingException e) {
        throw new OptionsParsingException(
            String.format(
                "While parsing option %s=%s: '%s' is not a %s",
                loadedFlag, unparsedValue, unparsedValue, type),
            e);
      }
      if (buildSetting.allowsMultiple() || buildSetting.isRepeatableFlag()) {
        List<Object> newValue;
        if (buildSettingWithTargetAndValue.containsKey(loadedFlag)) {
          newValue =
              new ArrayList<>(
                  (Collection<?>) buildSettingWithTargetAndValue.get(loadedFlag).getSecond());
        } else {
          newValue = new ArrayList<>();
        }
        newValue.add(value);
        value = newValue;
      }
      buildSettingWithTargetAndValue.put(loadedFlag, Pair.of(buildSettingTarget, value));
    }

    Map<String, Object> parsedOptions = new HashMap<>();
    for (String buildSetting : buildSettingWithTargetAndValue.keySet()) {
      Pair<Target, Object> buildSettingAndFinalValue =
          buildSettingWithTargetAndValue.get(buildSetting);
      Target buildSettingTarget = buildSettingAndFinalValue.getFirst();
      BuildSetting buildSettingObject =
          buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting();
      boolean allowsMultiple = buildSettingObject.allowsMultiple();
      parsedBuildSettings.put(buildSetting, buildSettingObject);
      Object value = buildSettingAndFinalValue.getSecond();
      if (allowsMultiple) {
        List<?> defaultValue =
            ImmutableList.of(
                Objects.requireNonNull(
                    buildSettingTarget
                        .getAssociatedRule()
                        .getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME)));
        List<?> newValue = (List<?>) value;
        if (!newValue.equals(defaultValue)) {
          parsedOptions.put(buildSetting, value);
        }
      } else {
        if (!value.equals(
            buildSettingTarget
                .getAssociatedRule()
                .getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME))) {
          parsedOptions.put(buildSetting, buildSettingAndFinalValue.getSecond());
        }
      }
    }
    nativeOptionsParser.setStarlarkOptions(ImmutableMap.copyOf(parsedOptions));
    this.starlarkOptions.putAll(parsedOptions);
  }

  private void parseArg(
      String arg,
      Multimap<String, Pair<String, Target>> unparsedOptions,
      ExtendedEventHandler eventHandler)
      throws OptionsParsingException {
    int equalsAt = arg.indexOf('=');
    String name = equalsAt == -1 ? arg.substring(2) : arg.substring(2, equalsAt);
    if (name.trim().isEmpty()) {
      throw new OptionsParsingException("Invalid options syntax: " + arg, arg);
    }
    String value = equalsAt == -1 ? null : arg.substring(equalsAt + 1);

    if (value != null) {
      // --flag=value or -flag=value form
      Target buildSettingTarget = loadBuildSetting(name, eventHandler);
      // Use the canonical form to ensure we don't have
      // duplicate options getting into the starlark options map.
      unparsedOptions.put(
          buildSettingTarget.getLabel().getCanonicalForm(), new Pair<>(value, buildSettingTarget));
    } else {
      boolean booleanValue = true;
      // check --noflag form
      if (name.startsWith("no")) {
        booleanValue = false;
        name = name.substring(2);
      }
      Target buildSettingTarget = loadBuildSetting(name, eventHandler);
      BuildSetting current =
          buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting();
      if (current.getType().equals(BOOLEAN)) {
        // --boolean_flag or --noboolean_flag
        // Ditto w/r/t canonical form.
        unparsedOptions.put(
            buildSettingTarget.getLabel().getCanonicalForm(),
            new Pair<>(String.valueOf(booleanValue), buildSettingTarget));
      } else {
        if (!booleanValue) {
          // --no(non_boolean_flag)
          throw new OptionsParsingException(
              "Illegal use of 'no' prefix on non-boolean option: " + name, name);
        }
        throw new OptionsParsingException("Expected value after " + arg);
      }
    }
  }

  private Target loadBuildSetting(String targetToBuild, ExtendedEventHandler eventHandler)
      throws OptionsParsingException {
    if (buildSettings.containsKey(targetToBuild)) {
      return buildSettings.get(targetToBuild);
    }

    Target buildSetting;
    try {
      TargetPatternPhaseValue result =
          skyframeExecutor.loadTargetPatternsWithoutFilters(
              reporter,
              Collections.singletonList(targetToBuild),
              relativeWorkingDirectory,
              SkyframeExecutor.DEFAULT_THREAD_COUNT,
              /*keepGoing=*/ false);
      buildSetting =
          Iterables.getOnlyElement(
              result.getTargets(eventHandler, skyframeExecutor.getPackageManager()));
    } catch (InterruptedException | TargetParsingException e) {
      Thread.currentThread().interrupt();
      throw new OptionsParsingException(
          "Error loading option " + targetToBuild + ": " + e.getMessage(), targetToBuild, e);
    }
    Rule associatedRule = buildSetting.getAssociatedRule();
    if (associatedRule == null || associatedRule.getRuleClassObject().getBuildSetting() == null) {
      throw new OptionsParsingException("Unrecognized option: " + targetToBuild, targetToBuild);
    }
    buildSettings.put(targetToBuild, buildSetting);
    return buildSetting;
  }

  /**
   * Separates out any Starlark options from the given list
   *
   * <p>This method doesn't go through the trouble to actually load build setting targets and verify
   * they are build settings, it just assumes all strings that look like they could be build
   * settings, aka are formatted like a flag and can parse out to a proper label, are build
   * settings. Use actual parsing functions above to do full build setting verification.
   *
   * @param list List of strings from which to parse out starlark options
   * @return Returns a pair of string lists. The first item contains the list of starlark options
   *     that were removed; the second contains the remaining string from the original list.
   */
  public static Pair<ImmutableList<String>, ImmutableList<String>> removeStarlarkOptions(
      List<String> list) {
    ImmutableList.Builder<String> keep = ImmutableList.builder();
    ImmutableList.Builder<String> remove = ImmutableList.builder();
    for (String name : list) {
      // Check if the string is a flag and trim off "--" if so.
      if (!name.startsWith("--")) {
        keep.add(name);
        continue;
      }
      String potentialStarlarkFlag = name.substring(2);
      // Check if the string uses the "no" prefix for setting boolean flags to false, trim
      // off "no" if so.
      if (potentialStarlarkFlag.startsWith("no")) {
        potentialStarlarkFlag = potentialStarlarkFlag.substring(2);
      }
      // Check if the string contains a value, trim off the value if so.
      int equalsIdx = potentialStarlarkFlag.indexOf('=');
      if (equalsIdx > 0) {
        potentialStarlarkFlag = potentialStarlarkFlag.substring(0, equalsIdx);
      }
      // Check if we can properly parse the (potentially trimmed) string as a label. If so, count
      // as starlark flag, else count as regular residue.
      try {
        LabelValidator.validateAbsoluteLabel(potentialStarlarkFlag);
        remove.add(name);
      } catch (BadLabelException e) {
        keep.add(name);
      }
    }
    return Pair.of(remove.build(), keep.build());
  }

  @VisibleForTesting
  public static StarlarkOptionsParser newStarlarkOptionsParserForTesting(
      SkyframeExecutor skyframeExecutor,
      ExtendedEventHandler reporter,
      PathFragment relativeWorkingDirectory,
      OptionsParser nativeOptionsParser) {
    return new StarlarkOptionsParser(
        skyframeExecutor, relativeWorkingDirectory, reporter, nativeOptionsParser);
  }

  @VisibleForTesting
  public void setResidueForTesting(List<String> residue) {
    nativeOptionsParser.setResidue(residue, ImmutableList.of());
  }

  @VisibleForTesting
  public OptionsParser getNativeOptionsParserFortesting() {
    return nativeOptionsParser;
  }

  public boolean checkIfParsedOptionAllowsMultiple(String option) {
    BuildSetting setting = parsedBuildSettings.get(option);
    return setting.allowsMultiple() || setting.isRepeatableFlag();
  }

  public Type<?> getParsedOptionType(String option) {
    return parsedBuildSettings.get(option).getType();
  }

  /** Return a canoncalized list of the starlark options and values that this parser has parsed. */
  @SuppressWarnings("unchecked")
  public List<String> canonicalize() {
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    for (Map.Entry<String, Object> starlarkOption : starlarkOptions.entrySet()) {
      String starlarkOptionName = starlarkOption.getKey();
      Object starlarkOptionValue = starlarkOption.getValue();
      String starlarkOptionString = "--" + starlarkOptionName + "=";
      if (checkIfParsedOptionAllowsMultiple(starlarkOptionName)) {
        Preconditions.checkState(
            starlarkOption.getValue() instanceof List,
            "Found a starlark option value that isn't a list for an allow multiple option.");
        for (Object singleValue : (List) starlarkOptionValue) {
          result.add(starlarkOptionString + singleValue);
        }
      } else if (getParsedOptionType(starlarkOptionName).equals(Type.STRING_LIST)) {
        result.add(
            starlarkOptionString + String.join(",", ((Iterable<String>) starlarkOptionValue)));
      } else {
        result.add(starlarkOptionString + starlarkOptionValue);
      }
    }
    return result.build();
  }
}
