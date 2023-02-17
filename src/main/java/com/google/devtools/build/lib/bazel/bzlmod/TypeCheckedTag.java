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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.collect.ImmutableCollection;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType.LabelConversionContext;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Structure;

/**
 * A {@link Tag} whose attribute values have been type-checked against the attribute schema define
 * in the {@link TagClass}.
 */
@StarlarkBuiltin(name = "bazel_module_tag", doc = "TODO")
public class TypeCheckedTag implements Structure {
  private final TagClass tagClass;
  private final Object[] attrValues;

  private TypeCheckedTag(TagClass tagClass, Object[] attrValues) {
    this.tagClass = tagClass;
    this.attrValues = attrValues;
  }

  /** Creates a {@link TypeCheckedTag}. */
  public static TypeCheckedTag create(
      TagClass tagClass, Tag tag, LabelConversionContext labelConversionContext)
      throws ExternalDepsException {
    Object[] attrValues = new Object[tagClass.getAttributes().size()];
    for (Map.Entry<String, Object> attrValue : tag.getAttributeValues().entrySet()) {
      Integer attrIndex = tagClass.getAttributeIndices().get(attrValue.getKey());
      if (attrIndex == null) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, unknown attribute %s provided",
            tag.getLocation(),
            attrValue.getKey());
      }
      Attribute attr = tagClass.getAttributes().get(attrIndex);
      Object nativeValue;
      try {
        nativeValue =
            attr.getType()
                .convert(attrValue.getValue(), attr.getPublicName(), labelConversionContext);
      } catch (ConversionException e) {
        throw ExternalDepsException.withCauseAndMessage(
            Code.BAD_MODULE,
            e,
            "in tag at %s, error converting value for attribute %s",
            tag.getLocation(),
            attr.getPublicName());
      }

      // Check that the value is actually allowed.
      if (attr.checkAllowedValues() && !attr.getAllowedValues().apply(nativeValue)) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, the value for attribute %s %s",
            tag.getLocation(),
            attr.getPublicName(),
            attr.getAllowedValues().getErrorReason(nativeValue));
      }

      attrValues[attrIndex] = Attribute.valueToStarlark(nativeValue);
    }

    // Check that all mandatory attributes have been specified, and fill in default values.
    for (int i = 0; i < attrValues.length; i++) {
      Attribute attr = tagClass.getAttributes().get(i);
      if (attr.isMandatory() && attrValues[i] == null) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, mandatory attribute %s isn't being specified",
            tag.getLocation(),
            attr.getPublicName());
      }
      if (attrValues[i] == null) {
        attrValues[i] = Attribute.valueToStarlark(attr.getDefaultValueUnchecked());
      }
    }
    return new TypeCheckedTag(tagClass, attrValues);
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Nullable
  @Override
  public Object getValue(String name) throws EvalException {
    Integer attrIndex = tagClass.getAttributeIndices().get(name);
    if (attrIndex == null) {
      return null;
    }
    return attrValues[attrIndex];
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return tagClass.getAttributeIndices().keySet();
  }

  @Nullable
  @Override
  public String getErrorMessageForUnknownField(String field) {
    return "unknown attribute " + field;
  }
}
