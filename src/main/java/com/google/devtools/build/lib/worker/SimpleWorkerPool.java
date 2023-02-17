// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker;

import com.google.common.base.Throwables;
import java.io.IOException;
import java.util.Objects;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;

/**
 * A worker pool that spawns multiple workers and delegates work to them.
 *
 * <p>This is useful when the worker cannot handle multiple parallel requests on its own and we need
 * to pre-fork a couple of them instead.
 */
@ThreadSafe
final class SimpleWorkerPool extends GenericKeyedObjectPool<WorkerKey, Worker> {

  public SimpleWorkerPool(WorkerFactory factory, int max) {
    super(factory, makeConfig(max));
  }

  static SimpleWorkerPoolConfig makeConfig(int max) {
    SimpleWorkerPoolConfig config = new SimpleWorkerPoolConfig();

    // It's better to re-use a worker as often as possible and keep it hot, in order to profit
    // from JIT optimizations as much as possible.
    config.setLifo(true);

    // Keep a fixed number of workers running per key.
    config.setMaxIdlePerKey(max);
    config.setMaxTotalPerKey(max);
    config.setMinIdlePerKey(max);

    // Don't limit the total number of worker processes, as otherwise the pool might be full of
    // workers for one WorkerKey and can't accommodate a worker for another WorkerKey.
    config.setMaxTotal(-1);

    // Wait for a worker to become ready when a thread needs one.
    config.setBlockWhenExhausted(true);

    // Always test the liveliness of worker processes.
    config.setTestOnBorrow(true);
    config.setTestOnCreate(true);
    config.setTestOnReturn(true);

    // No eviction of idle workers.
    config.setTimeBetweenEvictionRunsMillis(-1);

    return config;
  }

  @Override
  public Worker borrowObject(WorkerKey key) throws IOException, InterruptedException {
    try {
      return super.borrowObject(key);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, IOException.class, InterruptedException.class);
      throw new RuntimeException("unexpected", t);
    }
  }

  @Override
  public void invalidateObject(WorkerKey key, Worker obj) throws InterruptedException {
    try {
      super.invalidateObject(key, obj);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, InterruptedException.class);
      throw new RuntimeException("unexpected", t);
    }
  }

  /**
   * Our own configuration class for the {@code SimpleWorkerPool} that correctly implements {@code
   * equals()} and {@code hashCode()}.
   */
  static final class SimpleWorkerPoolConfig extends GenericKeyedObjectPoolConfig<Worker> {
    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SimpleWorkerPoolConfig that = (SimpleWorkerPoolConfig) o;
      return getBlockWhenExhausted() == that.getBlockWhenExhausted()
          && getFairness() == that.getFairness()
          && getJmxEnabled() == that.getJmxEnabled()
          && getLifo() == that.getLifo()
          && getMaxWaitMillis() == that.getMaxWaitMillis()
          && getMinEvictableIdleTimeMillis() == that.getMinEvictableIdleTimeMillis()
          && getNumTestsPerEvictionRun() == that.getNumTestsPerEvictionRun()
          && getSoftMinEvictableIdleTimeMillis() == that.getSoftMinEvictableIdleTimeMillis()
          && getTestOnBorrow() == that.getTestOnBorrow()
          && getTestOnCreate() == that.getTestOnCreate()
          && getTestOnReturn() == that.getTestOnReturn()
          && getTestWhileIdle() == that.getTestWhileIdle()
          && getTimeBetweenEvictionRunsMillis() == that.getTimeBetweenEvictionRunsMillis()
          && getMaxIdlePerKey() == that.getMaxIdlePerKey()
          && getMaxTotal() == that.getMaxTotal()
          && getMaxTotalPerKey() == that.getMaxTotalPerKey()
          && getMinIdlePerKey() == that.getMinIdlePerKey()
          && Objects.equals(getEvictionPolicyClassName(), that.getEvictionPolicyClassName())
          && Objects.equals(getJmxNameBase(), that.getJmxNameBase())
          && Objects.equals(getJmxNamePrefix(), that.getJmxNamePrefix());
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          getBlockWhenExhausted(),
          getFairness(),
          getJmxEnabled(),
          getLifo(),
          getMaxWaitMillis(),
          getMinEvictableIdleTimeMillis(),
          getNumTestsPerEvictionRun(),
          getSoftMinEvictableIdleTimeMillis(),
          getTestOnBorrow(),
          getTestOnCreate(),
          getTestOnReturn(),
          getTestWhileIdle(),
          getTimeBetweenEvictionRunsMillis(),
          getMaxIdlePerKey(),
          getMaxTotal(),
          getMaxTotalPerKey(),
          getMinIdlePerKey(),
          getEvictionPolicyClassName(),
          getJmxNameBase(),
          getJmxNamePrefix());
    }
  }
}
