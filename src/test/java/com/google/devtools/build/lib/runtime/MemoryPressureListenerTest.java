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

package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryUsage;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link MemoryPressureListener}. */
@RunWith(JUnit4.class)
public final class MemoryPressureListenerTest {
  private interface NotificationBean extends GarbageCollectorMXBean, NotificationEmitter {}

  private static final String TENURED_SPACE_NAME = "CMS Old Gen";
  private final NotificationBean mockBean = mock(NotificationBean.class);
  private final NotificationBean mockUselessBean = mock(NotificationBean.class);

  @Before
  public void initMocks() {
    when(mockBean.getMemoryPoolNames())
        .thenReturn(new String[] {"not tenured", TENURED_SPACE_NAME});
    when(mockUselessBean.getMemoryPoolNames()).thenReturn(new String[] {"assistant", "adjunct"});
  }

  @Test
  public void findBeans() {
    assertThat(
        MemoryPressureListener.findTenuredCollectorBeans(
            ImmutableList.of(mockUselessBean, mockBean)))
        .containsExactly(mockBean);
  }

  @Test
  public void createFromBeans_returnsNullIfNoTenuredSpaceBean() {
    assertThat(
            MemoryPressureListener.createFromBeans(
                ImmutableList.of(mockUselessBean), ImmutableList.of()))
        .isNull();
  }

  @Test
  public void simple() {
    MemoryPressureHandler mockHandler = mock(MemoryPressureHandler.class);

    MemoryPressureListener underTest = MemoryPressureListener.createFromBeans(
        ImmutableList.of(mockUselessBean, mockBean),
        ImmutableList.of(mockHandler));
    verify(mockBean).addNotificationListener(underTest, null, null);
    verify(mockUselessBean, never()).addNotificationListener(any(), any(), any());

    GcInfo mockGcInfo = mock(GcInfo.class);
    String nonTenuredSpaceName = "nope";
    MemoryUsage mockMemoryUsageForNonTenuredSpace = mock(MemoryUsage.class);
    MemoryUsage mockMemoryUsageForTenuredSpace = mock(MemoryUsage.class);
    when(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(42L);
    when(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(100L);
    when(mockGcInfo.getMemoryUsageAfterGc())
        .thenReturn(
            ImmutableMap.of(
                nonTenuredSpaceName,
                mockMemoryUsageForNonTenuredSpace,
                TENURED_SPACE_NAME,
                mockMemoryUsageForTenuredSpace));

    Notification notification =
        new Notification(
            GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123);
    notification.setUserData(
        new GarbageCollectionNotificationInfo("gcName", "gcAction", "non-manual", mockGcInfo)
            .toCompositeData(null));
    underTest.handleNotification(notification, null);

    verify(mockHandler)
        .handle(
            MemoryPressureHandler.Event.newBuilder()
                .setWasManualGc(false)
                .setTenuredSpaceUsedBytes(42L)
                .setTenuredSpaceMaxBytes(100L)
                .build());
  }

  @Test
  public void manualGc() {
    MemoryPressureHandler mockHandler = mock(MemoryPressureHandler.class);

    MemoryPressureListener underTest = MemoryPressureListener.createFromBeans(
        ImmutableList.of(mockBean),
        ImmutableList.of(mockHandler));
    verify(mockBean).addNotificationListener(underTest, null, null);

    GcInfo mockGcInfo = mock(GcInfo.class);
    MemoryUsage mockMemoryUsageForTenuredSpace = mock(MemoryUsage.class);
    when(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(42L);
    when(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(100L);
    when(mockGcInfo.getMemoryUsageAfterGc())
        .thenReturn(ImmutableMap.of(TENURED_SPACE_NAME, mockMemoryUsageForTenuredSpace));

    Notification notification =
        new Notification(
            GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123);
    notification.setUserData(
        new GarbageCollectionNotificationInfo("gcName", "gcAction", "System.gc()", mockGcInfo)
            .toCompositeData(null));
    underTest.handleNotification(notification, null);

    verify(mockHandler)
        .handle(
            MemoryPressureHandler.Event.newBuilder()
                .setWasManualGc(true)
                .setTenuredSpaceUsedBytes(42L)
                .setTenuredSpaceMaxBytes(100L)
                .build());
  }

  @Test
  public void doesntInvokeHandlerWhenTenuredSpaceMaxSizeIsZero() {
    MemoryPressureHandler mockHandler = mock(MemoryPressureHandler.class);

    MemoryPressureListener underTest = MemoryPressureListener.createFromBeans(
        ImmutableList.of(mockBean),
        ImmutableList.of(mockHandler));
    verify(mockBean).addNotificationListener(underTest, null, null);

    GcInfo mockGcInfo = mock(GcInfo.class);
    MemoryUsage mockMemoryUsageForTenuredSpace = mock(MemoryUsage.class);
    when(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(42L);
    when(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(0L);
    when(mockGcInfo.getMemoryUsageAfterGc())
        .thenReturn(ImmutableMap.of(TENURED_SPACE_NAME, mockMemoryUsageForTenuredSpace));

    Notification notification =
        new Notification(
            GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123);
    notification.setUserData(
        new GarbageCollectionNotificationInfo("gcName", "gcAction", "non-manual", mockGcInfo)
            .toCompositeData(null));
    underTest.handleNotification(notification, null);

    verify(mockHandler, never()).handle(any());
  }

  @Test
  public void findsTenuredSpaceWithNonZeroMaxSize() {
    MemoryPressureHandler mockHandler = mock(MemoryPressureHandler.class);

    NotificationBean anotherMockBean = mock(NotificationBean.class);
    String anotherTenuredSpaceName = "G1 Old Gen";
    when(anotherMockBean.getMemoryPoolNames()).thenReturn(new String[] {anotherTenuredSpaceName});

    MemoryPressureListener underTest = MemoryPressureListener.createFromBeans(
        ImmutableList.of(mockBean, anotherMockBean),
        ImmutableList.of(mockHandler));
    verify(mockBean).addNotificationListener(underTest, null, null);
    verify(anotherMockBean).addNotificationListener(underTest, null, null);

    GcInfo mockGcInfo = mock(GcInfo.class);
    MemoryUsage mockMemoryUsageForTenuredSpace = mock(MemoryUsage.class);
    when(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(1L);
    when(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(0L);
    MemoryUsage mockMemoryUsageForAnotherTenuredSpace = mock(MemoryUsage.class);
    when(mockMemoryUsageForAnotherTenuredSpace.getUsed()).thenReturn(2L);
    when(mockMemoryUsageForAnotherTenuredSpace.getMax()).thenReturn(3L);
    when(mockGcInfo.getMemoryUsageAfterGc())
        .thenReturn(
            ImmutableMap.of(
                TENURED_SPACE_NAME,
                mockMemoryUsageForTenuredSpace,
                anotherTenuredSpaceName,
                mockMemoryUsageForAnotherTenuredSpace));

    Notification notification =
        new Notification(
            GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123);
    notification.setUserData(
        new GarbageCollectionNotificationInfo("gcName", "gcAction", "non-manual", mockGcInfo)
            .toCompositeData(null));
    underTest.handleNotification(notification, null);

    verify(mockHandler)
        .handle(
            MemoryPressureHandler.Event.newBuilder()
                .setWasManualGc(false)
                .setTenuredSpaceUsedBytes(2L)
                .setTenuredSpaceMaxBytes(3L)
                .build());
  }
}