// Copyright 2020 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.testing.TestLogHandler;
import com.google.devtools.build.lib.bugreport.BugReport.BlazeRuntimeInterface;
import com.google.devtools.build.lib.bugreport.BugReport.NonFatalBugReport;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.Crash.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.CustomExitCodePublisher;
import com.google.devtools.build.lib.util.CustomFailureDetailPublisher;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.protobuf.ExtensionRegistry;
import com.google.testing.junit.runner.util.GoogleTestSecurityManager;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link BugReport}.
 *
 * <p>Assuming that {@link GoogleTestSecurityManager} is not already installed, uses {@link
 * ExitProhibitingSecurityManager} to exercise attempting to halt the JVM without aborting the whole
 * test.
 */
// TODO(b/222158599): Remove handling for GoogleTestSecurityManager.
@RunWith(TestParameterInjector.class)
public final class BugReportTest {

  @BeforeClass
  public static void installCustomSecurityManager() {
    if (System.getSecurityManager() == null) {
      System.setSecurityManager(new ExitProhibitingSecurityManager());
    } else {
      assertThat(System.getSecurityManager()).isInstanceOf(GoogleTestSecurityManager.class);
    }
  }

  @AfterClass
  public static void uninstallCustomSecurityManager() {
    if (System.getSecurityManager() instanceof ExitProhibitingSecurityManager) {
      System.setSecurityManager(null);
    }
  }

  private enum CrashType {
    CRASH(ExitCode.BLAZE_INTERNAL_ERROR, Code.CRASH_UNKNOWN) {
      @Override
      Throwable createThrowable() {
        return new IllegalStateException("Crashed");
      }
    },
    OOM(ExitCode.OOM_ERROR, Code.CRASH_OOM) {
      @Override
      Throwable createThrowable() {
        return new OutOfMemoryError("Java heap space");
      }
    };

    private final ExitCode expectedExitCode;
    private final Code expectedFailureDetailCode;

    CrashType(ExitCode expectedExitCode, Code expectedFailureDetailCode) {
      this.expectedExitCode = expectedExitCode;
      this.expectedFailureDetailCode = expectedFailureDetailCode;
    }

    abstract Throwable createThrowable();
  }

  private static final class NonFatalException extends RuntimeException
      implements NonFatalBugReport {
    NonFatalException(String message) {
      super(message);
    }
  }

  private enum ExceptionType {
    FATAL(
        new RuntimeException("fatal exception"),
        Level.SEVERE,
        "myProductName crashed with args: arg foo"),
    NONFATAL(
        new NonFatalException("bug report"),
        Level.WARNING,
        "myProductName had a non fatal error with args: arg foo"),
    OOM(new OutOfMemoryError("Java heap space"), Level.SEVERE, "myProductName OOMError: arg foo");

    @SuppressWarnings("ImmutableEnumChecker") // I'm pretty sure no one will mutate this Throwable.
    private final Throwable throwable;

    @SuppressWarnings("ImmutableEnumChecker") // Same here.
    private final Level level;

    private final String expectedMessage;

    ExceptionType(Throwable throwable, Level level, String expectedMessage) {
      this.throwable = throwable;
      this.level = level;
      this.expectedMessage = expectedMessage;
    }
  }

  @Rule public final TemporaryFolder tmp = new TemporaryFolder();

  private final BlazeRuntimeInterface mockRuntime = mock(BlazeRuntimeInterface.class);

  private Path exitCodeFile;
  private Path failureDetailFile;

  @Before
  public void setup() throws Exception {
    when(mockRuntime.getProductName()).thenReturn("myProductName");
    BugReport.setRuntime(mockRuntime);

    exitCodeFile = tmp.newFolder().toPath().resolve("exit_code_to_use_on_abrupt_exit");
    failureDetailFile = tmp.newFolder().toPath().resolve("failure_detail");

    CustomExitCodePublisher.setAbruptExitStatusFileDir(exitCodeFile.getParent().toString());
    CustomFailureDetailPublisher.setFailureDetailFilePath(failureDetailFile.toString());
  }

  @After
  public void resetPublishers() {
    CustomExitCodePublisher.resetAbruptExitStatusFile();
    CustomFailureDetailPublisher.resetFailureDetailFilePath();
  }

  @Test
  public void logException(@TestParameter ExceptionType exceptionType) throws Exception {
    TestLogHandler handler = new TestLogHandler();
    Logger logger = Logger.getLogger("build.lib.bugreport");
    logger.addHandler(handler);
    LoggingUtil.installRemoteLoggerForTesting(immediateFuture(logger));

    BugReport.logException(exceptionType.throwable, ImmutableList.of("arg", "foo"));

    LogRecord got = handler.getStoredLogRecords().get(0);
    assertThat(got.getThrown()).isSameInstanceAs(exceptionType.throwable);
    assertThat(got.getLevel()).isEqualTo(exceptionType.level);
    assertThat(got.getMessage()).isEqualTo(exceptionType.expectedMessage);
  }

  @Test
  public void convenienceMethod(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType.expectedFailureDetailCode);

    // TODO(b/222158599): This should always be ExitException.
    SecurityException e = assertThrows(SecurityException.class, () -> BugReport.handleCrash(t));
    if (e instanceof ExitException) {
      int code = ((ExitException) e).code;
      assertThat(code).isEqualTo(crashType.expectedExitCode.getNumericExitCode());
    }
    assertThrows(t.getClass(), BugReport::maybePropagateUnprocessedThrowableIfInTest);

