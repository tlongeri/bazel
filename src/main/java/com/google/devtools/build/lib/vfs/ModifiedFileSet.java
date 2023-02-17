// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs;

import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * An immutable set of modified source files. The scope of these files is context-dependent; in some
 * uses this may mean information about all files in the client, while in other uses this may mean
 * information about some specific subset of files. {@link #EVERYTHING_MODIFIED} can be used to
 * indicate that all files of interest have been modified.
 */
public class ModifiedFileSet {

  // When everything is modified that naturally includes all directories.
  public static final ModifiedFileSet EVERYTHING_MODIFIED =
      new ModifiedFileSet(null, /*includesAncestorDirectories=*/ true);

  /**
   * Special case of {@link #EVERYTHING_MODIFIED}, which indicates that the entire tree has been
   * deleted.
   */
  public static final ModifiedFileSet EVERYTHING_DELETED =
      new ModifiedFileSet(null, /*includesAncestorDirectories=*/ true) {
        @Override
        public boolean treatEverythingAsDeleted() {
          return true;
        }
      };

  public static final ModifiedFileSet NOTHING_MODIFIED =
      new ModifiedFileSet(ImmutableSet.of(), /*includesAncestorDirectories=*/ true);

  @Nullable private final ImmutableSet<PathFragment> modified;
  private final boolean includesAncestorDirectories;

  /**
   * Whether all files of interest should be treated as potentially modified.
   */
  public boolean treatEverythingAsModified() {
    return modified == null;
  }

  /**
   * Returns whether the diff indicates the whole tree has been deleted.
   *
   * <p>This precludes any optimizations like skipping invalidation when we do not check modified
   * outputs.
   */
  public boolean treatEverythingAsDeleted() {
    return false;
  }

  /**
   * The set of files of interest that were modified.
   *
   * @throws IllegalStateException if {@link #treatEverythingAsModified} returns true.
   */
  public ImmutableSet<PathFragment> modifiedSourceFiles() {
    if (treatEverythingAsModified()) {
      throw new IllegalStateException();
    }
    return modified;
  }

  /**
   * Returns whether the diff includes all of affected directories or we need to infer those from
   * reported items.
   */
  public boolean includesAncestorDirectories() {
    return includesAncestorDirectories;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof ModifiedFileSet)) {
      return false;
    }
    ModifiedFileSet other = (ModifiedFileSet) o;
    return treatEverythingAsModified() == other.treatEverythingAsModified()
        && treatEverythingAsDeleted() == other.treatEverythingAsDeleted()
        && includesAncestorDirectories == other.includesAncestorDirectories
        && Objects.equals(modified, other.modified);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hashCode(modified) + Boolean.hashCode(treatEverythingAsDeleted());
  }

  @Override
  public String toString() {
    if (this.equals(EVERYTHING_DELETED)) {
      return "EVERYTHING_DELETED";
    } else if (this.equals(EVERYTHING_MODIFIED)) {
      return "EVERYTHING_MODIFIED";
    } else if (this.equals(NOTHING_MODIFIED)) {
      return "NOTHING_MODIFIED";
    } else {
      return modified.toString();
    }
  }

  private ModifiedFileSet(
      ImmutableSet<PathFragment> modified, boolean includesAncestorDirectories) {
    this.modified = modified;
    this.includesAncestorDirectories = includesAncestorDirectories;
  }

  /** The builder for {@link ModifiedFileSet}. */
  public static class Builder {
    private final ImmutableSet.Builder<PathFragment> setBuilder = ImmutableSet.builder();
    private boolean includesAncestorDirectories = true;

    public ModifiedFileSet build() {
      ImmutableSet<PathFragment> modified = setBuilder.build();
      return modified.isEmpty()
          // Special case -- if no files were affected, we know the diff is complete even if
          // ancestor directories may not be accounted for.
          ? NOTHING_MODIFIED
          : new ModifiedFileSet(modified, includesAncestorDirectories);
    }

    public Builder setIncludesAncestorDirectories(boolean includesAncestorDirectories) {
      this.includesAncestorDirectories = includesAncestorDirectories;
      return this;
    }

    public Builder modify(PathFragment pathFragment) {
      setBuilder.add(pathFragment);
      return this;
    }

    public Builder modifyAll(Iterable<PathFragment> pathFragments) {
      setBuilder.addAll(pathFragments);
      return this;
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}
