// Copyright 2021 The Bazel Authors. All rights reserved.
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

#include <IOKit/pwr_mgt/IOPMLib.h>
#include <notify.h>

#include "src/main/native/darwin/util.h"
#include "src/main/native/macros.h"
#include "src/main/native/unix_jni.h"

namespace blaze_jni {

static int gSystemLoadAdvisoryNotifyToken = 0;

void portable_start_system_load_advisory_monitoring() {
  // To test use:
  //   /usr/bin/log stream -level debug \
  //       --predicate '(subsystem == "build.bazel")'
  // We install a test notification as well that can be used for testing.
  static dispatch_once_t once_token;
  dispatch_once(&once_token, ^{
    dispatch_queue_t queue = bazel::darwin::JniDispatchQueue();
    notify_handler_t handler = (^(int state) {
      int value = portable_system_load_advisory();
      system_load_advisory_callback(value);
    });
    int status = notify_register_dispatch(kIOSystemLoadAdvisoryNotifyName,
                                          &gSystemLoadAdvisoryNotifyToken,
                                          queue, handler);
    CHECK(status == NOTIFY_STATUS_OK);

    // This is registered solely so we can test the system from end-to-end.
    // Using the Apple notification requires admin access.
    int testToken;
    status = notify_register_dispatch(
        "com.google.bazel.test.SystemLoadAdvisory", &testToken, queue, handler);
    CHECK(status == NOTIFY_STATUS_OK);
    log_if_possible("system load advisory monitoring registered");
  });
}

int portable_system_load_advisory() {
  uint64_t state;
  uint32_t status = notify_get_state(gSystemLoadAdvisoryNotifyToken, &state);
  if (status != NOTIFY_STATUS_OK) {
    log_if_possible("error: notify_get_state failed (%d)", status);
    return -1;
  }
  IOSystemLoadAdvisoryLevel advisoryLevel = (IOSystemLoadAdvisoryLevel)state;
  int load = -1;
  switch (advisoryLevel) {
    case kIOSystemLoadAdvisoryLevelGreat:
      log_if_possible("system load advisory great (0) anomaly");
      load = 0;
      break;

    case kIOSystemLoadAdvisoryLevelOK:
      log_if_possible("system load advisory ok (25) anomaly");
      load = 25;
      break;

    case kIOSystemLoadAdvisoryLevelBad:
      log_if_possible("system load advisory bad (75) anomaly");
      load = 75;
      break;
  }
  if (load == -1) {
    log_if_possible("error: unknown system load advisory level: %d",
                    (int)advisoryLevel);
  }

  return load;
}

}  // namespace blaze_jni
