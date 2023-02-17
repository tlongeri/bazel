# Lint as: python3
# pylint: disable=g-direct-third-party-import
# Copyright 2022 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Module for fixing links in Bazel release docs."""
import os
import re

_BASE_URL = "https://bazel.build"

# We need to use regular expressions here since HTML can be embedded in
# Markdown and Yaml, thus breaking XML parsers. Moreover, our use case is
# simple, so regex should work (tm).
_HTML_LINK_PATTERN = re.compile(
    r"((href|src)\s*=\s*[\"']({})?)/".format(_BASE_URL))


def _fix_html_links(content, version):
  return _HTML_LINK_PATTERN.sub(r"\1/versions/{}/".format(version), content)


def _fix_html_metadata(content, version):
  return content.replace("value=\"/_book.yaml\"",
                         "value=\"/versions/{}/_book.yaml\"".format(version))


_MD_LINK_OR_IMAGE_PATTERN = re.compile(
    r"(\!?\[.*?\]\(({})?)(/.*?)\)".format(_BASE_URL))


def _fix_md_links_and_images(content, version):
  return _MD_LINK_OR_IMAGE_PATTERN.sub(r"\1/versions/{}\3)".format(version),
                                       content)


_MD_METADATA_PATTERN = re.compile(r"^(Book: )(/.+)$", re.MULTILINE)


def _fix_md_metadata(content, version):
  return _MD_METADATA_PATTERN.sub(r"\1/versions/{}\2".format(version), content)


_YAML_PATH_PATTERN = re.compile(r"((book_|image_)?path: ['\"]?)(/.*?)(['\"]?)$",
                                re.MULTILINE)

_YAML_IGNORE_LIST = frozenset(
    ["/", "/_project.yaml", "/versions/", "/versions/_toc.yaml"])


def _fix_yaml_paths(content, version):

  def sub(m):
    prefix, path, suffix = m.group(1, 3, 4)
    if path in _YAML_IGNORE_LIST:
      return m.group(0)

    return "{}/versions/{}{}{}".format(prefix, version, path, suffix)

  return _YAML_PATH_PATTERN.sub(sub, content)


_PURE_HTML_FIXES = [_fix_html_links, _fix_html_metadata]
_PURE_MD_FIXES = [_fix_md_links_and_images, _fix_md_metadata]
_PURE_YAML_FIXES = [_fix_yaml_paths]

_FIXES = {
    ".html": _PURE_HTML_FIXES,
    ".md": _PURE_MD_FIXES + _PURE_HTML_FIXES,
    ".yaml": _PURE_YAML_FIXES + _PURE_HTML_FIXES,
}


def _get_fixes(path):
  _, ext = os.path.splitext(path)
  return _FIXES.get(ext)


def can_rewrite(path):
  """Returns whether links in this file can/should be rewritten.

  Args:
      path: Path of the file in question.

  Returns:
    True if the file can/should be rewritten.
  """
  return bool(_get_fixes(path))


def rewrite_links(path, content, version):
  """Rewrites links in the given file to point to versioned docs.

  Args:
    path: Absolute path of the file to be rewritten.
    content: Content of said file, as text.
    version: Version of the Bazel release that is being built.

  Returns:
    The rewritten content of the file, as text. Equal to `content`
    if no links had to be rewritten.
  """
  fixes = _get_fixes(path)
  if not fixes:
    raise ValueError(
        "Cannot rewrite {} due to unsupported file type.".format(path))

  new_content = content
  for f in fixes:
    new_content = f(new_content, version)

  return new_content
