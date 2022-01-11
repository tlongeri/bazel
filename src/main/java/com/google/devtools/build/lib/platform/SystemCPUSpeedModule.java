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

package com.google.devtools.build.lib.platform;

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.jni.JniLoader;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** Detects cpu speed events. */
public final class SystemCPUSpeedModule extends BlazeModule {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  static {
    JniLoader.loadJni();
  }

  @GuardedBy("this")
  @Nullable
  private Reporter reporter;

  private native void registerJNI();

  private static native int cpuSpeed();

  public SystemCPUSpeedModule() {
    if (JniLoader.isJniAvailable()) {
      registerJNI();
    }
  }

  @Override
  public synchronized void beforeCommand(CommandEnvironment env) {
    this.reporter = env.getReporter();
    int startingSpeed = cpuSpeed();
    if (startingSpeed < 100) {
      cpuSpeedCallback(startingSpeed);
    }
  }

  @Override
  public synchronized void afterCommand() {
    this.reporter = null;
  }

  private synchronized void cpuSpeedCallback(int speed) {
    if (speed == -1) {
      // Speeds of -1 imply an error occurred in our speed gathering code.
      // It is expected that lower level code has logged the error, so we are just going to ignore.
      return;
    }
    SystemCPUSpeedEvent event = new SystemCPUSpeedEvent(speed);
    if (reporter != null) {
      reporter.post(event);
    }
    logger.atInfo().log(event.logString());
  }
}
