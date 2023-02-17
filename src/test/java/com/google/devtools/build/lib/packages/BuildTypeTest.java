// Copyright 2015 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.packages.BuildType.Selector;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkInt;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test of type-conversions for build-specific types. */
@RunWith(JUnit4.class)
public final class BuildTypeTest {

  private final LabelConverter labelConverter =
      new LabelConverter(
          PackageIdentifier.createInMainRepo("quux"), RepositoryMapping.ALWAYS_FALLBACK);

  @Test
  public void testKeepsDictOrdering() throws Exception {
    Map<Object, String> input = new ImmutableMap.Builder<Object, String>()
        .put("c", "//c")
        .put("b", "//b")
        .put("a", "//a")
        .put("f", "//f")
        .put("e", "//e")
        .put("d", "//d")
        .build();

    assertThat(BuildType.LABEL_DICT_UNARY.convert(input, null, labelConverter).keySet())
        .containsExactly("c", "b", "a", "f", "e", "d")
        .inOrder();
  }

  @Test
  public void testLabelKeyedStringDictConvertsToMapFromLabelToString() throws Exception {
    Map<Object, String> input =
        new ImmutableMap.Builder<Object, String>()
            .put("//absolute:label", "absolute value")
            .put(":relative", "theory of relativity")
            .put("nocolon", "colonial times")
            .put("//current/package:explicit", "explicit content")
            .put(Label.parseCanonical("//i/was/already/a/label"), "and that's okay")
            .build();
    LabelConverter converter =
        new LabelConverter(
            PackageIdentifier.parse("//current/package"), RepositoryMapping.ALWAYS_FALLBACK);

    Map<Label, String> expected =
        new ImmutableMap.Builder<Label, String>()
            .put(Label.parseCanonical("//absolute:label"), "absolute value")
            .put(Label.parseCanonical("//current/package:relative"), "theory of relativity")
            .put(Label.parseCanonical("//current/package:nocolon"), "colonial times")
            .put(Label.parseCanonical("//current/package:explicit"), "explicit content")
            .put(Label.parseCanonical("//i/was/already/a/label"), "and that's okay")
            .build();

    assertThat(BuildType.LABEL_KEYED_STRING_DICT.convert(input, null, converter))
        .containsExactlyEntriesIn(expected);
  }

