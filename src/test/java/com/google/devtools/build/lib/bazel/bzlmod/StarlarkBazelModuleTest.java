// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.buildTag;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createTagClass;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.util.FileTypeSet;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.syntax.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link StarlarkBazelModule}. */
@RunWith(JUnit4.class)
public class StarlarkBazelModuleTest {

  /** A builder for ModuleExtensionUsage that sets all the mandatory but irrelevant fields. */
  private static ModuleExtensionUsage.Builder getBaseUsageBuilder() {
    return ModuleExtensionUsage.builder()
        .setExtensionBzlFile("//:rje.bzl")
        .setExtensionName("maven")
        .setLocation(Location.BUILTIN)
        .setImports(ImmutableBiMap.of());
  }

  /** A builder for ModuleExtension that sets all the mandatory but irrelevant fields. */
  private static ModuleExtension.Builder getBaseExtensionBuilder() {
    return ModuleExtension.builder()
        .setName("maven")
        .setDoc("")
        .setLocation(Location.BUILTIN)
        .setDefinitionEnvironmentLabel(Label.parseAbsoluteUnchecked("//:rje.bzl"))
        .setImplementation(() -> "maven");
  }

  @Test
  public void basic() throws Exception {
    ModuleExtensionUsage usage =
        getBaseUsageBuilder()
            .addTag(buildTag("dep").addAttr("coord", "junit").build())
            .addTag(buildTag("dep").addAttr("coord", "guava").build())
            .addTag(
                buildTag("pom")
                    .addAttr("pom_xmls", StarlarkList.immutableOf("//:pom.xml", "@bar//:pom.xml"))
                    .build())
            .build();
    ModuleExtension extension =
        getBaseExtensionBuilder()
            .setTagClasses(
                ImmutableMap.of(
                    "dep", createTagClass(attr("coord", Type.STRING).build()),
                    "repos", createTagClass(attr("repos", Type.STRING_LIST).build()),
                    "pom",
                        createTagClass(
                            attr("pom_xmls", BuildType.LABEL_LIST)
                                .allowedFileTypes(FileTypeSet.ANY_FILE)
                                .build())))
            .build();
    Module module =
        Module.builder()
            .setName("foo")
            .setVersion(Version.parse("1.0"))
            .setKey(createModuleKey("foo", ""))
            .addDep("bar", createModuleKey("bar", "2.0"))
            .build();
    AbridgedModule abridgedModule = AbridgedModule.from(module);

    StarlarkBazelModule moduleProxy =
        StarlarkBazelModule.create(
            abridgedModule, extension, module.getRepoMappingWithBazelDepsOnly(), usage);

    assertThat(moduleProxy.getName()).isEqualTo("foo");
    assertThat(moduleProxy.getVersion()).isEqualTo("1.0");
    assertThat(moduleProxy.getTags().getFieldNames()).containsExactly("dep", "repos", "pom");

    // We have 2 "dep" tags...
    @SuppressWarnings("unchecked")
    StarlarkList<TypeCheckedTag> depTags =
        (StarlarkList<TypeCheckedTag>) moduleProxy.getTags().getValue("dep");
    assertThat(depTags.size()).isEqualTo(2);
    assertThat(depTags.get(0).getValue("coord")).isEqualTo("junit");
    assertThat(depTags.get(1).getValue("coord")).isEqualTo("guava");

    // ... zero "repos" tags...
    assertThat(moduleProxy.getTags().getValue("repos")).isEqualTo(StarlarkList.empty());

    // ... and 1 "pom" tag.
    @SuppressWarnings("unchecked")
    StarlarkList<TypeCheckedTag> pomTags =
        (StarlarkList<TypeCheckedTag>) moduleProxy.getTags().getValue("pom");
    assertThat(pomTags.size()).isEqualTo(1);
    assertThat(pomTags.get(0).getValue("pom_xmls"))
        .isEqualTo(
            StarlarkList.immutableOf(
                Label.parseAbsoluteUnchecked("@foo.override//:pom.xml"),
                Label.parseAbsoluteUnchecked("@bar.2.0//:pom.xml")));
  }

  @Test
  public void unknownTagClass() throws Exception {
    ModuleExtensionUsage usage = getBaseUsageBuilder().addTag(buildTag("blep").build()).build();
    ModuleExtension extension =
        getBaseExtensionBuilder().setTagClasses(ImmutableMap.of("dep", createTagClass())).build();
    Module module =
        Module.builder()
            .setName("foo")
            .setVersion(Version.parse("1.0"))
            .setKey(createModuleKey("foo", ""))
            .build();
    AbridgedModule abridgedModule = AbridgedModule.from(module);

    ExternalDepsException e =
        assertThrows(
            ExternalDepsException.class,
            () ->
                StarlarkBazelModule.create(
                    abridgedModule, extension, module.getRepoMappingWithBazelDepsOnly(), usage));
    assertThat(e).hasMessageThat().contains("does not have a tag class named blep");
  }
}
