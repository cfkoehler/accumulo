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
package org.apache.accumulo.test.conf.store;

import static org.apache.accumulo.harness.AccumuloITBase.ZOOKEEPER_TESTING_SERVER;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.DefaultConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.data.InstanceId;
import org.apache.accumulo.core.data.NamespaceId;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil;
import org.apache.accumulo.core.zookeeper.ZooSession;
import org.apache.accumulo.core.zookeeper.ZooSession.ZKUtil;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.conf.NamespaceConfiguration;
import org.apache.accumulo.server.conf.SystemConfiguration;
import org.apache.accumulo.server.conf.ZooBasedConfiguration;
import org.apache.accumulo.server.conf.store.NamespacePropKey;
import org.apache.accumulo.server.conf.store.PropChangeListener;
import org.apache.accumulo.server.conf.store.PropStore;
import org.apache.accumulo.server.conf.store.PropStoreKey;
import org.apache.accumulo.server.conf.store.SystemPropKey;
import org.apache.accumulo.server.conf.store.TablePropKey;
import org.apache.accumulo.server.conf.store.impl.ZooPropStore;
import org.apache.accumulo.test.zookeeper.ZooKeeperTestingServer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Ticker;

@Tag(ZOOKEEPER_TESTING_SERVER)
public class ZooBasedConfigIT {

  private static final Logger log = LoggerFactory.getLogger(ZooBasedConfigIT.class);
  private static final InstanceId INSTANCE_ID = InstanceId.of(UUID.randomUUID());
  private static ZooKeeperTestingServer testZk = null;
  private static ZooReaderWriter zrw;
  private static ZooSession zk;
  private ServerContext context;

  // fake ids
  private final NamespaceId nsId = NamespaceId.of("nsIdForTest");
  private final TableId tidA = TableId.of("A");
  private final TableId tidB = TableId.of("B");

  private TestTicker ticker;
  private PropStore propStore;
  private AccumuloConfiguration parent;

  @TempDir
  private static File tempDir;

  @BeforeAll
  public static void setupZk() throws Exception {
    testZk = new ZooKeeperTestingServer(tempDir);
    // prop store uses a chrooted ZK, so it is relocatable, but create a convenient empty node to
    // work in for the test, so we can easily clean it up after each test
    try (var zkInit = testZk.newClient()) {
      zkInit.create("/instanceRoot", null, ZooUtil.PUBLIC, CreateMode.PERSISTENT);
    }
    // create a chrooted client for the tests to use
    zk = testZk.newClient("/instanceRoot");
    zrw = zk.asReaderWriter();
  }

  @AfterAll
  public static void shutdownZK() throws Exception {
    try {
      zk.close();
    } finally {
      testZk.close();
    }
  }

