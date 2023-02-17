// Copyright 2011 The Bazel Authors. All Rights Reserved.
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

package com.google.devtools.build.buildjar.javac.plugins.errorprone;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.buildjar.InvalidCommandLineException;
import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin;
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics;
import com.google.errorprone.BaseErrorProneJavaCompiler;
import com.google.errorprone.ErrorProneAnalyzer;
import com.google.errorprone.ErrorProneError;
import com.google.errorprone.ErrorProneOptions;
import com.google.errorprone.ErrorProneTimings;
import com.google.errorprone.InvalidCommandLineOptionException;
import com.google.errorprone.scanner.BuiltInCheckerSuppliers;
import com.google.errorprone.scanner.ScannerSupplier;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskEvent.Kind;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A plugin for BlazeJavaCompiler that performs Error Prone analysis. Error Prone is a static
 * analysis framework that we use to perform some simple static checks on Java code.
 */
public final class ErrorPronePlugin extends BlazeJavaCompilerPlugin {

  private final ScannerSupplier scannerSupplier;

  /**
   * Constructs an {@link ErrorPronePlugin} instance with the set of checks that are enabled as
   * errors in open-source Error Prone.
   */
  public ErrorPronePlugin() {
    this(BuiltInCheckerSuppliers.errorChecks());
  }

  /**
   * Constructs an {@link ErrorPronePlugin} with the set of checks that are enabled in {@code
   * scannerSupplier}.
   */
  public ErrorPronePlugin(ScannerSupplier scannerSupplier) {
    this.scannerSupplier = scannerSupplier;
  }

  private ErrorProneAnalyzer errorProneAnalyzer;
  private ErrorProneOptions epOptions;
  private ErrorProneTimings timings;
  private final Stopwatch elapsed = Stopwatch.createUnstarted();

  /** Registers our message bundle. */
  public static void setupMessageBundle(Context context) {
    BaseErrorProneJavaCompiler.setupMessageBundle(context);
  }

  @Override
  public void processArgs(
      ImmutableList<String> standardJavacopts, ImmutableList<String> blazeJavacopts)
      throws InvalidCommandLineException {
    ImmutableList.Builder<String> epArgs = ImmutableList.<String>builder().addAll(blazeJavacopts);
    // allow javacopts that reference unknown error-prone checks
    epArgs.add("-XepIgnoreUnknownCheckNames");
    processEpOptions(epArgs.build());
  }

  private void processEpOptions(List<String> args) throws InvalidCommandLineException {
    try {
      epOptions = ErrorProneOptions.processArgs(args);
    } catch (InvalidCommandLineOptionException e) {
      throw new InvalidCommandLineException(e.getMessage());
    }
  }

  @Override
  public void init(
      Context context,
      Log log,
      JavaCompiler compiler,
      BlazeJavacStatistics.Builder statisticsBuilder) {
    super.init(context, log, compiler, statisticsBuilder);

    setupMessageBundle(context);

    if (epOptions == null) {
      epOptions = ErrorProneOptions.empty();
    }
    errorProneAnalyzer =
        ErrorProneAnalyzer.createByScanningForPlugins(scannerSupplier, epOptions, context);
    timings = ErrorProneTimings.instance(context);
  }

  /** Run Error Prone analysis after performing dataflow checks. */
  @Override
  public void postFlow(Env<AttrContext> env) {
    elapsed.start();
    try {
      errorProneAnalyzer.finished(new TaskEvent(Kind.ANALYZE, env.toplevel, env.enclClass.sym));
    } catch (ErrorProneError e) {
      e.logFatalError(log, context);
      // let the exception propagate to javac's main, where it will cause the compilation to
      // terminate with Result.ABNORMAL
      throw e;
    } finally {
      elapsed.stop();
    }
  }

  @Override
  public void finish() {
    statisticsBuilder.totalErrorProneTime(elapsed.elapsed());
    timings.timings().entrySet().stream()
        .sorted(Map.Entry.<String, Duration>comparingByValue().reversed())
        .limit(10) // best-effort to stay under the action metric size limit
        .forEachOrdered(e -> statisticsBuilder.addBugpatternTiming(e.getKey(), e.getValue()));
  }
}
