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
package org.apache.accumulo.test.fate.user;

import static org.apache.accumulo.core.fate.user.FateMutator.Status.ACCEPTED;
import static org.apache.accumulo.core.fate.user.FateMutator.Status.REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.ConditionalWriter;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.NewTableConfiguration;
import org.apache.accumulo.core.client.admin.TabletAvailability;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.FateInstanceType;
import org.apache.accumulo.core.fate.FateStore.FateReservation;
import org.apache.accumulo.core.fate.ReadOnlyFateStore;
import org.apache.accumulo.core.fate.user.FateMutatorImpl;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil;
import org.apache.accumulo.harness.SharedMiniClusterBase;
import org.apache.accumulo.test.fate.FateITBase.TestRepo;
import org.apache.accumulo.test.fate.FateTestRunner.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Suppliers;

public class FateMutatorImplIT_SimpleSuite extends SharedMiniClusterBase {

  Logger log = LoggerFactory.getLogger(FateMutatorImplIT_SimpleSuite.class);
  final NewTableConfiguration ntc =
      new NewTableConfiguration().withInitialTabletAvailability(TabletAvailability.HOSTED);

  @BeforeAll
  public static void setup() throws Exception {
    SharedMiniClusterBase.startMiniCluster();
  }

  @AfterAll
  public static void tearDown() {
    SharedMiniClusterBase.stopMiniCluster();
  }

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(5);
  }

  private Supplier<ConditionalWriter> createWriterSupplier(AccumuloClient client, String table) {
    return Suppliers.memoize(() -> {
      try {
        return client.createConditionalWriter(table);
      } catch (TableNotFoundException e) {
        throw new IllegalStateException();
      }
    });
  }

  @Test
  public void putRepo() throws Exception {
    final String table = getUniqueNames(1)[0];
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      client.tableOperations().create(table, ntc);

      ClientContext context = (ClientContext) client;

      var fateId = FateId.from(FateInstanceType.USER, UUID.randomUUID());

      Supplier<ConditionalWriter> writer = createWriterSupplier(client, table);

      // add some repos in order
      var fateMutator = new FateMutatorImpl<TestEnv>(context, table, fateId, writer);
      fateMutator.putRepo(100, new TestRepo("test")).mutate();
      var fateMutator1 = new FateMutatorImpl<TestEnv>(context, table, fateId, writer);
      fateMutator1.putRepo(99, new TestRepo("test")).mutate();
      var fateMutator2 = new FateMutatorImpl<TestEnv>(context, table, fateId, writer);
      fateMutator2.putRepo(98, new TestRepo("test")).mutate();

      // make sure we cant add a repo that has already been added
      var fateMutator3 = new FateMutatorImpl<TestEnv>(context, table, fateId, writer);
      assertThrows(IllegalStateException.class,
          () -> fateMutator3.putRepo(98, new TestRepo("test")).mutate(),
          "Repo in position 98 already exists. Expected to not be able to add it again.");
      var fateMutator4 = new FateMutatorImpl<TestEnv>(context, table, fateId, writer);
      assertThrows(IllegalStateException.class,
          () -> fateMutator4.putRepo(99, new TestRepo("test")).mutate(),
          "Repo in position 99 already exists. Expected to not be able to add it again.");
    }
  }

  @Test
  public void requireStatus() throws Exception {
    final String table = getUniqueNames(1)[0];
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      client.tableOperations().create(table, ntc);

      ClientContext context = (ClientContext) client;

      var fateId = FateId.from(FateInstanceType.USER, UUID.randomUUID());

      Supplier<ConditionalWriter> writer = createWriterSupplier(client, table);

      // use require status passing all statuses. without the status column present this should
      // fail
      assertThrows(IllegalStateException.class,
          () -> new FateMutatorImpl<>(context, table, fateId, writer)
              .requireStatus(ReadOnlyFateStore.TStatus.values())
              .putStatus(ReadOnlyFateStore.TStatus.NEW).mutate());
      assertEquals(0, client.createScanner(table).stream().count());
      var status = new FateMutatorImpl<>(context, table, fateId, writer)
          .requireStatus(ReadOnlyFateStore.TStatus.values())
          .putStatus(ReadOnlyFateStore.TStatus.NEW).tryMutate();
      assertEquals(REJECTED, status);
      assertEquals(0, client.createScanner(table).stream().count());

      // use require status without passing any statuses to require that the status column is
      // absent
      status = new FateMutatorImpl<>(context, table, fateId, writer).requireStatus()
          .putStatus(ReadOnlyFateStore.TStatus.NEW).tryMutate();
      assertEquals(ACCEPTED, status);

      // try again with requiring an absent status column. this time it should fail because we
      // just
      // put status NEW
      assertThrows(IllegalStateException.class,
          () -> new FateMutatorImpl<>(context, table, fateId, writer).requireStatus()
              .putStatus(ReadOnlyFateStore.TStatus.NEW).mutate(),
          "Expected to not be able to use requireStatus() without passing any statuses");
      status = new FateMutatorImpl<>(context, table, fateId, writer).requireStatus()
          .putStatus(ReadOnlyFateStore.TStatus.NEW).tryMutate();
      assertEquals(REJECTED, status,
          "Expected to not be able to use requireStatus() without passing any statuses");

      // now use require same with the current status, NEW passed in
      status = new FateMutatorImpl<>(context, table, fateId, writer)
          .requireStatus(ReadOnlyFateStore.TStatus.NEW)
          .putStatus(ReadOnlyFateStore.TStatus.SUBMITTED).tryMutate();
      assertEquals(ACCEPTED, status);

      // use require same with an array of statuses, none of which are the current status
      // (SUBMITTED)
      assertThrows(IllegalStateException.class,
          () -> new FateMutatorImpl<>(context, table, fateId, writer)
              .requireStatus(ReadOnlyFateStore.TStatus.NEW, ReadOnlyFateStore.TStatus.UNKNOWN)
              .putStatus(ReadOnlyFateStore.TStatus.SUBMITTED).mutate(),
          "Expected to not be able to use requireStatus() with statuses that do not match the current status");
      status = new FateMutatorImpl<>(context, table, fateId, writer)
          .requireStatus(ReadOnlyFateStore.TStatus.NEW, ReadOnlyFateStore.TStatus.UNKNOWN)
          .putStatus(ReadOnlyFateStore.TStatus.SUBMITTED).tryMutate();
      assertEquals(REJECTED, status,
          "Expected to not be able to use requireStatus() with statuses that do not match the current status");

      // use require same with an array of statuses, one of which is the current status
      // (SUBMITTED)
      status = new FateMutatorImpl<>(context, table, fateId, writer)
          .requireStatus(ReadOnlyFateStore.TStatus.UNKNOWN, ReadOnlyFateStore.TStatus.SUBMITTED)
          .putStatus(ReadOnlyFateStore.TStatus.IN_PROGRESS).tryMutate();
      assertEquals(ACCEPTED, status);

      // one more time check that we can use require same with the current status (IN_PROGRESS)
      status = new FateMutatorImpl<>(context, table, fateId, writer)
          .requireStatus(ReadOnlyFateStore.TStatus.IN_PROGRESS)
          .putStatus(ReadOnlyFateStore.TStatus.FAILED_IN_PROGRESS).tryMutate();
      assertEquals(ACCEPTED, status);
    }
  }

  @Test
  public void testReservations() throws Exception {
    final String table = getUniqueNames(1)[0];
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      client.tableOperations().create(table, ntc);

      ClientContext context = (ClientContext) client;

      var fateId = FateId.from(FateInstanceType.USER, UUID.randomUUID());
      var lockID = new ZooUtil.LockID("/locks", "L1", 50);
      var reservation = FateReservation.from(lockID, UUID.randomUUID());
      var wrongReservation = FateReservation.from(lockID, UUID.randomUUID());

      Supplier<ConditionalWriter> writer = createWriterSupplier(client, table);

      // Ensure that reserving is the only thing we can do
      var status = new FateMutatorImpl<>(context, table, fateId, writer).putUnreserveTx(reservation)
          .tryMutate();
      assertEquals(REJECTED, status);
      status = new FateMutatorImpl<>(context, table, fateId, writer).putReservedTx(reservation)
          .tryMutate();
      assertEquals(ACCEPTED, status);

      // Should not be able to reserve when it is already reserved
      status = new FateMutatorImpl<>(context, table, fateId, writer).putReservedTx(wrongReservation)
          .tryMutate();
      assertEquals(REJECTED, status);
      status = new FateMutatorImpl<>(context, table, fateId, writer).putReservedTx(reservation)
          .tryMutate();
      assertEquals(REJECTED, status);

      // Should be able to unreserve
      status = new FateMutatorImpl<>(context, table, fateId, writer)
          .putUnreserveTx(wrongReservation).tryMutate();
      assertEquals(REJECTED, status);
      status = new FateMutatorImpl<>(context, table, fateId, writer).putUnreserveTx(reservation)
          .tryMutate();
      assertEquals(ACCEPTED, status);
      status = new FateMutatorImpl<>(context, table, fateId, writer).putUnreserveTx(reservation)
          .tryMutate();
      assertEquals(REJECTED, status);
    }
  }

  void logAllEntriesInTable(String tableName, AccumuloClient client) throws Exception {
    client.createScanner(tableName).forEach(e -> log.info(e.getKey() + " " + e.getValue()));
  }
}
