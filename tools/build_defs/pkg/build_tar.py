# Copyright 2015 The Bazel Authors. All rights reserved.
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
"""This tool build tar files from a list of inputs."""

import os

# Do not edit this line. Copybara replaces it with PY2 migration helper.
from absl import app
from absl import flags

from tools.build_defs.pkg import archive

flags.DEFINE_string('output', None, 'The output file, mandatory')
flags.mark_flag_as_required('output')

flags.DEFINE_multi_string('file', [], 'A file to add to the layer')

flags.DEFINE_string('mode', None,
                    'Force the mode on the added files (in octal).')

flags.DEFINE_multi_string('tar', [], 'A tar file to add to the layer')

flags.DEFINE_string('directory', None,
                    'Directory in which to store the file inside the layer')

flags.DEFINE_string('compression', None,
                    'Compression (`gz` or `bz2`), default is none.')

flags.DEFINE_string(
    'owner', '0.0', 'Specify the numeric default owner of all files,'
    ' e.g., 0.0')

flags.DEFINE_string('owner_name', None,
                    'Specify the owner name of all files, e.g. root.root.')

flags.DEFINE_string('root_directory', './',
                    'Default root directory is named "."')

FLAGS = flags.FLAGS


class TarFile(object):
  """A class to generates a TAR file."""

  class DebError(Exception):
    pass

  def __init__(self, output, directory, compression, root_directory):
    self.directory = directory
    self.output = output
    self.compression = compression
    self.root_directory = root_directory

  def __enter__(self):
    self.tarfile = archive.TarFileWriter(
        self.output,
        self.compression,
        self.root_directory)
    return self

  def __exit__(self, t, v, traceback):
    self.tarfile.close()

  def add_file(self, f, destfile, mode=None, ids=None, names=None):
    """Add a file to the tar file.

    Args:
       f: the file to add to the layer
       destfile: the name of the file in the layer
       mode: force to set the specified mode, by default the value from the
         source is taken.
       ids: (uid, gid) for the file to set ownership
       names: (username, groupname) for the file to set ownership. `f` will be
         copied to `self.directory/destfile` in the layer.
    """
    dest = destfile.lstrip('/')  # Remove leading slashes
    if self.directory and self.directory != '/':
      dest = self.directory.lstrip('/') + '/' + dest
    # If mode is unspecified, derive the mode from the file's mode.
    if mode is None:
      mode = 0o755 if os.access(f, os.X_OK) else 0o644
    if ids is None:
      ids = (0, 0)
    if names is None:
      names = ('', '')
    dest = os.path.normpath(dest)
    self.tarfile.add_file(
        dest,
        file_content=f,
        mode=mode,
        uid=ids[0],
        gid=ids[1],
        uname=names[0],
        gname=names[1])

  def add_tar(self, tar):
    """Merge a tar file into the destination tar file.

    All files presents in that tar will be added to the output file
    under self.directory/path. No user name nor group name will be
    added to the output.

    Args:
      tar: the tar file to add
    """
    root = None
    if self.directory and self.directory != '/':
      root = self.directory
    self.tarfile.add_tar(tar, numeric=True, root=root)


def unquote_and_split(arg, c):
  """Split a string at the first unquoted occurrence of a character.

  Split the string arg at the first unquoted occurrence of the character c.
  Here, in the first part of arg, the backslash is considered the
  quoting character indicating that the next character is to be
  added literally to the first part, even if it is the split character.

  Args:
    arg: the string to be split
    c: the character at which to split

  Returns:
    The unquoted string before the separator and the string after the
    separator.
  """
  head = ''
  i = 0
  while i < len(arg):
    if arg[i] == c:
      return (head, arg[i + 1:])
    elif arg[i] == '\\':
      i += 1
      if i == len(arg):
        # dangling quotation symbol
        return (head, '')
      else:
        head += arg[i]
    else:
      head += arg[i]
    i += 1
  # if we leave the loop, the character c was not found unquoted
  return (head, '')


def main(unused_argv):
  # Parse modes arguments
  default_mode = None
  if FLAGS.mode:
    # Convert from octal
    default_mode = int(FLAGS.mode, 8)

  mode_map = {}
  default_ownername = ('', '')
  if FLAGS.owner_name:
    default_ownername = FLAGS.owner_name.split('.', 1)

  default_ids = FLAGS.owner.split('.', 1)
  default_ids = (int(default_ids[0]), int(default_ids[1]))
  ids_map = {}

  # Add objects to the tar file
  with TarFile(FLAGS.output, FLAGS.directory, FLAGS.compression,
               FLAGS.root_directory) as output:

    def file_attributes(filename):
      if filename.startswith('/'):
        filename = filename[1:]
      return {
          'mode': mode_map.get(filename, default_mode),
          'ids': ids_map.get(filename, default_ids),
          'names': default_ownername,
      }

    for f in FLAGS.file:
      (inf, tof) = unquote_and_split(f, '=')
      output.add_file(inf, tof, **file_attributes(tof))
    for tar in FLAGS.tar:
      output.add_tar(tar)


if __name__ == '__main__':
  app.run(main)
