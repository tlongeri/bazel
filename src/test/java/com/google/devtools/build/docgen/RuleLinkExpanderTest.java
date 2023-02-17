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
package com.google.devtools.build.docgen;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RuleLinkExpander}. */
@RunWith(JUnit4.class)
public class RuleLinkExpanderTest {
  private RuleLinkExpander multiPageExpander;
  private RuleLinkExpander singlePageExpander;

  @Before public void setUp() {
    Map<String, String> index =
        ImmutableMap.<String, String>builder()
            .put("cc_library", "c-cpp")
            .put("cc_binary", "c-cpp")
            .put("java_binary", "java")
            .put("Fileset", "fileset")
            .put("proto_library", "protocol-buffer")
            .buildOrThrow();
    DocLinkMap linkMap =
        new DocLinkMap(
            "",
            ImmutableMap.of(
                "make-variables",
                "make-variables.html",
                "common-definitions",
                "common-definitions.html",
                "standalone",
                "standalone.html"));
    multiPageExpander = new RuleLinkExpander(index, false, linkMap);
    singlePageExpander = new RuleLinkExpander(index, true, linkMap);
  }

  private void checkExpandSingle(String docs, String expected) {
    assertThat(singlePageExpander.expand(docs)).isEqualTo(expected);
  }

  private void checkExpandMulti(String docs, String expected) {
    assertThat(multiPageExpander.expand(docs)).isEqualTo(expected);
  }

  @Test public void testRule() {
    checkExpandMulti(
        "<a href=\"${link java_binary}\">java_binary rule</a>",
        "<a href=\"java.html#java_binary\">java_binary rule</a>");
    checkExpandSingle(
        "<a href=\"${link java_binary}\">java_binary rule</a>",
        "<a href=\"#java_binary\">java_binary rule</a>");
  }

  @Test public void testRuleAndAttribute() {
    checkExpandMulti(
        "<a href=\"${link java_binary.runtime_deps}\">runtime_deps attribute</a>",
        "<a href=\"java.html#java_binary.runtime_deps\">runtime_deps attribute</a>");
    checkExpandSingle(
        "<a href=\"${link java_binary.runtime_deps}\">runtime_deps attribute</a>",
        "<a href=\"#java_binary.runtime_deps\">runtime_deps attribute</a>");
  }

  @Test public void testUpperCaseRule() {
    checkExpandMulti(
        "<a href=\"${link Fileset.entries}\">entries</a>",
        "<a href=\"fileset.html#Fileset.entries\">entries</a>");
    checkExpandSingle(
        "<a href=\"${link Fileset.entries}\">entries</a>",
        "<a href=\"#Fileset.entries\">entries</a>");
  }

  @Test public void testRuleExamples() {
    checkExpandMulti(
        "<a href=\"${link cc_binary_examples}\">examples</a>",
        "<a href=\"c-cpp.html#cc_binary_examples\">examples</a>");
    checkExpandSingle(
        "<a href=\"${link cc_binary_examples}\">examples</a>",
        "<a href=\"#cc_binary_examples\">examples</a>");
  }

  @Test public void testRuleArgs() {
    checkExpandMulti(
        "<a href=\"${link cc_binary_args}\">args</a>",
        "<a href=\"c-cpp.html#cc_binary_args\">args</a>");
    checkExpandSingle(
        "<a href=\"${link cc_binary_args}\">args</a>",
        "<a href=\"#cc_binary_args\">args</a>");
  }

  @Test public void testRuleImplicitOutputsj() {
    checkExpandMulti(
        "<a href=\"${link cc_binary_implicit_outputs}\">args</a>",
        "<a href=\"c-cpp.html#cc_binary_implicit_outputs\">args</a>");
    checkExpandSingle(
        "<a href=\"${link cc_binary_implicit_outputs}\">args</a>",
        "<a href=\"#cc_binary_implicit_outputs\">args</a>");
  }

  @Test
  public void testStaticPageRef_pageReplacedBySinglePageBE() {
    checkExpandMulti(
        "<a href=\"${link common-definitions}\">Common Definitions</a>",
        "<a href=\"common-definitions.html\">Common Definitions</a>");
    checkExpandSingle(
        "<a href=\"${link common-definitions}\">Common Definitions</a>",
        "<a href=\"#common-definitions\">Common Definitions</a>");
  }

  @Test
  public void testStaticPageRef_separatePage() {
    checkExpandMulti(
        "<a href=\"${link standalone}\">standalone</a>",
        "<a href=\"standalone.html\">standalone</a>");
    checkExpandSingle(
        "<a href=\"${link standalone}\">standalone</a>",
        "<a href=\"standalone.html\">standalone</a>");
  }

  @Test
  public void testRefNotFound() {
    String docs = "<a href=\"${link foo.bar}\">bar</a>";
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          multiPageExpander.expand(docs);
        });
  }

  @Test
  public void testIncorrectStaticPageHeadingLink() {
    String docs = "<a href=\"${link common-definitions.label-expansion}\">Label Expansion</a>";
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          multiPageExpander.expand(docs);
        });
  }

  @Test public void testRuleHeadingLink() {
    checkExpandMulti(
        "<a href=\"${link cc_library#alwayslink_lib_example}\">examples</a>",
        "<a href=\"c-cpp.html#alwayslink_lib_example\">examples</a>");
    checkExpandSingle(
        "<a href=\"${link cc_library#alwayslink_lib_example}\">examples</a>",
        "<a href=\"#alwayslink_lib_example\">examples</a>");
  }

  @Test
  public void testStaticPageHeadingLink_pageReplacedBySinglePageBE() {
    checkExpandMulti(
        "<a href=\"${link make-variables#predefined_variables.genrule.cmd}\">genrule cmd</a>",
        "<a href=\"make-variables.html#predefined_variables.genrule.cmd\">genrule cmd</a>");
    checkExpandSingle(
        "<a href=\"${link make-variables#predefined_variables.genrule.cmd}\">genrule cmd</a>",
        "<a href=\"#predefined_variables.genrule.cmd\">genrule cmd</a>");
  }

  @Test
  public void testStaticPageHeadingLink_separatePage() {
    checkExpandMulti(
        "<a href=\"${link standalone#foobar}\">standalone</a>",
        "<a href=\"standalone.html#foobar\">standalone</a>");
    checkExpandSingle(
        "<a href=\"${link standalone#foobar}\">standalone</a>",
        "<a href=\"standalone.html#foobar\">standalone</a>");
  }

  @Test public void testExpandRef() {
    assertThat(multiPageExpander.expandRef("java_binary.runtime_deps"))
        .isEqualTo("java.html#java_binary.runtime_deps");
    assertThat(singlePageExpander.expandRef("java_binary.runtime_deps"))
        .isEqualTo("#java_binary.runtime_deps");
  }

  @Test
  public void testExcplicitBuildEncyclopediaRoot() {
    DocLinkMap linkMap = new DocLinkMap("/be_root", ImmutableMap.of());
    RuleLinkExpander expander =
        new RuleLinkExpander(ImmutableMap.of("java_binary", "java"), false, linkMap);

    assertThat(expander.expand("<a href=\"${link java_binary}\">java_binary rule</a>"))
        .isEqualTo("<a href=\"/be_root/java.html#java_binary\">java_binary rule</a>");
  }
}
