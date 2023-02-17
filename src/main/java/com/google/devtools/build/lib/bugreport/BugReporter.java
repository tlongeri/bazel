// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bugreport;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.List;

/**
 * Logs bug reports.
 *
 * <p>This interface is generally fulfilled by {@link #defaultInstance}. It exists to facilitate
 * alternate implementations in tests that intentionally send bug reports, since the default
 * instance throws if invoked during a test case.
 */
public interface BugReporter {

  static BugReporter defaultInstance() {
    return BugReport.REPORTER_INSTANCE;
  }

  /** Reports an unexpected state, see {@link BugReport#logUnexpected}. */
  @FormatMethod
  default void logUnexpected(@FormatString String message, Object... args) {
    BugReport.logUnexpected(message, args);
  }

  /** See {@link BugReport#logUnexpected}. */
  @FormatMethod
  default void logUnexpected(Exception e, @FormatString String message, Object... args) {
    BugReport.logUnexpected(e, message, args);
  }

  /** Reports an exception, see {@link BugReport#sendBugReport(String, Object...)}. */
  @FormatMethod
  default void sendBugReport(@FormatString String message, Object... args) {
    sendBugReport(new IllegalStateException(String.format(message, args)));
  }

  /** Reports an exception, see {@link BugReport#sendBugReport(Throwable)}. */
  default void sendBugReport(Throwable exception) {
    sendBugReport(exception, /*args=*/ ImmutableList.of());
  }

  /** Reports an exception, see {@link BugReport#sendBugReport(Throwable, List, String...)}. */
  void sendBugReport(Throwable exception, List<String> args, String... values);

  /** Reports a non-fatal exception, see {@link BugReport#sendNonFatalBugReport(Throwable)}. */
  void sendNonFatalBugReport(Exception exception);

  /** See {@link BugReport#handleCrash}. */
  void handleCrash(Crash crash, CrashContext ctx);
}
