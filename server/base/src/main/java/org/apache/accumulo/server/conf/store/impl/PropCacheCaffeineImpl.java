/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.conf.store.impl;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.util.cache.Caches;
import org.apache.accumulo.core.util.cache.Caches.CacheName;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.server.conf.codec.VersionedProperties;
import org.apache.accumulo.server.conf.store.PropCache;
import org.apache.accumulo.server.conf.store.PropStoreKey;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;

public class PropCacheCaffeineImpl implements PropCache {

  public static final TimeUnit BASE_TIME_UNITS = TimeUnit.MINUTES;

  public static final int EXPIRE_MIN = 60;
  private static final Logger log = LoggerFactory.getLogger(PropCacheCaffeineImpl.class);
  private static final Executor executor =
      ThreadPools.getServerThreadPools().getPoolBuilder("caffeine.prop.cache.tasks")
          .numCoreThreads(1).numMaxThreads(20).withTimeOut(60L, SECONDS).build();

  private final LoadingCache<PropStoreKey,VersionedProperties> cache;

  private PropCacheCaffeineImpl(final CacheLoader<PropStoreKey,VersionedProperties> cacheLoader,
      final Ticker ticker, boolean runTasksInline) {
    Caffeine<Object,Object> caffeine =
        Caches.getInstance().createNewBuilder(CacheName.PROP_CACHE, true)
            .expireAfterAccess(EXPIRE_MIN, BASE_TIME_UNITS);
    if (runTasksInline) {
      caffeine.executor(Runnable::run);
    } else {
      caffeine.executor(executor);
    }
    if (ticker != null) {
      caffeine.ticker(ticker);
    }
    cache = caffeine.evictionListener(this::evictionNotifier).build(cacheLoader);
  }

  void evictionNotifier(PropStoreKey propStoreKey, VersionedProperties value, RemovalCause cause) {
    log.trace("Evicted: ID: {} was evicted from cache. Reason: {}", propStoreKey, cause);
  }

  @Override
  public @Nullable VersionedProperties get(PropStoreKey propStoreKey) {
    log.trace("Called get() for {}", propStoreKey);
    try {
      return cache.get(propStoreKey);
    } catch (Exception ex) {
      log.info("Cache failed to retrieve properties for: " + propStoreKey, ex);
      return null;
    }
  }

  @Override
  public void remove(PropStoreKey propStoreKey) {
    log.trace("clear {} from cache", propStoreKey);
    cache.invalidate(propStoreKey);
  }

  @Override
  public void removeAll() {
    cache.invalidateAll();
  }

  /**
   * Retrieve the version properties if present in the cache, otherwise return null. This prevents
   * caching the properties and should be used when properties will be updated and then committed to
   * the backend store. The process that is updating the values may not need them for additional
   * processing so there is no reason to store them in the cache at this time. If they are used, a
   * normal cache get will load the property into the cache.
   *
   * @param propStoreKey the property id
   * @return the version properties if cached, otherwise return null.
   */
  public @Nullable VersionedProperties getIfCached(PropStoreKey propStoreKey) {
    return cache.getIfPresent(propStoreKey);
  }

  public static class Builder {
    private final ZooPropLoader zooPropLoader;
    private Ticker ticker = null;
    private boolean runTasksInline = false;

    public Builder(final ZooPropLoader zooPropLoader) {
      Objects.requireNonNull(zooPropLoader, "A PropStoreChangeMonitor must be provided");
      this.zooPropLoader = zooPropLoader;
    }

    public PropCacheCaffeineImpl build() {
      return new PropCacheCaffeineImpl(zooPropLoader, ticker, runTasksInline);
    }

    public Builder forTests(final Ticker ticker) {
      this.ticker = ticker;
      this.runTasksInline = true;
      return this;
    }
  }

}
