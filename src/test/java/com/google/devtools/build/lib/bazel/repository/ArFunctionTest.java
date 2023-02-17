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

package com.google.devtools.build.lib.bazel.repository;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.File;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests decompressing archives. */
@RunWith(JUnit4.class)
public class ArFunctionTest {
  /*
   * .ar archive created with ar cr test_files.ar archived_first.txt archived_second.md
   * The files contain short UTF-8 encoded strings.
   */
  private static final String ARCHIVE_NAME = "test_files.ar";
  private static final String PATH_TO_TEST_ARCHIVE =
      "/com/google/devtools/build/lib/bazel/repository/";
  private static final String FIRST_FILE_NAME = "archived_first.txt";
  private static final String SECOND_FILE_NAME = "archived_second.md";

  @Test
  public void testDecompress() throws Exception {
    Path outputDir = decompress(createDescriptorBuilder());

    assertThat(outputDir.exists()).isTrue();
    Path firstFile = outputDir.getRelative(FIRST_FILE_NAME);
    assertThat(firstFile.exists()).isTrue();
    // There are 20 bytes in the content "this is test file 1"
    assertThat(firstFile.getFileSize()).isEqualTo(20);
    assertThat(firstFile.isSymbolicLink()).isFalse();

    Path secondFile = outputDir.getRelative(SECOND_FILE_NAME);
    assertThat(secondFile.exists()).isTrue();
    // There are 20 bytes in the content "this is the second test file"
    assertThat(secondFile.getFileSize()).isEqualTo(29);
    assertThat(secondFile.isSymbolicLink()).isFalse();
  }

  private Path decompress(DecompressorDescriptor.Builder descriptorBuilder) throws Exception {
    descriptorBuilder.setDecompressor(ArFunction.INSTANCE);
    return new ArFunction().decompress(descriptorBuilder.build());
  }

  private DecompressorDescriptor.Builder createDescriptorBuilder() throws IOException {
    // This was cribbed from TestArchiveDescriptor
    FileSystem testFS =
        OS.getCurrent() == OS.WINDOWS
            ? new JavaIoFileSystem(DigestHashFunction.SHA256)
            : new UnixFileSystem(DigestHashFunction.SHA256, /*hashAttributeName=*/ "");

    // do not rely on TestConstants.JAVATESTS_ROOT end with slash, but ensure separators
    // are not duplicated
    String path =
        (TestConstants.JAVATESTS_ROOT + PATH_TO_TEST_ARCHIVE + ARCHIVE_NAME).replace("//", "/");
    Path tarballPath = testFS.getPath(Runfiles.create().rlocation(path));

    Path workingDir = testFS.getPath(new File(TestUtils.tmpDir()).getCanonicalPath());
    Path outDir = workingDir.getRelative("out");

    return DecompressorDescriptor.builder().setRepositoryPath(outDir).setArchivePath(tarballPath);
  }
}
