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
package org.apache.accumulo.test.fate;

import static org.apache.accumulo.test.fate.FateTestUtil.TEST_FATE_OP;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.admin.NewTableConfiguration;
import org.apache.accumulo.core.client.admin.servers.ServerId;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.conf.DefaultConfiguration;
import org.apache.accumulo.core.fate.AbstractFateStore;
import org.apache.accumulo.core.fate.AdminUtil;
import org.apache.accumulo.core.fate.Fate;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.FateInstanceType;
import org.apache.accumulo.core.fate.FateStore;
import org.apache.accumulo.core.fate.ReadOnlyFateStore;
import org.apache.accumulo.core.fate.user.UserFateStore;
import org.apache.accumulo.core.fate.zookeeper.MetaFateStore;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil;
import org.apache.accumulo.core.iterators.IteratorUtil;
import org.apache.accumulo.core.lock.ServiceLockPaths.AddressSelector;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.zookeeper.ZooSession;
import org.apache.accumulo.harness.SharedMiniClusterBase;
import org.apache.accumulo.minicluster.ServerType;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloClusterImpl.ProcessInfo;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.util.Admin;
import org.apache.accumulo.server.util.fateCommand.FateSummaryReport;
import org.apache.accumulo.server.util.fateCommand.FateTxnDetails;
import org.apache.accumulo.test.fate.MultipleStoresITBase.LatchTestEnv;
import org.apache.accumulo.test.fate.MultipleStoresITBase.LatchTestRepo;
import org.apache.accumulo.test.functional.ReadWriteIT;
import org.apache.accumulo.test.functional.SlowIterator;
import org.apache.accumulo.test.util.Wait;
import org.easymock.EasyMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class FateOpsCommandsITBase extends SharedMiniClusterBase
    implements FateTestRunner<LatchTestEnv> {
  private static final Logger log = LoggerFactory.getLogger(FateOpsCommandsITBase.class);
  protected final Set<String> fateOpsToCleanup = new HashSet<>();

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(3);
  }

  @BeforeAll
  public static void beforeAllSetup() throws Exception {
    SharedMiniClusterBase.startMiniCluster();
    // Occasionally, the summary/print cmds will see a COMMIT_COMPACTION transaction which was
    // initiated on starting the manager, causing the test to fail. Stopping the compactor fixes
    // this issue.
    getCluster().getClusterControl().stopAllServers(ServerType.COMPACTOR);
    Wait.waitFor(() -> getCluster().getServerContext().getServerPaths()
        .getCompactor(rg -> true, AddressSelector.all(), true).isEmpty(), 60_000);
  }

  @AfterAll
  public static void afterAllTeardown() {
    SharedMiniClusterBase.stopMiniCluster();
  }

  @Test
  public void testFateSummaryCommand() throws Exception {
    executeTest(this::testFateSummaryCommand);
  }

  protected void testFateSummaryCommand(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Configure Fate
    Fate<LatchTestEnv> fate = initFateNoDeadResCleaner(store);

    try {
      // validate blank report, no transactions have started
      ProcessInfo p = getCluster().exec(Admin.class, "fate", "--summary", "-j");
      assertEquals(0, p.getProcess().waitFor());
      String result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      FateSummaryReport report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertTrue(report.getStatusCounts().isEmpty());
      assertTrue(report.getStepCounts().isEmpty());
      assertTrue(report.getCmdCounts().isEmpty());
      assertTrue(report.getStatusFilterNames().isEmpty());
      assertTrue(report.getInstanceTypesFilterNames().isEmpty());
      assertTrue(report.getFateIdFilter().isEmpty());
      validateFateDetails(report.getFateDetails(), 0, null);

      // create Fate transactions
      FateId fateId1 = fate.startTransaction();
      FateId fateId2 = fate.startTransaction();
      List<String> fateIdsStarted = List.of(fateId1.canonical(), fateId2.canonical());

      // validate no filters
      p = getCluster().exec(Admin.class, "fate", "--summary", "-j");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertFalse(report.getStatusCounts().isEmpty());
      assertFalse(report.getStepCounts().isEmpty());
      assertFalse(report.getCmdCounts().isEmpty());
      assertTrue(report.getStatusFilterNames().isEmpty());
      assertTrue(report.getInstanceTypesFilterNames().isEmpty());
      assertTrue(report.getFateIdFilter().isEmpty());
      validateFateDetails(report.getFateDetails(), 2, fateIdsStarted);

      /*
       * Test filtering by FateIds
       */

      // validate filtering by both transactions
      p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), fateId2.canonical(),
          "--summary", "-j");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertFalse(report.getStatusCounts().isEmpty());
      assertFalse(report.getStepCounts().isEmpty());
      assertFalse(report.getCmdCounts().isEmpty());
      assertTrue(report.getStatusFilterNames().isEmpty());
      assertTrue(report.getInstanceTypesFilterNames().isEmpty());
      assertEquals(2, report.getFateIdFilter().size());
      assertTrue(report.getFateIdFilter().containsAll(fateIdsStarted));
      validateFateDetails(report.getFateDetails(), 2, fateIdsStarted);

      // validate filtering by just one transaction
      p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), "--summary", "-j");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertFalse(report.getStatusCounts().isEmpty());
      assertFalse(report.getStepCounts().isEmpty());
      assertFalse(report.getCmdCounts().isEmpty());
      assertTrue(report.getStatusFilterNames().isEmpty());
      assertTrue(report.getInstanceTypesFilterNames().isEmpty());
      assertEquals(1, report.getFateIdFilter().size());
      assertTrue(report.getFateIdFilter().contains(fateId1.canonical()));
      validateFateDetails(report.getFateDetails(), 1, fateIdsStarted);

      // validate filtering by non-existent transaction
      FateId fakeFateId = FateId.from(store.type(), UUID.randomUUID());
      p = getCluster().exec(Admin.class, "fate", fakeFateId.canonical(), "--summary", "-j");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertFalse(report.getStatusCounts().isEmpty());
      assertFalse(report.getStepCounts().isEmpty());
      assertFalse(report.getCmdCounts().isEmpty());
      assertTrue(report.getStatusFilterNames().isEmpty());
      assertTrue(report.getInstanceTypesFilterNames().isEmpty());
      assertEquals(1, report.getFateIdFilter().size());
      assertTrue(report.getFateIdFilter().contains(fakeFateId.canonical()));
      validateFateDetails(report.getFateDetails(), 0, fateIdsStarted);

      /*
       * Test filtering by States
       */

      // validate status filter by including only FAILED transactions, should be none
      p = getCluster().exec(Admin.class, "fate", "--summary", "-j", "-s", "FAILED");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertFalse(report.getStatusCounts().isEmpty());
      assertFalse(report.getStepCounts().isEmpty());
      assertFalse(report.getCmdCounts().isEmpty());
      assertEquals(Set.of("FAILED"), report.getStatusFilterNames());
      assertTrue(report.getInstanceTypesFilterNames().isEmpty());
      assertTrue(report.getFateIdFilter().isEmpty());
      validateFateDetails(report.getFateDetails(), 0, fateIdsStarted);

      // validate status filter by including only NEW transactions, should be 2
      p = getCluster().exec(Admin.class, "fate", "--summary", "-j", "-s", "NEW");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertFalse(report.getStatusCounts().isEmpty());
      assertFalse(report.getStepCounts().isEmpty());
      assertFalse(report.getCmdCounts().isEmpty());
      assertEquals(Set.of("NEW"), report.getStatusFilterNames());
      assertTrue(report.getInstanceTypesFilterNames().isEmpty());
      assertTrue(report.getFateIdFilter().isEmpty());
      validateFateDetails(report.getFateDetails(), 2, fateIdsStarted);

      /*
       * Test filtering by FateInstanceType
       */

      // validate FateInstanceType filter by only including transactions with META filter
      p = getCluster().exec(Admin.class, "fate", "--summary", "-j", "-t", "META");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertFalse(report.getStatusCounts().isEmpty());
      assertFalse(report.getStepCounts().isEmpty());
      assertFalse(report.getCmdCounts().isEmpty());
      assertTrue(report.getStatusFilterNames().isEmpty());
      assertEquals(Set.of("META"), report.getInstanceTypesFilterNames());
      assertTrue(report.getFateIdFilter().isEmpty());
      if (store.type() == FateInstanceType.META) {
        validateFateDetails(report.getFateDetails(), 2, fateIdsStarted);
      } else { // USER
        validateFateDetails(report.getFateDetails(), 0, fateIdsStarted);
      }

      // validate FateInstanceType filter by only including transactions with USER filter
      p = getCluster().exec(Admin.class, "fate", "--summary", "-j", "-t", "USER");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
          .collect(Collectors.joining("\n"));
      report = FateSummaryReport.fromJson(result);
      assertNotNull(report);
      assertNotEquals(0, report.getReportTime());
      assertFalse(report.getStatusCounts().isEmpty());
      assertFalse(report.getStepCounts().isEmpty());
      assertFalse(report.getCmdCounts().isEmpty());
      assertTrue(report.getStatusFilterNames().isEmpty());
      assertEquals(Set.of("USER"), report.getInstanceTypesFilterNames());
      assertTrue(report.getFateIdFilter().isEmpty());
      if (store.type() == FateInstanceType.META) {
        validateFateDetails(report.getFateDetails(), 0, fateIdsStarted);
      } else { // USER
        validateFateDetails(report.getFateDetails(), 2, fateIdsStarted);
      }
    } finally {
      fate.shutdown(1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testFateSummaryCommandPlainText() throws Exception {
    executeTest(this::testFateSummaryCommandPlainText);
  }

  protected void testFateSummaryCommandPlainText(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Configure Fate
    Fate<LatchTestEnv> fate = initFateNoDeadResCleaner(store);

    try {
      // Start some transactions
      FateId fateId1 = fate.startTransaction();
      FateId fateId2 = fate.startTransaction();

      ProcessInfo p = getCluster().exec(Admin.class, "fate", fateId1.canonical(),
          fateId2.canonical(), "--summary", "-s", "NEW", "-t", store.type().name());
      assertEquals(0, p.getProcess().waitFor());
      String result = p.readStdOut();
      assertTrue(result.contains("Status Filters: [NEW]"));
      assertTrue(result
          .contains("Fate ID Filters: [" + fateId1.canonical() + ", " + fateId2.canonical() + "]")
          || result.contains(
              "Fate ID Filters: [" + fateId2.canonical() + ", " + fateId1.canonical() + "]"));
      assertTrue(result.contains("Instance Types Filters: [" + store.type().name() + "]"));
    } finally {
      fate.shutdown(1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testFatePrintCommand() throws Exception {
    executeTest(this::testFatePrintCommand);
  }

  protected void testFatePrintCommand(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Configure Fate
    Fate<LatchTestEnv> fate = initFateNoDeadResCleaner(store);

    try {
      // validate no transactions
      ProcessInfo p = getCluster().exec(Admin.class, "fate", "--print");
      assertEquals(0, p.getProcess().waitFor());
      String result = p.readStdOut();
      assertTrue(result.contains(" 0 transactions"));

      // create Fate transactions
      FateId fateId1 = fate.startTransaction();
      FateId fateId2 = fate.startTransaction();

      // Get all transactions. Should be 2 FateIds with a NEW status
      p = getCluster().exec(Admin.class, "fate", "--print");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      Map<String,String> fateIdsFromResult = getFateIdsFromPrint(result);
      assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
          fateIdsFromResult);

      /*
       * Test filtering by States
       */

      // Filter by NEW state
      p = getCluster().exec(Admin.class, "fate", "--print", "-s", "NEW");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      fateIdsFromResult = getFateIdsFromPrint(result);
      assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
          fateIdsFromResult);

      // Filter by FAILED state
      p = getCluster().exec(Admin.class, "fate", "--print", "-s", "FAILED");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      fateIdsFromResult = getFateIdsFromPrint(result);
      assertTrue(fateIdsFromResult.isEmpty());

      /*
       * Test filtering by FateIds
       */

      // Filter by one FateId
      p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), "--print");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      fateIdsFromResult = getFateIdsFromPrint(result);
      assertEquals(Map.of(fateId1.canonical(), "NEW"), fateIdsFromResult);

      // Filter by both FateIds
      p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), fateId2.canonical(),
          "--print");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      fateIdsFromResult = getFateIdsFromPrint(result);
      assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
          fateIdsFromResult);

      // Filter by non-existent FateId
      FateId fakeFateId = FateId.from(store.type(), UUID.randomUUID());
      p = getCluster().exec(Admin.class, "fate", fakeFateId.canonical(), "--print");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      fateIdsFromResult = getFateIdsFromPrint(result);
      assertEquals(0, fateIdsFromResult.size());

      /*
       * Test filtering by FateInstanceType
       */

      // Test filter by USER FateInstanceType
      p = getCluster().exec(Admin.class, "fate", "--print", "-t", "USER");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      fateIdsFromResult = getFateIdsFromPrint(result);
      if (store.type() == FateInstanceType.META) {
        assertTrue(fateIdsFromResult.isEmpty());
      } else { // USER
        assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
            fateIdsFromResult);
      }

      // Test filter by META FateInstanceType
      p = getCluster().exec(Admin.class, "fate", "--print", "-t", "META");
      assertEquals(0, p.getProcess().waitFor());
      result = p.readStdOut();
      fateIdsFromResult = getFateIdsFromPrint(result);
      if (store.type() == FateInstanceType.META) {
        assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
            fateIdsFromResult);
      } else { // USER
        assertTrue(fateIdsFromResult.isEmpty());
      }
    } finally {
      fate.shutdown(1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testTransactionNameAndStep() throws Exception {
    executeTest(this::testTransactionNameAndStep);
  }

  protected void testTransactionNameAndStep(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Since the other tests just use NEW transactions for simplicity, there are some fields of the
    // summary and print outputs which are null and not tested for (transaction name and transaction
    // step). This test uses seeded/in progress transactions to test that the summary and print
    // commands properly output these fields.
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      final String table = getUniqueNames(1)[0];

      IteratorSetting is = new IteratorSetting(1, SlowIterator.class);
      is.addOption("sleepTime", "10000");

      NewTableConfiguration cfg = new NewTableConfiguration();
      var majcScope = EnumSet.of(IteratorUtil.IteratorScope.majc);
      cfg.attachIterator(is, majcScope);
      client.tableOperations().create(table, cfg);
      client.tableOperations().attachIterator(SystemTables.METADATA.tableName(), is, majcScope);

      try {
        ReadWriteIT.ingest(client, 10, 10, 10, 0, table);
        client.tableOperations().flush(table, null, null, true);
        client.tableOperations().flush(SystemTables.METADATA.tableName(), null, null, true);

        if (store.type() == FateInstanceType.USER) {
          // create USER FATE transactions
          client.tableOperations().compact(table, null, null, false, false);
          client.tableOperations().compact(table, null, null, false, false);
        } else {
          // create META FATE transactions
          client.tableOperations().compact(SystemTables.METADATA.tableName(), null, null, false,
              false);
          client.tableOperations().compact(SystemTables.METADATA.tableName(), null, null, false,
              false);
        }
        List<String> fateIdsStarted = new ArrayList<>();

        ProcessInfo p = getCluster().exec(Admin.class, "fate", "--summary", "-j");
        assertEquals(0, p.getProcess().waitFor());

        String result = p.readStdOut();
        result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
            .collect(Collectors.joining("\n"));
        FateSummaryReport report = FateSummaryReport.fromJson(result);

        // Validate transaction name and transaction step from summary command

        for (FateTxnDetails d : report.getFateDetails()) {
          assertEquals("TABLE_COMPACT", d.getFateOp());
          assertEquals("CompactionDriver", d.getStep());
          fateIdsStarted.add(d.getFateId());
        }
        assertEquals(2, fateIdsStarted.size());

        p = getCluster().exec(Admin.class, "fate", "--print");
        assertEquals(0, p.getProcess().waitFor());
        result = p.readStdOut();

        // Validate transaction name and transaction step from print command

        String[] lines = result.split("\n");
        // Filter out the result to just include the info about the transactions
        List<String> transactionInfo = Arrays.stream(lines).filter(
            line -> line.contains(fateIdsStarted.get(0)) || line.contains(fateIdsStarted.get(1)))
            .collect(Collectors.toList());
        assertEquals(2, transactionInfo.size());
        for (String info : transactionInfo) {
          assertTrue(info.contains("TABLE_COMPACT"));
          assertTrue(info.contains("op: CompactionDriver"));
        }
      } finally {
        client.tableOperations().removeIterator(SystemTables.METADATA.tableName(), is.getName(),
            majcScope);
        if (store.type() == FateInstanceType.USER) {
          client.tableOperations().cancelCompaction(table);
        } else {
          client.tableOperations().cancelCompaction(SystemTables.METADATA.tableName());
        }
        client.tableOperations().delete(table);
      }
    }
  }

  @Test
  public void testFateCancelCommand() throws Exception {
    executeTest(this::testFateCancelCommand);
  }

  protected void testFateCancelCommand(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Configure Fate
    Fate<LatchTestEnv> fate = initFateNoDeadResCleaner(store);

    try {
      // Start some transactions
      FateId fateId1 = fate.startTransaction();
      FateId fateId2 = fate.startTransaction();

      // Check that summary output lists both the transactions with a NEW status
      Map<String,String> fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
          fateIdsFromSummary);

      // Cancel the first transaction and ensure that it was cancelled
      ProcessInfo p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), "--cancel");
      assertEquals(0, p.getProcess().waitFor());
      String result = p.readStdOut();

      assertTrue(result
          .contains("transaction " + fateId1.canonical() + " was cancelled or already completed"));
      fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId1.canonical(), "FAILED", fateId2.canonical(), "NEW"),
          fateIdsFromSummary);
    } finally {
      fate.shutdown(1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testFateFailCommandTimeout() throws Exception {
    try {
      stopManagerAndExecuteTest(this::testFateFailCommandTimeout);
    } finally {
      // restart the manager for the next tests
      startManager();
    }
  }

  protected void testFateFailCommandTimeout(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Configure Fate
    LatchTestEnv env = new LatchTestEnv();
    FastFate<LatchTestEnv> fate = initFateWithDeadResCleaner(store, env);

    try {
      // Start some transactions
      FateId fateId1 = fate.startTransaction();
      FateId fateId2 = fate.startTransaction();

      // Check that summary output lists both the transactions with a NEW status
      Map<String,String> fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
          fateIdsFromSummary);

      // Seed the transaction with the latch repo, so we can have an IN_PROGRESS transaction
      fate.seedTransaction(TEST_FATE_OP, fateId1, new LatchTestRepo(), true, "test");
      // Wait for 'fate' to reserve fateId1 (will be IN_PROGRESS on fateId1)
      Wait.waitFor(() -> env.numWorkers.get() == 1);

      // Try to fail fateId1
      // This should not work as it is already reserved and being worked on by our running FATE
      // ('fate'). Admin should try to reserve it for a bit, but should fail and exit
      ProcessInfo p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), "--fail");
      assertEquals(0, p.getProcess().waitFor());
      String result = p.readStdOut();

      assertTrue(result.contains("Could not fail " + fateId1 + " in a reasonable time"));
      fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId1.canonical(), "IN_PROGRESS", fateId2.canonical(), "NEW"),
          fateIdsFromSummary);
    } finally {
      // Finish work and shutdown
      env.workersLatch.countDown();
      fate.shutdown(1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testFateFailCommandSuccess() throws Exception {
    executeTest(this::testFateFailCommandSuccess);
  }

  protected void testFateFailCommandSuccess(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Configure Fate
    Fate<LatchTestEnv> fate = initFateNoDeadResCleaner(store);

    try {
      // Start some transactions
      FateId fateId1 = fate.startTransaction();
      FateId fateId2 = fate.startTransaction();

      // Check that summary output lists both the transactions with a NEW status
      Map<String,String> fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
          fateIdsFromSummary);

      // Try to fail fateId1
      // This should work since nothing has fateId1 reserved (it is NEW)
      ProcessInfo p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), "--fail");
      assertEquals(0, p.getProcess().waitFor());
      String result = p.readStdOut();

      assertTrue(result.contains("Failing transaction: " + fateId1));
      fateIdsFromSummary = getFateIdsFromSummary();
      assertTrue(fateIdsFromSummary
          .equals(Map.of(fateId1.canonical(), "FAILED_IN_PROGRESS", fateId2.canonical(), "NEW"))
          || fateIdsFromSummary
              .equals(Map.of(fateId1.canonical(), "FAILED", fateId2.canonical(), "NEW")));
    } finally {
      fate.shutdown(1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testFateDeleteCommandTimeout() throws Exception {
    try {
      stopManagerAndExecuteTest(this::testFateDeleteCommandTimeout);
    } finally {
      // restart the manager for the next tests
      startManager();
    }
  }

  protected void testFateDeleteCommandTimeout(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Configure Fate
    LatchTestEnv env = new LatchTestEnv();
    FastFate<LatchTestEnv> fate = initFateWithDeadResCleaner(store, env);

    try {
      // Start some transactions
      FateId fateId1 = fate.startTransaction();
      FateId fateId2 = fate.startTransaction();

      // Check that summary output lists both the transactions with a NEW status
      Map<String,String> fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
          fateIdsFromSummary);

      // Seed the transaction with the latch repo, so we can have an IN_PROGRESS transaction
      fate.seedTransaction(TEST_FATE_OP, fateId1, new LatchTestRepo(), true, "test");
      // Wait for 'fate' to reserve fateId1 (will be IN_PROGRESS on fateId1)
      Wait.waitFor(() -> env.numWorkers.get() == 1);

      // Try to delete fateId1
      // This should not work as it is already reserved and being worked on by our running FATE
      // ('fate'). Admin should try to reserve it for a bit, but should fail and exit
      ProcessInfo p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), "--delete");
      assertEquals(0, p.getProcess().waitFor());
      String result = p.readStdOut();

      assertTrue(result.contains("Could not delete " + fateId1 + " in a reasonable time"));
      fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId1.canonical(), "IN_PROGRESS", fateId2.canonical(), "NEW"),
          fateIdsFromSummary);
    } finally {
      // Finish work and shutdown
      env.workersLatch.countDown();
      fate.shutdown(1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testFateDeleteCommandSuccess() throws Exception {
    executeTest(this::testFateDeleteCommandSuccess);
  }

  protected void testFateDeleteCommandSuccess(FateStore<LatchTestEnv> store, ServerContext sctx)
      throws Exception {
    // Configure Fate
    Fate<LatchTestEnv> fate = initFateNoDeadResCleaner(store);

    try {
      // Start some transactions
      FateId fateId1 = fate.startTransaction();
      FateId fateId2 = fate.startTransaction();

      // Check that summary output lists both the transactions with a NEW status
      Map<String,String> fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId1.canonical(), "NEW", fateId2.canonical(), "NEW"),
          fateIdsFromSummary);

      // Try to delete fateId1
      // This should work since nothing has fateId1 reserved (it is NEW)
      ProcessInfo p = getCluster().exec(Admin.class, "fate", fateId1.canonical(), "--delete");
      assertEquals(0, p.getProcess().waitFor());
      String result = p.readStdOut();

      assertTrue(result.contains("Deleting transaction: " + fateId1));
      fateIdsFromSummary = getFateIdsFromSummary();
      assertEquals(Map.of(fateId2.canonical(), "NEW"), fateIdsFromSummary);
    } finally {
      fate.shutdown(1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testFatePrintAndSummaryCommandsWithInProgressTxns() throws Exception {
    executeTest(this::testFatePrintAndSummaryCommandsWithInProgressTxns);
  }

  protected void testFatePrintAndSummaryCommandsWithInProgressTxns(FateStore<LatchTestEnv> store,
      ServerContext sctx) throws Exception {
    // This test was written for an issue with the 'admin fate --print' and 'admin fate --summary'
    // commands where transactions could complete mid-print causing the command to fail. These
    // commands first get a list of the transactions and then probe for info on the transactions.
    // If a transaction completed between getting the list and probing for info on that
    // transaction, the command would fail. This test ensures that this problem has been fixed
    // (if the tx no longer exists, it should just be ignored so the print/summary can complete).
    FateStore<LatchTestEnv> mockedStore;

    // This error was occurring in AdminUtil.getTransactionStatus(), so we will test this method.
    if (store.type().equals(FateInstanceType.USER)) {
      Method listMethod = UserFateStore.class.getMethod("list");
      mockedStore = EasyMock.createMockBuilder(UserFateStore.class)
          .withConstructor(ClientContext.class, String.class, ZooUtil.LockID.class, Predicate.class)
          .withArgs(sctx, SystemTables.FATE.tableName(), null, null).addMockedMethod(listMethod)
          .createMock();
    } else {
      Method listMethod = MetaFateStore.class.getMethod("list");
      mockedStore = EasyMock.createMockBuilder(MetaFateStore.class)
          .withConstructor(ZooSession.class, ZooUtil.LockID.class, Predicate.class)
          .withArgs(sctx.getZooSession(), null, null).addMockedMethod(listMethod).createMock();
    }

    // 3 FateIds, two that exist and one that does not. We are simulating that a transaction that
    // doesn't exist is accessed in getTransactionStatus() and ensuring that this doesn't cause
    // the method to fail or have any unexpected behavior.
    FateId tx1 = store.create();
    FateId tx2 = FateId.from(store.type(), UUID.randomUUID());
    FateId tx3 = store.create();

    List<ReadOnlyFateStore.FateIdStatus> fateIdStatusList =
        List.of(createFateIdStatus(tx1), createFateIdStatus(tx2), createFateIdStatus(tx3));
    expect(mockedStore.list()).andReturn(fateIdStatusList.stream()).once();

    replay(mockedStore);

    AdminUtil.FateStatus status = null;
    try {
      status = AdminUtil.getTransactionStatus(Map.of(store.type(), mockedStore), null, null, null,
          new HashMap<>(), new HashMap<>());
    } catch (Exception e) {
      fail("An unexpected error occurred in getTransactionStatus():\n" + e);
    }

    verify(mockedStore);

    assertNotNull(status);
    // All three should be returned
    assertEquals(3, status.getTransactions().size());
    assertEquals(status.getTransactions().stream().map(AdminUtil.TransactionStatus::getFateId)
        .collect(Collectors.toList()), List.of(tx1, tx2, tx3));
    // The two real FateIds should have NEW status and the fake one should be UNKNOWN
    assertEquals(
        status.getTransactions().stream().map(AdminUtil.TransactionStatus::getStatus)
            .collect(Collectors.toList()),
        List.of(ReadOnlyFateStore.TStatus.NEW, ReadOnlyFateStore.TStatus.UNKNOWN,
            ReadOnlyFateStore.TStatus.NEW));
    // None of them should have a name since none of them were seeded with work
    assertEquals(status.getTransactions().stream().map(AdminUtil.TransactionStatus::getFateOp)
        .collect(Collectors.toList()), Arrays.asList(null, null, null));
    // None of them should have a Repo since none of them were seeded with work
    assertEquals(status.getTransactions().stream().map(AdminUtil.TransactionStatus::getTop)
        .collect(Collectors.toList()), Arrays.asList(null, null, null));
    // The FateId that doesn't exist should have a creation time of 0, the others should not
    List<Long> timeCreated = status.getTransactions().stream()
        .map(AdminUtil.TransactionStatus::getTimeCreated).collect(Collectors.toList());
    assertNotEquals(timeCreated.get(0), 0);
    assertEquals(timeCreated.get(1), 0);
    assertNotEquals(timeCreated.get(2), 0);
    // All should have the store.type() type
    assertEquals(status.getTransactions().stream().map(AdminUtil.TransactionStatus::getInstanceType)
        .collect(Collectors.toList()), List.of(store.type(), store.type(), store.type()));
  }

  private ReadOnlyFateStore.FateIdStatus createFateIdStatus(FateId fateId) {
    // We are only using the fateId from this, so null/empty is fine for the rest
    return new AbstractFateStore.FateIdStatusBase(fateId) {
      @Override
      public ReadOnlyFateStore.TStatus getStatus() {
        return null;
      }

      @Override
      public Optional<FateStore.FateReservation> getFateReservation() {
        return Optional.empty();
      }

      @Override
      public Optional<Fate.FateOperation> getFateOperation() {
        return Optional.empty();
      }
    };
  }

  /**
   *
   * @param printResult the output of the --print fate command
   * @return a map of each of the FateIds to their status using the output of --print
   */
  private Map<String,String> getFateIdsFromPrint(String printResult) {
    Map<String,String> fateIdToStatus = new HashMap<>();
    String lastFateIdSeen = null;
    String[] words = printResult.split(" ");
    for (String word : words) {
      if (FateId.isFateId(word)) {
        if (!fateIdToStatus.containsKey(word)) {
          lastFateIdSeen = word;
        } else {
          log.debug(
              "--print listed the same transaction more than once. This should not occur, failing");
          fail();
        }
      } else if (wordIsTStatus(word)) {
        fateIdToStatus.put(lastFateIdSeen, word);
      }
    }
    return fateIdToStatus;
  }

  /**
   *
   * @return a map of each of the FateIds to their status using the --summary command
   */
  private Map<String,String> getFateIdsFromSummary() throws Exception {
    ProcessInfo p = getCluster().exec(Admin.class, "fate", "--summary", "-j");
    assertEquals(0, p.getProcess().waitFor());
    String result = p.readStdOut();
    result = result.lines().filter(line -> !line.matches(".*(INFO|DEBUG|WARN|ERROR).*"))
        .collect(Collectors.joining("\n"));
    FateSummaryReport report = FateSummaryReport.fromJson(result);
    assertNotNull(report);
    Map<String,String> fateIdToStatus = new HashMap<>();
    report.getFateDetails().forEach((d) -> {
      fateIdToStatus.put(d.getFateId(), d.getStatus());
    });
    return fateIdToStatus;
  }

  /**
   * Validates the fate details of NEW transactions
   *
   * @param details the fate details from the {@link FateSummaryReport}
   * @param expDetailsSize the expected size of details
   * @param fateIdsStarted the list of fate ids that have been started
   */
  private void validateFateDetails(Set<FateTxnDetails> details, int expDetailsSize,
      List<String> fateIdsStarted) {
    assertEquals(expDetailsSize, details.size());
    for (FateTxnDetails d : details) {
      assertTrue(fateIdsStarted.contains(d.getFateId()));
      assertEquals("NEW", d.getStatus());
      assertEquals("?", d.getStep());
      assertEquals("?", d.getFateOp());
      assertNotEquals(0, d.getRunning());
      assertEquals("[]", d.getLocksHeld().toString());
      assertEquals("[]", d.getLocksWaiting().toString());
    }
  }

  protected FastFate<LatchTestEnv> initFateWithDeadResCleaner(FateStore<LatchTestEnv> store,
      LatchTestEnv env) {
    // Using FastFate so the cleanup will run often. This ensures that the cleanup will run when
    // there are reservations present and that the cleanup will not unexpectedly delete these live
    // reservations
    return new FastFate<>(env, store, true, Object::toString, DefaultConfiguration.getInstance());
  }

  protected Fate<LatchTestEnv> initFateNoDeadResCleaner(FateStore<LatchTestEnv> store) {
    return new Fate<>(new LatchTestEnv(), store, false, Object::toString,
        DefaultConfiguration.getInstance(), new ScheduledThreadPoolExecutor(2));
  }

  private boolean wordIsTStatus(String word) {
    try {
      ReadOnlyFateStore.TStatus.valueOf(word);
    } catch (IllegalArgumentException e) {
      return false;
    }
    return true;
  }

  /**
   * Stop the MANAGER. For some of our tests, we want to be able to seed transactions with our own
   * test repos. We want our fate to reserve these transactions (and not the real fates running in
   * the Manager as that will lead to exceptions since the real fates wouldn't be able to handle our
   * test repos). So, we essentially have the fates created here acting as the real fates: they have
   * the same threads running that the real fates would, use a fate store with a ZK lock, use the
   * same locations to store fate data that the Manager does, and are running in a separate process
   * from the Admin process. Note that we cannot simply use different locations for our fate data
   * from Manager to keep our test env separate from Manager. Admin uses the real fate data
   * locations, so our test must also use the real locations.
   */
  protected void stopManager() throws IOException {
    getCluster().getClusterControl().stopAllServers(ServerType.MANAGER);
    Wait.waitFor(() -> getCluster().getServerContext().instanceOperations()
        .getServers(ServerId.Type.MANAGER).isEmpty(), 60_000);
  }

  protected void startManager() throws IOException {
    getCluster().getClusterControl().startAllServers(ServerType.MANAGER);
    Wait.waitFor(() -> !getCluster().getServerContext().instanceOperations()
        .getServers(ServerId.Type.MANAGER).isEmpty(), 60_000);
  }

  protected void cleanupFateOps() throws Exception {
    List<String> args = new ArrayList<>();
    args.add("fate");
    args.addAll(fateOpsToCleanup);
    args.add("--delete");
    ProcessInfo p = getCluster().exec(Admin.class, args.toArray(new String[0]));
    assertEquals(0, p.getProcess().waitFor());
  }
}