    verify(mockRuntime)
        .cleanUpForCrash(DetailedExitCode.of(crashType.expectedExitCode, expectedFailureDetail));
    verifyExitCodeWritten(crashType.expectedExitCode.getNumericExitCode());
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void halt(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType.expectedFailureDetailCode);

    // TODO(b/222158599): This should always be ExitException.
    SecurityException e =
        assertThrows(
            SecurityException.class,
            () -> BugReport.handleCrash(Crash.from(t), CrashContext.halt()));
    if (e instanceof ExitException) {
      int code = ((ExitException) e).code;
      assertThat(code).isEqualTo(crashType.expectedExitCode.getNumericExitCode());
    }
    assertThrows(t.getClass(), BugReport::maybePropagateUnprocessedThrowableIfInTest);

    verify(mockRuntime)
        .cleanUpForCrash(DetailedExitCode.of(crashType.expectedExitCode, expectedFailureDetail));
    verifyExitCodeWritten(crashType.expectedExitCode.getNumericExitCode());
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void keepAlive(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType.expectedFailureDetailCode);

    BugReport.handleCrash(Crash.from(t), CrashContext.keepAlive());
    assertThrows(t.getClass(), BugReport::maybePropagateUnprocessedThrowableIfInTest);

    verify(mockRuntime)
        .cleanUpForCrash(DetailedExitCode.of(crashType.expectedExitCode, expectedFailureDetail));
    verifyNoExitCodeWritten();
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void customContext_setUpFront(@TestParameter CrashType crashType) {
    Throwable t = crashType.createThrowable();
    EventHandler handler = mock(EventHandler.class);
    ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);

    BugReport.handleCrash(
        Crash.from(t),
        CrashContext.keepAlive().withExtraOomInfo("Build fewer targets!").reportingTo(handler));
    assertThrows(t.getClass(), BugReport::maybePropagateUnprocessedThrowableIfInTest);

    verify(handler).handle(event.capture());
    assertThat(event.getValue().getKind()).isEqualTo(EventKind.FATAL);
    assertThat(event.getValue().getMessage()).contains(Throwables.getStackTraceAsString(t));

    if (crashType == CrashType.OOM) {
      assertThat(event.getValue().getMessage()).contains("Build fewer targets!");
    } else {
      assertThat(event.getValue().getMessage()).doesNotContain("Build fewer targets!");
    }
  }

  @Test
  public void customContext_filledInByRuntime(@TestParameter CrashType crashType) {
    Throwable t = crashType.createThrowable();
    EventHandler handler = mock(EventHandler.class);
    ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
    doAnswer(
            inv ->
                inv.getArgument(0, CrashContext.class)
                    .withExtraOomInfo("Build fewer targets!")
                    .reportingTo(handler))
        .when(mockRuntime)
        .fillInCrashContext(any());

    BugReport.handleCrash(Crash.from(t), CrashContext.keepAlive());
    assertThrows(t.getClass(), BugReport::maybePropagateUnprocessedThrowableIfInTest);

    verify(handler).handle(event.capture());
    assertThat(event.getValue().getKind()).isEqualTo(EventKind.FATAL);
    assertThat(event.getValue().getMessage()).contains(Throwables.getStackTraceAsString(t));

    if (crashType == CrashType.OOM) {
      assertThat(event.getValue().getMessage()).contains("Build fewer targets!");
    } else {
      assertThat(event.getValue().getMessage()).doesNotContain("Build fewer targets!");
    }
  }

  private void verifyExitCodeWritten(int exitCode) throws Exception {
    assertThat(Files.readAllLines(exitCodeFile)).containsExactly(String.valueOf(exitCode));
  }

  private void verifyNoExitCodeWritten() {
    assertThat(exitCodeFile.toFile().exists()).isFalse();
  }

  private void verifyFailureDetailWritten(FailureDetail expected) throws Exception {
    assertThat(
            FailureDetail.parseFrom(
                Files.readAllBytes(failureDetailFile), ExtensionRegistry.getEmptyRegistry()))
        .isEqualTo(expected);
  }

  private static FailureDetail createExpectedFailureDetail(
      Throwable t, Code expectedFailureDetailCode) {
    return FailureDetail.newBuilder()
        .setMessage(String.format("Crashed: (%s) %s", t.getClass().getName(), t.getMessage()))
        .setCrash(
            FailureDetails.Crash.newBuilder()
                .setCode(expectedFailureDetailCode)
                .addCauses(
                    FailureDetails.Throwable.newBuilder()
                        .setThrowableClass(t.getClass().getName())
                        .setMessage(t.getMessage())
                        .addAllStackTrace(
                            Lists.transform(
                                Arrays.asList(t.getStackTrace()), StackTraceElement::toString))))
        .build();
  }

  private static final class ExitException extends SecurityException {
    private final int code;

    ExitException(int code) {
      super("Tried to exit JVM with code " + code);
      this.code = code;
    }
  }

  /** Instead of exiting the JVM, throws {@link ExitException} to keep the test alive. */
  private static final class ExitProhibitingSecurityManager extends SecurityManager {

    @Override
    public void checkExit(int code) {
      throw new ExitException(code);
    }

    @Override
    public void checkPermission(Permission p) {} // Allow everything else.
  }
}

