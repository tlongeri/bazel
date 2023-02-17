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
package com.google.devtools.build.lib.testutil;

import static org.junit.Assert.fail;

import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;

/** A helper class for implementing tests of the "foundation" library. */
public abstract class FoundationTestCase {
  protected Path rootDirectory;
  protected Path outputBase;

  // May be overridden by subclasses:
  protected Reporter reporter;
  // The event bus of the reporter
  protected EventBus eventBus;
  protected EventCollector eventCollector;
  protected FileSystem fileSystem;
  protected Scratch scratch;
  protected Root root;

  /** Returns the Scratch instance for this test case. */
  public Scratch getScratch() {
    return scratch;
  }

  // Individual tests can opt-out of this handler if they expect an error, by
  // calling reporter.removeHandler(failFastHandler).
  public static final EventHandler failFastHandler =
      new EventHandler() {
        @Override
        public void handle(Event event) {
          if (EventKind.ERRORS.contains(event.getKind())) {
            fail(event.toString());
          }
        }
      };

  protected static final EventHandler printHandler =
      new EventHandler() {
        @Override
        public void handle(Event event) {}
      };

  @Before
  public final void initializeFileSystemAndDirectories() throws Exception {
    fileSystem = createFileSystem();
    scratch = new Scratch(fileSystem, "/workspace");
    outputBase = scratch.dir("/usr/local/google/_blaze_jrluser/FAKEMD5/");
    rootDirectory = scratch.dir("/workspace");
    scratch.file(rootDirectory.getRelative("WORKSPACE").getPathString());
    root = Root.fromPath(rootDirectory);
  }

  @Before
  public final void initializeLogging() throws Exception {
    eventCollector = new EventCollector(EventKind.ERRORS_WARNINGS_AND_INFO);
    eventBus = new EventBus();
    reporter = new Reporter(eventBus, eventCollector);
    reporter.addHandler(failFastHandler);
  }

  @After
  public final void clearInterrupts() throws Exception {
    Thread.interrupted(); // Clear any interrupt pending against this thread,
    // so that we don't cause later tests to fail.
  }

  /** Creates the file system; override to inject FS behavior. */
  protected FileSystem createFileSystem() {
    return new InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256);
  }

  // Mix-in assertions:

  protected void assertNoEvents() {
    MoreAsserts.assertNoEvents(eventCollector);
  }

  protected Event assertContainsEvent(String expectedMessage) {
    return MoreAsserts.assertContainsEvent(eventCollector, expectedMessage);
  }

  protected Event assertContainsEvent(Pattern expectedMessagePattern) {
    return MoreAsserts.assertContainsEvent(eventCollector, expectedMessagePattern);
  }

  protected Event assertContainsEvent(String expectedMessage, Set<EventKind> kinds) {
    return MoreAsserts.assertContainsEvent(eventCollector, expectedMessage, kinds);
  }

  protected void assertContainsEventWithFrequency(String expectedMessage, int expectedFrequency) {
    MoreAsserts.assertContainsEventWithFrequency(
        eventCollector, expectedMessage, expectedFrequency);
  }

  protected void assertDoesNotContainEvent(String expectedMessage) {
    MoreAsserts.assertDoesNotContainEvent(eventCollector, expectedMessage);
  }

  protected void assertContainsEventsInOrder(String... expectedMessages) {
    MoreAsserts.assertContainsEventsInOrder(eventCollector, expectedMessages);
  }
}