  @BeforeEach
  public void initPaths() throws Exception {
    context = createMock(ServerContext.class);
    expect(context.getZooSession()).andReturn(zk);

    zk.create(Constants.ZTABLES, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

    zk.create(Constants.ZTABLES + "/" + tidA.canonical(), new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.PERSISTENT);
    zk.create(Constants.ZTABLES + "/" + tidB.canonical(), new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.PERSISTENT);

    zk.create(Constants.ZNAMESPACES, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.PERSISTENT);
    zk.create(Constants.ZNAMESPACES + "/" + nsId.canonical(), new byte[0],
        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

    ticker = new TestTicker();

    reset(context);

    // setup context mock with enough to create prop store
    expect(context.getInstanceID()).andReturn(INSTANCE_ID).anyTimes();

    replay(context);

    propStore = ZooPropStore.initialize(zk);

    reset(context);

    // parent = createMock(AccumuloConfiguration.class);
    parent = DefaultConfiguration.getInstance();

    // setup context mock with prop store and the rest of the env needed.
    expect(context.getInstanceID()).andReturn(INSTANCE_ID).anyTimes();
    expect(context.getZooKeepersSessionTimeOut()).andReturn(zk.getSessionTimeout()).anyTimes();
    expect(context.getPropStore()).andReturn(propStore).anyTimes();
    expect(context.getSiteConfiguration()).andReturn(SiteConfiguration.empty().build()).anyTimes();

  }

  @AfterEach
  public void cleanupZnodes() throws Exception {
    for (var child : zk.getChildren("/", null)) {
      ZKUtil.deleteRecursive(zk, "/" + child);
    }
    verify(context);
  }

  /**
   * The sys config encoded node will not exist and there are no properties set - an empty encoded
   * node should be created.
   */
  @Test
  public void upgradeSysTestNoProps() throws Exception {
    replay(context);
    // force create empty sys config node.
    zk.create(Constants.ZCONFIG, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    var propKey = SystemPropKey.of();
    ZooBasedConfiguration zbc = new SystemConfiguration(context, propKey, parent);
    assertNotNull(zbc);
  }

  @Test
  public void getPropertiesTest() {

    replay(context);

    propStore.create(SystemPropKey.of(), Map.of(Property.TABLE_BLOOM_ENABLED.getKey(), "true"));

    var sysPropKey = SystemPropKey.of();

    ZooBasedConfiguration zbc = new SystemConfiguration(context, sysPropKey, parent);

    assertNotNull(zbc.getSnapshot());
    assertEquals("true", zbc.get(Property.TABLE_BLOOM_ENABLED));

  }

  @Test
  public void failOnDuplicateCreate() {

    replay(context);

    var sysPropKey = SystemPropKey.of();

    propStore.create(sysPropKey, Map.of());
    assertThrows(IllegalStateException.class, () -> propStore.create(sysPropKey, Map.of()));

    propStore.create(NamespacePropKey.of(nsId), Map.of());
    assertThrows(IllegalStateException.class,
        () -> propStore.create(NamespacePropKey.of(nsId), Map.of()));

    propStore.create(TablePropKey.of(tidA), Map.of());
    assertThrows(IllegalStateException.class,
        () -> propStore.create(TablePropKey.of(tidA), Map.of()));
  }

  @Test
  public void getPropertiesFromParentTest() {

    replay(context);

    var sysPropKey = SystemPropKey.of();

    propStore.create(sysPropKey, Map.of());

    propStore.create(NamespacePropKey.of(nsId), Map.of());

    ZooBasedConfiguration zbc = new NamespaceConfiguration(context, nsId, parent);

    assertNotNull(zbc.getSnapshot());
    assertEquals("false", zbc.get(Property.TABLE_BLOOM_ENABLED));
  }

  @Test
  public void throwOnNoNode() {
    replay(context);
    var nsConf = new NamespaceConfiguration(context, nsId, parent);
    assertThrows(IllegalStateException.class, () -> nsConf.getSnapshot());
  }

  @Test
  public void expireTest() throws Exception {

    // expect(parent.getUpdateCount()).andReturn(123L).anyTimes();
    replay(context);

    propStore.create(SystemPropKey.of(), Map.of(Property.TABLE_BLOOM_ENABLED.getKey(), "true"));

    var sysPropKey = SystemPropKey.of();

    TestListener testListener = new TestListener();
    propStore.registerAsListener(sysPropKey, testListener);

    ZooBasedConfiguration zbc = new SystemConfiguration(context, sysPropKey, parent);

    assertNotNull(zbc.getSnapshot());
    assertEquals("true", zbc.get(Property.TABLE_BLOOM_ENABLED));

    long updateCount = zbc.getUpdateCount();

    // advance well past unload period.
    ticker.advance(2, TimeUnit.HOURS);

    var tableBPropKey = TablePropKey.of(tidB);
    propStore.create(tableBPropKey, Map.of());
    Thread.sleep(150);

    int changeCount = testListener.getZkChangeCount();

    // force an "external update" directly in ZK - emulates a change external to the prop store.
    // just echoing the same data - but it will update the ZooKeeper node data version.
    Stat stat = new Stat();
    byte[] bytes = zrw.getData(sysPropKey.getPath(), stat);
    zrw.overwritePersistentData(sysPropKey.getPath(), bytes, stat.getVersion());

    // allow ZooKeeper notification time to propagate

    int retries = 5;
    do {
      Thread.sleep(25);
    } while (changeCount >= testListener.getZkChangeCount() && --retries > 0);

    assertTrue(changeCount < testListener.getZkChangeCount());

    // prop changed - but will not be loaded in cache.
    long updateCount2 = zbc.getUpdateCount();
    assertNotEquals(updateCount, updateCount2);

    // read will repopulate the cache.
    assertNotNull(zbc.getSnapshot());
    assertEquals("true", zbc.get(Property.TABLE_BLOOM_ENABLED));

    assertNotEquals(updateCount, zbc.getUpdateCount());
    assertEquals(updateCount2, zbc.getUpdateCount());
  }

  private static class TestListener implements PropChangeListener {

    private final AtomicInteger zkChangeCount = new AtomicInteger(0);
    private final AtomicInteger cacheChangeCount = new AtomicInteger(0);
    private final AtomicInteger deleteCount = new AtomicInteger(0);
    private final AtomicInteger connectionEventCount = new AtomicInteger(0);

    public int getZkChangeCount() {
      return zkChangeCount.get();
    }

    public int getCacheChangeCount() {
      return cacheChangeCount.get();
    }

    public int getDeleteCount() {
      return deleteCount.get();
    }

    public int getConnectionEventCount() {
      return connectionEventCount.get();
    }

    @Override
    public void zkChangeEvent(PropStoreKey propStoreKey) {
      log.debug("Received zkChangeEvent for {}", propStoreKey);
      zkChangeCount.incrementAndGet();
    }

    @Override
    public void cacheChangeEvent(PropStoreKey propStoreKey) {
      log.debug("Received cacheChangeEvent for {}", propStoreKey);
      cacheChangeCount.incrementAndGet();
    }

    @Override
    public void deleteEvent(PropStoreKey propStoreKey) {
      log.debug("Received deleteEvent for: {}", propStoreKey);
      deleteCount.incrementAndGet();
    }

    @Override
    public void connectionEvent() {
      log.debug("Received connectionEvent");
      connectionEventCount.incrementAndGet();
    }

    @Override
    public String toString() {
      return "TestListener{zkChangeCount=" + getZkChangeCount() + ", cacheChangeCount="
          + getCacheChangeCount() + ", deleteCount=" + getDeleteCount() + ", connectionEventCount="
          + getConnectionEventCount() + '}';
    }
  }

  private static class TestTicker implements Ticker {

    private final long startTime;
    private long elapsed;

    public TestTicker() {
      startTime = System.nanoTime();
      elapsed = 0L;
    }

    public void advance(final long value, final TimeUnit units) {
      elapsed += TimeUnit.NANOSECONDS.convert(value, units);
    }

    @Override
    public long read() {
      return startTime + elapsed;
    }
  }

}
