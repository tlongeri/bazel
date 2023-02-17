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
package com.google.devtools.build.lib.remote.common;

import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.Spawn;
import javax.annotation.Nullable;

/** A context that provide remote execution related information for executing an action remotely. */
public class RemoteActionExecutionContext {
  /** Determines whether to read/write remote cache, disk cache or both. */
  public enum CachePolicy {
    NO_CACHE,
    REMOTE_CACHE_ONLY,
    DISK_CACHE_ONLY,
    ANY_CACHE;

    public static CachePolicy create(boolean allowRemoteCache, boolean allowDiskCache) {
      if (allowRemoteCache && allowDiskCache) {
        return ANY_CACHE;
      } else if (allowRemoteCache) {
        return REMOTE_CACHE_ONLY;
      } else if (allowDiskCache) {
        return DISK_CACHE_ONLY;
      } else {
        return NO_CACHE;
      }
    }

    public boolean allowAnyCache() {
      return this != NO_CACHE;
    }

    public boolean allowRemoteCache() {
      return this == REMOTE_CACHE_ONLY || this == ANY_CACHE;
    }

    public boolean allowDiskCache() {
      return this == DISK_CACHE_ONLY || this == ANY_CACHE;
    }

    public CachePolicy addRemoteCache() {
      if (this == DISK_CACHE_ONLY || this == ANY_CACHE) {
        return ANY_CACHE;
      }

      return REMOTE_CACHE_ONLY;
    }
  }

  @Nullable private final Spawn spawn;
  private final RequestMetadata requestMetadata;
  private final NetworkTime networkTime;
  private final CachePolicy writeCachePolicy;
  private final CachePolicy readCachePolicy;

  private RemoteActionExecutionContext(
      @Nullable Spawn spawn, RequestMetadata requestMetadata, NetworkTime networkTime) {
    this.spawn = spawn;
    this.requestMetadata = requestMetadata;
    this.networkTime = networkTime;
    this.writeCachePolicy = CachePolicy.ANY_CACHE;
    this.readCachePolicy = CachePolicy.ANY_CACHE;
  }

  private RemoteActionExecutionContext(
      @Nullable Spawn spawn,
      RequestMetadata requestMetadata,
      NetworkTime networkTime,
      CachePolicy writeCachePolicy,
      CachePolicy readCachePolicy) {
    this.spawn = spawn;
    this.requestMetadata = requestMetadata;
    this.networkTime = networkTime;
    this.writeCachePolicy = writeCachePolicy;
    this.readCachePolicy = readCachePolicy;
  }

  public RemoteActionExecutionContext withWriteCachePolicy(CachePolicy writeCachePolicy) {
    return new RemoteActionExecutionContext(
        spawn, requestMetadata, networkTime, writeCachePolicy, readCachePolicy);
  }

  public RemoteActionExecutionContext withReadCachePolicy(CachePolicy readCachePolicy) {
    return new RemoteActionExecutionContext(
        spawn, requestMetadata, networkTime, writeCachePolicy, readCachePolicy);
  }

  /** Returns the {@link Spawn} of the action being executed or {@code null}. */
  @Nullable
  public Spawn getSpawn() {
    return spawn;
  }

  /** Returns the {@link RequestMetadata} for the action being executed. */
  public RequestMetadata getRequestMetadata() {
    return requestMetadata;
  }

  /**
   * Returns the {@link NetworkTime} instance used to measure the network time during the action
   * execution.
   */
  public NetworkTime getNetworkTime() {
    return networkTime;
  }

  @Nullable
  public ActionExecutionMetadata getSpawnOwner() {
    Spawn spawn = getSpawn();
    if (spawn == null) {
      return null;
    }

    return spawn.getResourceOwner();
  }

  public CachePolicy getWriteCachePolicy() {
    return writeCachePolicy;
  }

  public CachePolicy getReadCachePolicy() {
    return readCachePolicy;
  }

  /** Creates a {@link RemoteActionExecutionContext} with given {@link RequestMetadata}. */
  public static RemoteActionExecutionContext create(RequestMetadata metadata) {
    return new RemoteActionExecutionContext(/*spawn=*/ null, metadata, new NetworkTime());
  }

  /**
   * Creates a {@link RemoteActionExecutionContext} with given {@link Spawn} and {@link
   * RequestMetadata}.
   */
  public static RemoteActionExecutionContext create(
      @Nullable Spawn spawn, RequestMetadata metadata) {
    return new RemoteActionExecutionContext(spawn, metadata, new NetworkTime());
  }

  public static RemoteActionExecutionContext create(
      @Nullable Spawn spawn,
      RequestMetadata requestMetadata,
      CachePolicy writeCachePolicy,
      CachePolicy readCachePolicy) {
    return new RemoteActionExecutionContext(
        spawn, requestMetadata, new NetworkTime(), writeCachePolicy, readCachePolicy);
  }
}