  @Test
  public void testLabelKeyedStringDictConvertingStringShouldFail() throws Exception {
    ConversionException expected =
        assertThrows(
            ConversionException.class,
            () ->
                BuildType.LABEL_KEYED_STRING_DICT.convert(
                    "//actually/a:label", null, labelConverter));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "expected value of type 'dict(label, string)', "
                + "but got \"//actually/a:label\" (string)");
  }

  @Test
  public void testLabelKeyedStringDictConvertingListShouldFail() throws Exception {
    ConversionException expected =
        assertThrows(
            ConversionException.class,
            () ->
                BuildType.LABEL_KEYED_STRING_DICT.convert(
                    ImmutableList.of("//actually/a:label"), null, labelConverter));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "expected value of type 'dict(label, string)', "
                + "but got [\"//actually/a:label\"] (List)");
  }

  @Test
  public void testLabelKeyedStringDictConvertingMapWithNonStringKeyShouldFail() {
    ConversionException expected =
        assertThrows(
            ConversionException.class,
            () ->
                BuildType.LABEL_KEYED_STRING_DICT.convert(
                    ImmutableMap.of(StarlarkInt.of(1), "OK"), null, labelConverter));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo("expected value of type 'string' for dict key element, but got 1 (int)");
  }

  @Test
  public void testLabelKeyedStringDictConvertingMapWithNonStringValueShouldFail() {
    ConversionException expected =
        assertThrows(
            ConversionException.class,
            () ->
                BuildType.LABEL_KEYED_STRING_DICT.convert(
                    ImmutableMap.of("//actually/a:label", StarlarkInt.of(3)),
                    null,
                    labelConverter));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo("expected value of type 'string' for dict value element, but got 3 (int)");
  }

  @Test
  public void testLabelKeyedStringDictConvertingMapWithInvalidLabelKeyShouldFail() {
    ConversionException expected =
        assertThrows(
            ConversionException.class,
            () ->
                BuildType.LABEL_KEYED_STRING_DICT.convert(
                    ImmutableMap.of("//uplevel/references/are:../../forbidden", "OK"),
                    null,
                    labelConverter));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "invalid label '//uplevel/references/are:../../forbidden' in "
                + "dict key element: invalid target name '../../forbidden': "
                + "target names may not contain up-level references '..'");
  }

  @Test
  public void testLabelKeyedStringDictConvertingMapWithMultipleEquivalentKeysShouldFail()
      throws Exception {
    LabelConverter converter =
        new LabelConverter(
            PackageIdentifier.parse("//current/package"), RepositoryMapping.ALWAYS_FALLBACK);
    Map<String, String> input = new ImmutableMap.Builder<String, String>()
        .put(":reference", "value1")
        .put("//current/package:reference", "value2")
        .build();
    ConversionException expected =
        assertThrows(
            ConversionException.class,
            () -> BuildType.LABEL_KEYED_STRING_DICT.convert(input, null, converter));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "duplicate labels: //current/package:reference "
                + "(as [\":reference\", \"//current/package:reference\"])");
  }

  @Test
  public void testLabelKeyedStringDictConvertingMapWithMultipleSetsOfEquivalentKeysShouldFail()
      throws Exception {
    LabelConverter converter =
        new LabelConverter(
            PackageIdentifier.parse("//current/rule"), RepositoryMapping.ALWAYS_FALLBACK);
    Map<String, String> input = new ImmutableMap.Builder<String, String>()
        .put(":rule", "first set")
        .put("//current/rule:rule", "also first set")
        .put("//other/package:package", "interrupting rule")
        .put("//other/package", "interrupting rule's friend")
        .put("//current/rule", "part of first set but non-contiguous in iteration order")
        .put("//not/involved/in/any:collisions", "same value")
        .put("//also/not/involved/in/any:collisions", "same value")
        .build();
    ConversionException expected =
        assertThrows(
            ConversionException.class,
            () -> BuildType.LABEL_KEYED_STRING_DICT.convert(input, null, converter));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "duplicate labels: //current/rule:rule "
                + "(as [\":rule\", \"//current/rule:rule\", \"//current/rule\"]), "
                + "//other/package:package "
                + "(as [\"//other/package:package\", \"//other/package\"])");
  }

  @Test
  public void testLabelKeyedStringDictErrorConvertingMapWithMultipleEquivalentKeysIncludesContext()
      throws Exception {
    LabelConverter converter =
        new LabelConverter(
            PackageIdentifier.parse("//current/package"), RepositoryMapping.ALWAYS_FALLBACK);
    Map<String, String> input = new ImmutableMap.Builder<String, String>()
        .put(":reference", "value1")
        .put("//current/package:reference", "value2")
        .build();
    ConversionException expected =
        assertThrows(
            ConversionException.class,
            () -> BuildType.LABEL_KEYED_STRING_DICT.convert(input, "flag map", converter));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "duplicate labels in flag map: //current/package:reference "
                + "(as [\":reference\", \"//current/package:reference\"])");
  }

  @Test
  public void testLabelKeyedStringDictCollectLabels() throws Exception {
    Map<Label, String> input =
        new ImmutableMap.Builder<Label, String>()
            .put(Label.parseCanonical("//absolute:label"), "absolute value")
            .put(Label.parseCanonical("//current/package:relative"), "theory of relativity")
            .put(Label.parseCanonical("//current/package:nocolon"), "colonial times")
            .put(Label.parseCanonical("//current/package:explicit"), "explicit content")
            .put(Label.parseCanonical("//i/was/already/a/label"), "and that's okay")
            .build();

    ImmutableList<Label> expected =
        ImmutableList.of(
            Label.parseCanonical("//absolute:label"),
            Label.parseCanonical("//current/package:relative"),
            Label.parseCanonical("//current/package:nocolon"),
            Label.parseCanonical("//current/package:explicit"),
            Label.parseCanonical("//i/was/already/a/label"));

    assertThat(collectLabels(BuildType.LABEL_KEYED_STRING_DICT, input))
        .containsExactlyElementsIn(expected);
  }


  /**
   * Tests basic {@link Selector} functionality.
   */
  @Test
  public void testSelector() throws Exception {
    ImmutableMap<String, String> input = ImmutableMap.of(
        "//conditions:a", "//a:a",
        "//conditions:b", "//b:b",
        Selector.DEFAULT_CONDITION_KEY, "//d:d");
    Selector<Label> selector = new Selector<>(input, null, labelConverter, BuildType.LABEL);
    assertThat(selector.getOriginalType()).isEqualTo(BuildType.LABEL);

    Map<Label, Label> expectedMap =
        ImmutableMap.of(
            Label.parseCanonical("//conditions:a"),
            Label.create("@//a", "a"),
            Label.parseCanonical("//conditions:b"),
            Label.create("@//b", "b"),
            Label.parseCanonical(Selector.DEFAULT_CONDITION_KEY),
            Label.create("@//d", "d"));
    assertThat(selector.getEntries().entrySet()).containsExactlyElementsIn(expectedMap.entrySet());
  }

  /**
   * Tests that creating a {@link Selector} over a mismatching native type triggers an
   * exception.
   */
  @Test
  public void testSelectorWrongType() throws Exception {
    ImmutableMap<String, String> input = ImmutableMap.of(
        "//conditions:a", "not a/../label", "//conditions:b", "also not a/../label",
        BuildType.Selector.DEFAULT_CONDITION_KEY, "whatever");
    ConversionException e =
        assertThrows(
            ConversionException.class,
            () -> new Selector<>(input, null, labelConverter, BuildType.LABEL));
    assertThat(e).hasMessageThat().contains("invalid label 'not a/../label'");
  }

  /** Tests that non-label selector keys trigger an exception. */
  @Test
  public void testSelectorKeyIsNotALabel() {
    ImmutableMap<String, String> input = ImmutableMap.of(
        "not a/../label", "//a:a",
        BuildType.Selector.DEFAULT_CONDITION_KEY, "whatever");
    ConversionException e =
        assertThrows(
            ConversionException.class,
            () -> new Selector<>(input, null, labelConverter, BuildType.LABEL));
    assertThat(e).hasMessageThat().contains("invalid label 'not a/../label'");
  }

  /**
   * Tests that {@link Selector} correctly references its default value.
   */
  @Test
  public void testSelectorDefault() throws Exception {
    ImmutableMap<String, String> input = ImmutableMap.of(
        "//conditions:a", "//a:a",
        "//conditions:b", "//b:b",
        BuildType.Selector.DEFAULT_CONDITION_KEY, "//d:d");
    assertThat(new Selector<>(input, null, labelConverter, BuildType.LABEL).getDefault())
        .isEqualTo(Label.create("@//d", "d"));
  }

  @Test
  public void testSelectorList() throws Exception {
    Object selector1 = new SelectorValue(ImmutableMap.of("//conditions:a",
        ImmutableList.of("//a:a"), "//conditions:b", ImmutableList.of("//b:b")), "");
    Object selector2 = new SelectorValue(ImmutableMap.of("//conditions:c",
        ImmutableList.of("//c:c"), "//conditions:d", ImmutableList.of("//d:d")), "");
    BuildType.SelectorList<List<Label>> selectorList =
        new BuildType.SelectorList<>(
            ImmutableList.of(selector1, selector2), null, labelConverter, BuildType.LABEL_LIST);

    assertThat(selectorList.getOriginalType()).isEqualTo(BuildType.LABEL_LIST);
    assertThat(selectorList.getKeyLabels())
        .containsExactly(
            Label.parseCanonical("//conditions:a"),
            Label.parseCanonical("//conditions:b"),
            Label.parseCanonical("//conditions:c"),
            Label.parseCanonical("//conditions:d"));

    List<Selector<List<Label>>> selectors = selectorList.getSelectors();
    assertThat(selectors.get(0).getEntries().entrySet())
        .containsExactlyElementsIn(
            ImmutableMap.of(
                    Label.parseCanonical("//conditions:a"),
                    ImmutableList.of(Label.create("@//a", "a")),
                    Label.parseCanonical("//conditions:b"),
                    ImmutableList.of(Label.create("@//b", "b")))
                .entrySet());
    assertThat(selectors.get(1).getEntries().entrySet())
        .containsExactlyElementsIn(
            ImmutableMap.of(
                    Label.parseCanonical("//conditions:c"),
                    ImmutableList.of(Label.create("@//c", "c")),
                    Label.parseCanonical("//conditions:d"),
                    ImmutableList.of(Label.create("@//d", "d")))
                .entrySet());
  }

  @Test
  public void testSelectorListMixedTypes() throws Exception {
    Object selector1 =
        new SelectorValue(ImmutableMap.of("//conditions:a", ImmutableList.of("//a:a")), "");
    Object selector2 =
        new SelectorValue(ImmutableMap.of("//conditions:b", "//b:b"), "");
    ConversionException e =
        assertThrows(
            ConversionException.class,
            () ->
                new BuildType.SelectorList<>(
                    ImmutableList.of(selector1, selector2),
                    null,
                    labelConverter,
                    BuildType.LABEL_LIST));
    assertThat(e).hasMessageThat().contains("expected value of type 'list(label)'");
  }

  @Test
  public void testSelectorList_concatenate_selectorList() throws Exception {
    SelectorList selectorList =
        SelectorList.of(
            new SelectorValue(ImmutableMap.of("//conditions:a", ImmutableList.of("//a:a")), ""));
    List<String> list = ImmutableList.of("//a:a", "//b:b");

    // Creating a SelectorList from a SelectorList and a list should work properly.
    SelectorList result = SelectorList.concat(selectorList, list);
    assertThat(result).isNotNull();
    assertThat(result.getType()).isAssignableTo(List.class);
  }

  @Test
  public void testSelectorList_concatenate_selectorValue() throws Exception {
    SelectorValue selectorValue =
        new SelectorValue(ImmutableMap.of("//conditions:a", ImmutableList.of("//a:a")), "");
    List<String> list = ImmutableList.of("//a:a", "//b:b");

    // Creating a SelectorList from a SelectorValue and a list should work properly.
    SelectorList result = SelectorList.concat(selectorValue, list);
    assertThat(result).isNotNull();
    assertThat(result.getType()).isAssignableTo(List.class);
  }

  @Test
  public void testSelectorList_concatenate_differentListTypes() throws Exception {
    List<String> list = ImmutableList.of("//a:a", "//b:b");
    List<String> arrayList = new ArrayList<>();
    arrayList.add("//a:a");

    // Creating a SelectorList from two lists of different types should work properly.
    SelectorList result = SelectorList.concat(list, arrayList);
    assertThat(result).isNotNull();
    assertThat(result.getType()).isAssignableTo(List.class);
  }

  @Test
  public void testSelectorList_concatenate_invalidType() throws Exception {
    List<String> list = ImmutableList.of("//a:a", "//b:b");

    // Creating a SelectorList from a list and a non-list should fail.
    assertThrows(EvalException.class, () -> SelectorList.concat(list, "A string"));
  }

  /**
   * Tests that {@link BuildType#selectableConvert} returns either the native type or a selector on
   * that type, in accordance with the provided input.
   */
  @SuppressWarnings({"unchecked", "TruthIncompatibleType"})
  @Test
  public void testSelectableConvert() throws Exception {
    Object nativeInput = Arrays.asList("//a:a1", "//a:a2");
    Object selectableInput =
        SelectorList.of(new SelectorValue(ImmutableMap.of(
            "//conditions:a", nativeInput,
            BuildType.Selector.DEFAULT_CONDITION_KEY, nativeInput), ""));
    List<Label> expectedLabels =
        ImmutableList.of(Label.create("@//a", "a1"), Label.create("@//a", "a2"));

    // Conversion to direct type:
    Object converted =
        BuildType.selectableConvert(BuildType.LABEL_LIST, nativeInput, null, labelConverter);
    assertThat(converted instanceof List<?>).isTrue();
    assertThat((List<Label>) converted).containsExactlyElementsIn(expectedLabels);

    // Conversion to selectable type:
    converted =
        BuildType.selectableConvert(BuildType.LABEL_LIST, selectableInput, null, labelConverter);
    BuildType.SelectorList<?> selectorList = (BuildType.SelectorList<?>) converted;
    assertThat(((Selector<Label>) selectorList.getSelectors().get(0)).getEntries().entrySet())
        .containsExactlyElementsIn(
            /* expected: Entry<Label, Label>, actual: Entry<Label, List<Label>> */ ImmutableMap.of(
                    Label.parseCanonical("//conditions:a"),
                    expectedLabels,
                    Label.parseCanonical(Selector.DEFAULT_CONDITION_KEY),
                    expectedLabels)
                .entrySet());
  }

  /**
   * Tests that {@link com.google.devtools.build.lib.packages.Type#convert} fails on selector
   * inputs.
   */
  @Test
  public void testConvertDoesNotAcceptSelectables() throws Exception {
    Object selectableInput = SelectorList.of(
        new SelectorValue(
            ImmutableMap.of("//conditions:a", Arrays.asList("//a:a1", "//a:a2")), ""));
    ConversionException e =
        assertThrows(
            ConversionException.class,
            () -> BuildType.LABEL_LIST.convert(selectableInput, null, labelConverter));
    assertThat(e).hasMessageThat().contains("expected value of type 'list(label)'");
  }

  /**
   * Tests for "reserved" key labels (i.e. not intended to map to actual targets).
   */
  @Test
  public void testReservedKeyLabels() throws Exception {
    assertThat(BuildType.Selector.isReservedLabel(Label.parseCanonical("//condition:a"))).isFalse();
    assertThat(
            BuildType.Selector.isReservedLabel(
                Label.parseCanonical(Selector.DEFAULT_CONDITION_KEY)))
        .isTrue();
  }

  @Test
  public void testUnconditionalSelects() throws Exception {
    assertThat(
            new Selector<>(
                    ImmutableMap.of("//conditions:a", "//a:a"),
                    null,
                    labelConverter,
                    BuildType.LABEL)
                .isUnconditional())
        .isFalse();
    assertThat(
            new Selector<>(
                    ImmutableMap.of(
                        "//conditions:a",
                        "//a:a",
                        BuildType.Selector.DEFAULT_CONDITION_KEY,
                        "//b:b"),
                    null,
                    labelConverter,
                    BuildType.LABEL)
                .isUnconditional())
        .isFalse();
    assertThat(
            new Selector<>(
                    ImmutableMap.of(BuildType.Selector.DEFAULT_CONDITION_KEY, "//b:b"),
                    null,
                    labelConverter,
                    BuildType.LABEL)
                .isUnconditional())
        .isTrue();
  }

  private static <T> ImmutableList<Label> collectLabels(Type<T> type, T value) {
    ImmutableList.Builder<Label> result = ImmutableList.builder();
    type.visitLabels((label, dummy) -> result.add(label), value, /*context=*/ null);
    return result.build();
  }
}
