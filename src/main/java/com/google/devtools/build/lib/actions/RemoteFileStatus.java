// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;

/**
 * A FileStatus that exists remotely and provides remote metadata.
 *
 * <p>Filesystem may return implementation of this interface if the files are stored remotely. When
 * checking outputs of actions, Skyframe will inject the metadata returned by {@link
 * #getRemoteMetadata()} if the output file has {@link RemoteFileStatus}.
 */
public interface RemoteFileStatus extends FileStatusWithDigest {
  RemoteFileArtifactValue getRemoteMetadata();
}
