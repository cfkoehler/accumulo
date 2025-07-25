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
package org.apache.accumulo.tserver;

import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Durability;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.clientImpl.CompressedIterators;
import org.apache.accumulo.core.clientImpl.DurabilityImpl;
import org.apache.accumulo.core.clientImpl.TabletType;
import org.apache.accumulo.core.clientImpl.thrift.SecurityErrorCode;
import org.apache.accumulo.core.clientImpl.thrift.TInfo;
import org.apache.accumulo.core.clientImpl.thrift.TableOperationExceptionType;
import org.apache.accumulo.core.clientImpl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.clientImpl.thrift.ThriftTableOperationException;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.ConstraintViolationSummary;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.NamespaceId;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.dataImpl.thrift.TCMResult;
import org.apache.accumulo.core.dataImpl.thrift.TCMStatus;
import org.apache.accumulo.core.dataImpl.thrift.TConditionalMutation;
import org.apache.accumulo.core.dataImpl.thrift.TConditionalSession;
import org.apache.accumulo.core.dataImpl.thrift.TKeyExtent;
import org.apache.accumulo.core.dataImpl.thrift.TMutation;
import org.apache.accumulo.core.dataImpl.thrift.TRowRange;
import org.apache.accumulo.core.dataImpl.thrift.TSummaries;
import org.apache.accumulo.core.dataImpl.thrift.TSummaryRequest;
import org.apache.accumulo.core.dataImpl.thrift.UpdateErrors;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil;
import org.apache.accumulo.core.iteratorsImpl.system.IterationInterruptedException;
import org.apache.accumulo.core.lock.ServiceLock;
import org.apache.accumulo.core.lock.ServiceLockPaths;
import org.apache.accumulo.core.logging.TabletLogger;
import org.apache.accumulo.core.manager.thrift.TabletServerStatus;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.securityImpl.thrift.TCredentials;
import org.apache.accumulo.core.spi.cache.BlockCache;
import org.apache.accumulo.core.summary.Gatherer;
import org.apache.accumulo.core.summary.Gatherer.FileSystemResolver;
import org.apache.accumulo.core.summary.SummaryCollection;
import org.apache.accumulo.core.tablet.thrift.TUnloadTabletGoal;
import org.apache.accumulo.core.tablet.thrift.TabletManagementClientService;
import org.apache.accumulo.core.tabletingest.thrift.TDurability;
import org.apache.accumulo.core.tabletingest.thrift.TabletIngestClientService;
import org.apache.accumulo.core.tabletserver.log.LogEntry;
import org.apache.accumulo.core.tabletserver.thrift.ActiveCompaction;
import org.apache.accumulo.core.tabletserver.thrift.NoSuchScanIDException;
import org.apache.accumulo.core.tabletserver.thrift.TabletServerClientService;
import org.apache.accumulo.core.tabletserver.thrift.TabletStats;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.ByteBufferUtil;
import org.apache.accumulo.core.util.Halt;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.accumulo.core.util.time.SteadyTime;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.compaction.CompactionInfo;
import org.apache.accumulo.server.compaction.FileCompactor;
import org.apache.accumulo.server.conf.TableConfiguration;
import org.apache.accumulo.server.data.ServerConditionalMutation;
import org.apache.accumulo.server.data.ServerMutation;
import org.apache.accumulo.server.fs.TooManyFilesException;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.rpc.TServerUtils;
import org.apache.accumulo.server.security.AuditedSecurityOperation;
import org.apache.accumulo.server.tablets.ConditionCheckerContext;
import org.apache.accumulo.server.tablets.ConditionCheckerContext.ConditionChecker;
import org.apache.accumulo.tserver.RowLocks.RowLock;
import org.apache.accumulo.tserver.session.ConditionalSession;
import org.apache.accumulo.tserver.session.SummarySession;
import org.apache.accumulo.tserver.session.UpdateSession;
import org.apache.accumulo.tserver.tablet.CommitSession;
import org.apache.accumulo.tserver.tablet.PreparedMutations;
import org.apache.accumulo.tserver.tablet.Tablet;
import org.apache.accumulo.tserver.tablet.Tablet.RefreshPurpose;
import org.apache.accumulo.tserver.tablet.TabletClosedException;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.io.Text;
import org.apache.thrift.TException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

public class TabletClientHandler implements TabletServerClientService.Iface,
    TabletIngestClientService.Iface, TabletManagementClientService.Iface {

  private static final Logger log = LoggerFactory.getLogger(TabletClientHandler.class);
  private final long MAX_TIME_TO_WAIT_FOR_SCAN_RESULT_MILLIS;
  private final TabletServer server;
  protected final ServerContext context;
  protected final AuditedSecurityOperation security;
  private final WriteTracker writeTracker;
  private final RowLocks rowLocks = new RowLocks();

  public TabletClientHandler(TabletServer server, WriteTracker writeTracker) {
    this.context = server.getContext();
    this.writeTracker = writeTracker;
    this.security = context.getSecurityOperation();
    this.server = server;
    MAX_TIME_TO_WAIT_FOR_SCAN_RESULT_MILLIS = server.getContext().getConfiguration()
        .getTimeInMillis(Property.TSERV_SCAN_RESULTS_MAX_TIMEOUT);
    log.debug("{} created", TabletClientHandler.class.getName());
  }

  @Override
  public long startUpdate(TInfo tinfo, TCredentials credentials, TDurability tdurabilty)
      throws ThriftSecurityException {
    // Make sure user is real
    Durability durability = DurabilityImpl.fromThrift(tdurabilty);
    security.authenticateUser(credentials, credentials);
    server.updateMetrics.addPermissionErrors(0);

    UpdateSession us = new UpdateSession(new TservConstraintEnv(server.getContext(), credentials),
        credentials, durability) {
      @Override
      public boolean cleanup() {
        // This is called when a client abandons a session. When this happens need to decrement
        // any queued mutations.
        if (queuedMutationSize > 0) {
          log.trace(
              "cleaning up abandoned update session, decrementing totalQueuedMutationSize by {}",
              queuedMutationSize);
          server.updateTotalQueuedMutationSize(-queuedMutationSize);
          queuedMutationSize = 0;
        }
        return true;
      }
    };
    return server.sessionManager.createSession(us, false);
  }

  private void setUpdateTablet(UpdateSession us, KeyExtent keyExtent) {
    long t1 = System.currentTimeMillis();
    if (us.currentTablet != null && us.currentTablet.getExtent().equals(keyExtent)) {
      return;
    }
    if (us.currentTablet == null && (us.failures.containsKey(keyExtent)
        || us.authFailures.containsKey(keyExtent) || us.unhandledException != null)) {
      // if there were previous failures, then do not accept additional writes
      return;
    }

    TableId tableId = null;
    try {
      // if user has no permission to write to this table, add it to
      // the failures list
      boolean sameTable = us.currentTablet != null
          && us.currentTablet.getExtent().tableId().equals(keyExtent.tableId());
      tableId = keyExtent.tableId();
      if (sameTable || security.canWrite(us.getCredentials(), tableId,
          server.getContext().getNamespaceId(tableId))) {
        long t2 = System.currentTimeMillis();
        us.authTimes.addStat(t2 - t1);
        us.currentTablet = server.getOnlineTablet(keyExtent);
        if (us.currentTablet != null) {
          us.queuedMutations.put(us.currentTablet, new ArrayList<>());
        } else {
          // not serving tablet, so report all mutations as
          // failures
          us.failures.put(keyExtent, 0L);
          server.updateMetrics.addUnknownTabletErrors(1);
        }
      } else {
        log.warn("Denying access to table {} for user {}", keyExtent.tableId(), us.getUser());
        long t2 = System.currentTimeMillis();
        us.authTimes.addStat(t2 - t1);
        us.currentTablet = null;
        us.authFailures.put(keyExtent, SecurityErrorCode.PERMISSION_DENIED);
        server.updateMetrics.addPermissionErrors(1);
      }
    } catch (TableNotFoundException tnfe) {
      log.error("Table " + tableId + " not found ", tnfe);
      long t2 = System.currentTimeMillis();
      us.authTimes.addStat(t2 - t1);
      us.currentTablet = null;
      us.authFailures.put(keyExtent, SecurityErrorCode.TABLE_DOESNT_EXIST);
      server.updateMetrics.addUnknownTabletErrors(1);
    } catch (ThriftSecurityException e) {
      log.error("Denying permission to check user " + us.getUser() + " with user " + e.getUser(),
          e);
      long t2 = System.currentTimeMillis();
      us.authTimes.addStat(t2 - t1);
      us.currentTablet = null;
      us.authFailures.put(keyExtent, e.getCode());
      server.updateMetrics.addPermissionErrors(1);
    }
  }

  @Override
  public void applyUpdates(TInfo tinfo, long updateID, TKeyExtent tkeyExtent,
      List<TMutation> tmutations) {
    UpdateSession us = (UpdateSession) server.sessionManager.reserveSession(updateID);
    if (us == null) {
      return;
    }

    boolean reserved = true;
    try {
      KeyExtent keyExtent = KeyExtent.fromThrift(tkeyExtent);
      setUpdateTablet(us, keyExtent);

      if (us.currentTablet != null) {
        long additionalMutationSize = 0;
        List<Mutation> mutations = us.queuedMutations.get(us.currentTablet);
        for (TMutation tmutation : tmutations) {
          Mutation mutation = new ServerMutation(tmutation);
          // Deserialize the mutation in an attempt to check for data corruption that happened on
          // the network. This will avoid writing a corrupt mutation to the write ahead log and
          // failing after its written to the write ahead log when it is deserialized to update the
          // in memory map.
          mutation.getUpdates();
          mutations.add(mutation);
          additionalMutationSize += mutation.numBytes();
        }
        us.queuedMutationSize += additionalMutationSize;
        long totalQueued = server.updateTotalQueuedMutationSize(additionalMutationSize);
        long total = server.getConfiguration().getAsBytes(Property.TSERV_TOTAL_MUTATION_QUEUE_MAX);
        if (totalQueued > total) {
          try {
            flush(us);
          } catch (HoldTimeoutException hte) {
            // Assumption is that the client has timed out and is gone. If that's not the case,
            // then removing the session should cause the client to fail
            // in such a way that it retries.
            log.debug("HoldTimeoutException during applyUpdates, removing session");
            server.sessionManager.removeSession(updateID, true);
            reserved = false;
          }
        }
      }
    } catch (RuntimeException e) {
      // This method is a thrift oneway method so an exception from it will not make it back to the
      // client. Need to record the exception and set the session such that any future updates to
      // the session are ignored.
      us.unhandledException = e;
      us.currentTablet = null;

      // Rethrowing it will cause logging from thrift, so not adding logging here.
      throw e;
    } finally {
      if (reserved) {
        server.sessionManager.unreserveSession(us);
      }
    }
  }

  private void flush(UpdateSession us) {

    int mutationCount = 0;
    Map<CommitSession,List<Mutation>> sendables = new HashMap<>();
    Map<CommitSession,TabletMutations> loggables = new HashMap<>();
    Throwable error = null;

    long pt1 = System.currentTimeMillis();

    boolean containsMetadataTablet = false;
    for (Tablet tablet : us.queuedMutations.keySet()) {
      if (tablet.getExtent().isMeta()) {
        containsMetadataTablet = true;
      }
    }

    if (!containsMetadataTablet && !us.queuedMutations.isEmpty()) {
      server.resourceManager.waitUntilCommitsAreEnabled();
    }

    int preppedMutations = 0;
    int sendableMutations = 0;

    Span span = TraceUtil.startSpan(this.getClass(), "flush::prep");
    try (Scope scope = span.makeCurrent()) {
      for (Entry<Tablet,? extends List<Mutation>> entry : us.queuedMutations.entrySet()) {

        Tablet tablet = entry.getKey();
        Durability durability =
            DurabilityImpl.resolveDurabilty(us.durability, tablet.getDurability());
        List<Mutation> mutations = entry.getValue();
        if (!mutations.isEmpty()) {
          preppedMutations += mutations.size();
          try {
            server.updateMetrics.addMutationArraySize(mutations.size());

            PreparedMutations prepared = tablet.prepareMutationsForCommit(us.cenv, mutations);

            if (prepared.tabletClosed()) {
              if (us.currentTablet == tablet) {
                us.currentTablet = null;
              }
              us.failures.put(tablet.getExtent(), us.successfulCommits.get(tablet));
            } else {
              if (!prepared.getNonViolators().isEmpty()) {
                List<Mutation> validMutations = prepared.getNonViolators();
                CommitSession session = prepared.getCommitSession();
                if (durability != Durability.NONE) {
                  loggables.put(session, new TabletMutations(session, validMutations, durability));
                }
                sendables.put(session, validMutations);
                sendableMutations += validMutations.size();
              }

              if (!prepared.getViolations().isEmpty()) {
                us.violations.add(prepared.getViolations());
                server.updateMetrics.addConstraintViolations(1);
              }
              // Use the size of the original mutation list, regardless of how many mutations
              // did not violate constraints.
              mutationCount += mutations.size();

            }
          } catch (Exception t) {
            error = t;
            log.error("Unexpected error preparing for commit", error);
            TraceUtil.setException(span, t, false);
            break;
          }
        }
      }
    } catch (Exception e) {
      TraceUtil.setException(span, e, true);
      throw e;
    } finally {
      span.end();
    }

    long pt2 = System.currentTimeMillis();
    us.prepareTimes.addStat(pt2 - pt1);
    updateAvgPrepTime(pt2 - pt1, preppedMutations);

    if (error != null) {
      sendables.forEach((commitSession, value) -> commitSession.abortCommit());
      throw new RuntimeException(error);
    }
    try {
      Span span2 = TraceUtil.startSpan(this.getClass(), "flush::wal");
      try (Scope scope = span2.makeCurrent()) {
        while (true) {
          try {
            long t1 = System.currentTimeMillis();

            server.logger.logManyTablets(loggables);

            long t2 = System.currentTimeMillis();
            us.walogTimes.addStat(t2 - t1);
            updateWalogWriteTime((t2 - t1));
            break;
          } catch (IOException | FSError ex) {
            log.warn("logging mutations failed, retrying");
          } catch (Exception t) {
            log.error("Unknown exception logging mutations, counts"
                + " for mutations in flight not decremented!", t);
            throw new RuntimeException(t);
          }
        }
      } catch (Exception e) {
        TraceUtil.setException(span2, e, true);
        log.error("Error logging mutations sent from {}", TServerUtils.clientAddress.get(), e);
        throw e;
      } finally {
        span2.end();
      }

      Span span3 = TraceUtil.startSpan(this.getClass(), "flush::commit");
      try (Scope scope = span3.makeCurrent()) {
        long t1 = System.currentTimeMillis();
        sendables.forEach((commitSession, mutations) -> {
          commitSession.commit(mutations);
          KeyExtent extent = commitSession.getExtent();

          if (us.currentTablet != null && extent == us.currentTablet.getExtent()) {
            // because constraint violations may filter out some
            // mutations, for proper accounting with the client code,
            // need to increment the count based on the original
            // number of mutations from the client NOT the filtered number
            us.successfulCommits.increment(us.currentTablet,
                us.queuedMutations.get(us.currentTablet).size());
          }
        });
        long t2 = System.currentTimeMillis();

        us.flushTime += (t2 - pt1);
        us.commitTimes.addStat(t2 - t1);

        updateAvgCommitTime(t2 - t1, sendableMutations);
      } catch (Exception e) {
        TraceUtil.setException(span3, e, true);
        log.error("Error committing mutations sent from {}", TServerUtils.clientAddress.get(), e);
        throw e;
      } finally {
        span3.end();
      }
    } finally {
      us.queuedMutations.clear();
      if (us.currentTablet != null) {
        us.queuedMutations.put(us.currentTablet, new ArrayList<>());
      }
      server.updateTotalQueuedMutationSize(-us.queuedMutationSize);
      us.queuedMutationSize = 0;
    }
    us.totalUpdates += mutationCount;
  }

  private void updateAverageLockTime(long time, TimeUnit unit, int size) {
    if (size > 0) {
      server.updateMetrics.addLockTime((long) (time / (double) size), unit);
    }
  }

  private void updateAverageCheckTime(long time, TimeUnit unit, int size) {
    if (size > 0) {
      server.updateMetrics.addCheckTime((long) (time / (double) size), unit);
    }
  }

  private void updateWalogWriteTime(long time) {
    server.updateMetrics.addWalogWriteTime(time);
  }

  private void updateAvgCommitTime(long time, int size) {
    if (size > 0) {
      server.updateMetrics.addCommitTime((long) (time / (double) size));
    }
  }

  private void updateAvgPrepTime(long time, int size) {
    if (size > 0) {
      server.updateMetrics.addCommitPrep((long) (time / (double) size));
    }
  }

  @Override
  public UpdateErrors closeUpdate(TInfo tinfo, long updateID) throws NoSuchScanIDException {
    // Reserve the session and wait for any write that may currently have it reserved. Once reserved
    // no write stragglers can start against this session id.
    final UpdateSession us = (UpdateSession) server.sessionManager.reserveSession(updateID, true);
    if (us == null) {
      throw new NoSuchScanIDException();
    }

    try {
      if (us.unhandledException != null) {
        // Since flush() is not being called, any memory added to the global queued mutations
        // counter will not be decremented. So do that here before throwing an exception.
        server.updateTotalQueuedMutationSize(-us.queuedMutationSize);
        us.queuedMutationSize = 0;
        // make this memory available for GC
        us.queuedMutations.clear();

        // Something unexpected happened during this write session, so throw an exception here to
        // cause a TApplicationException on the client side.
        throw new IllegalStateException(
            "Write session " + updateID + " saw an unexpected exception", us.unhandledException);
      }

      // clients may or may not see data from an update session while
      // it is in progress, however when the update session is closed
      // want to ensure that reads wait for the write to finish
      long opid = writeTracker.startWrite(us.queuedMutations.keySet());

      try {
        flush(us);
      } catch (HoldTimeoutException e) {
        // Assumption is that the client has timed out and is gone. If that's not the case throw an
        // exception that will cause it to retry.
        log.debug("HoldTimeoutException during closeUpdate, reporting no such session");
        throw new NoSuchScanIDException();
      } finally {
        writeTracker.finishWrite(opid);
      }

      if (log.isTraceEnabled()) {
        log.trace(
            String.format("UpSess %s %,d in %.3fs, at=[%s] ft=%.3fs(pt=%.3fs lt=%.3fs ct=%.3fs)",
                TServerUtils.clientAddress.get(), us.totalUpdates,
                (System.currentTimeMillis() - us.startTime) / 1000.0, us.authTimes,
                us.flushTime / 1000.0, us.prepareTimes.sum() / 1000.0, us.walogTimes.sum() / 1000.0,
                us.commitTimes.sum() / 1000.0));
      }
      if (!us.failures.isEmpty()) {
        Entry<KeyExtent,Long> first = us.failures.entrySet().iterator().next();
        log.debug(String.format("Failures: %d, first extent %s successful commits: %d",
            us.failures.size(), first.getKey().toString(), first.getValue()));
      }
      List<ConstraintViolationSummary> violations = us.violations.asList();
      if (!violations.isEmpty()) {
        ConstraintViolationSummary first = us.violations.asList().iterator().next();
        log.debug(String.format("Violations: %d, first %s occurs %d", violations.size(),
            first.violationDescription, first.numberOfViolatingMutations));
      }
      if (!us.authFailures.isEmpty()) {
        KeyExtent first = us.authFailures.keySet().iterator().next();
        log.debug(String.format("Authentication Failures: %d, first %s", us.authFailures.size(),
            first.toString()));
      }
      return new UpdateErrors(
          us.failures.entrySet().stream()
              .collect(Collectors.toMap(e -> e.getKey().toThrift(), Entry::getValue)),
          violations.stream().map(ConstraintViolationSummary::toThrift)
              .collect(Collectors.toList()),
          us.authFailures.entrySet().stream()
              .collect(Collectors.toMap(e -> e.getKey().toThrift(), Entry::getValue)));
    } finally {
      // Atomically unreserve and delete the session. If there are any write stragglers, they will
      // fail
      // after this point.
      server.sessionManager.removeSession(updateID, true);
    }
  }

  @Override
  public boolean cancelUpdate(TInfo tinfo, long updateID) throws TException {
    return server.sessionManager.removeIfNotReserved(updateID);
  }

  private NamespaceId getNamespaceId(TCredentials credentials, TableId tableId)
      throws ThriftSecurityException {
    try {
      return server.getContext().getNamespaceId(tableId);
    } catch (TableNotFoundException e1) {
      throw new ThriftSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.TABLE_DOESNT_EXIST);
    }
  }

  private void checkConditions(Map<KeyExtent,List<ServerConditionalMutation>> updates,
      ArrayList<TCMResult> results, ConditionalSession cs, List<String> symbols)
      throws IOException, ReflectiveOperationException {
    Iterator<Entry<KeyExtent,List<ServerConditionalMutation>>> iter = updates.entrySet().iterator();

    final CompressedIterators compressedIters = new CompressedIterators(symbols);
    ConditionCheckerContext checkerContext = new ConditionCheckerContext(server.getContext(),
        compressedIters, context.getTableConfiguration(cs.tableId));

    while (iter.hasNext()) {
      final Entry<KeyExtent,List<ServerConditionalMutation>> entry = iter.next();
      final Tablet tablet = server.getOnlineTablet(entry.getKey());

      if (tablet == null || tablet.isClosed()) {
        for (ServerConditionalMutation scm : entry.getValue()) {
          results.add(new TCMResult(scm.getID(), TCMStatus.IGNORED));
        }
        iter.remove();
      } else {
        final List<ServerConditionalMutation> okMutations =
            new ArrayList<>(entry.getValue().size());
        final List<TCMResult> resultsSubList = results.subList(results.size(), results.size());

        ConditionChecker checker =
            checkerContext.newChecker(entry.getValue(), okMutations, resultsSubList);
        try {
          tablet.checkConditions(checker, cs.auths, cs.interruptFlag);

          if (okMutations.isEmpty()) {
            iter.remove();
          } else {
            entry.setValue(okMutations);
          }
        } catch (TabletClosedException | IterationInterruptedException | TooManyFilesException e) {
          // clear anything added while checking conditions.
          resultsSubList.clear();

          for (ServerConditionalMutation scm : entry.getValue()) {
            results.add(new TCMResult(scm.getID(), TCMStatus.IGNORED));
          }
          iter.remove();
        }
      }
    }
  }

  private void writeConditionalMutations(Map<KeyExtent,List<ServerConditionalMutation>> updates,
      ArrayList<TCMResult> results, ConditionalSession sess) {
    Set<Entry<KeyExtent,List<ServerConditionalMutation>>> es = updates.entrySet();

    Map<CommitSession,List<Mutation>> sendables = new HashMap<>();
    Map<CommitSession,TabletMutations> loggables = new HashMap<>();

    boolean sessionCanceled = sess.interruptFlag.get();

    Span span = TraceUtil.startSpan(this.getClass(), "writeConditionalMutations::prep");
    int preppedMutions = 0;
    int sendableMutations = 0;
    try (Scope scope = span.makeCurrent()) {
      long t1 = System.currentTimeMillis();
      for (Entry<KeyExtent,List<ServerConditionalMutation>> entry : es) {
        final Tablet tablet = server.getOnlineTablet(entry.getKey());
        if (tablet == null || tablet.isClosed() || sessionCanceled) {
          addMutationsAsTCMResults(results, entry.getValue(), TCMStatus.IGNORED);
        } else {
          final Durability durability =
              DurabilityImpl.resolveDurabilty(sess.durability, tablet.getDurability());

          List<Mutation> mutations = Collections.unmodifiableList(entry.getValue());
          preppedMutions += mutations.size();
          if (!mutations.isEmpty()) {

            PreparedMutations prepared = tablet.prepareMutationsForCommit(
                new TservConstraintEnv(server.getContext(), sess.credentials), mutations);

            if (prepared.tabletClosed()) {
              addMutationsAsTCMResults(results, mutations, TCMStatus.IGNORED);
            } else {
              if (!prepared.getNonViolators().isEmpty()) {
                // Only log and commit mutations that did not violate constraints.
                List<Mutation> validMutations = prepared.getNonViolators();
                addMutationsAsTCMResults(results, validMutations, TCMStatus.ACCEPTED);
                CommitSession session = prepared.getCommitSession();
                if (durability != Durability.NONE) {
                  loggables.put(session, new TabletMutations(session, validMutations, durability));
                }
                sendables.put(session, validMutations);
                sendableMutations += validMutations.size();
              }

              if (!prepared.getViolators().isEmpty()) {
                addMutationsAsTCMResults(results, prepared.getViolators(), TCMStatus.VIOLATED);
              }
            }
          }
        }
      }

      long t2 = System.currentTimeMillis();
      updateAvgPrepTime(t2 - t1, preppedMutions);
    } catch (Exception e) {
      TraceUtil.setException(span, e, true);
      throw e;
    } finally {
      span.end();
    }

    Span span2 = TraceUtil.startSpan(this.getClass(), "writeConditionalMutations::wal");
    try (Scope scope = span2.makeCurrent()) {
      while (!loggables.isEmpty()) {
        try {
          long t1 = System.currentTimeMillis();
          server.logger.logManyTablets(loggables);
          long t2 = System.currentTimeMillis();
          updateWalogWriteTime(t2 - t1);
          break;
        } catch (IOException | FSError ex) {
          TraceUtil.setException(span2, ex, false);
          log.warn("logging mutations failed, retrying");
        } catch (Exception t) {
          log.error("Unknown exception logging mutations, counts for"
              + " mutations in flight not decremented!", t);
          throw new RuntimeException(t);
        }
      }
    } catch (Exception e) {
      TraceUtil.setException(span2, e, true);
      throw e;
    } finally {
      span2.end();
    }

    Span span3 = TraceUtil.startSpan(this.getClass(), "writeConditionalMutations::commit");
    try (Scope scope = span3.makeCurrent()) {
      long t1 = System.currentTimeMillis();
      sendables.forEach(CommitSession::commit);
      long t2 = System.currentTimeMillis();
      updateAvgCommitTime(t2 - t1, sendableMutations);
    } catch (Exception e) {
      TraceUtil.setException(span3, e, true);
      log.error("Error committing mutations sent from {}", TServerUtils.clientAddress.get(), e);
      throw e;
    } finally {
      span3.end();
    }
  }

  /**
   * Transform and add each mutation as a {@link TCMResult} with the mutation's ID and the specified
   * status to the {@link TCMResult} list.
   */
  private void addMutationsAsTCMResults(final List<TCMResult> list,
      final Collection<? extends Mutation> mutations, final TCMStatus status) {
    mutations.stream()
        .map(mutation -> new TCMResult(((ServerConditionalMutation) mutation).getID(), status))
        .forEach(list::add);
  }

  private Map<KeyExtent,List<ServerConditionalMutation>> conditionalUpdate(ConditionalSession cs,
      Map<KeyExtent,List<ServerConditionalMutation>> updates, ArrayList<TCMResult> results,
      List<String> symbols) throws IOException, ReflectiveOperationException {
    // sort each list of mutations, this is done to avoid deadlock and doing seeks in order is
    // more efficient and detect duplicate rows.
    int numMutations = ConditionalMutationSet.sortConditionalMutations(updates);

    Map<KeyExtent,List<ServerConditionalMutation>> deferred = new HashMap<>();

    // can not process two mutations for the same row, because one will not see what the other
    // writes
    ConditionalMutationSet.deferDuplicatesRows(updates, deferred);

    // get as many locks as possible w/o blocking... defer any rows that are locked
    long lt1 = System.nanoTime();
    List<RowLock> locks = rowLocks.acquireRowlocks(updates, deferred);
    long lt2 = System.nanoTime();
    updateAverageLockTime(lt2 - lt1, TimeUnit.NANOSECONDS, numMutations);
    try {
      Span span = TraceUtil.startSpan(this.getClass(), "conditionalUpdate::Check conditions");
      try (Scope scope = span.makeCurrent()) {
        long t1 = System.nanoTime();
        checkConditions(updates, results, cs, symbols);
        long t2 = System.nanoTime();
        updateAverageCheckTime(t2 - t1, TimeUnit.NANOSECONDS, numMutations);
      } catch (Exception e) {
        TraceUtil.setException(span, e, true);
        throw e;
      } finally {
        span.end();
      }

      Span span2 =
          TraceUtil.startSpan(this.getClass(), "conditionalUpdate::apply conditional mutations");
      try (Scope scope = span2.makeCurrent()) {
        writeConditionalMutations(updates, results, cs);
      } catch (Exception e) {
        TraceUtil.setException(span2, e, true);
        throw e;
      } finally {
        span2.end();
      }
    } finally {
      rowLocks.releaseRowLocks(locks);
    }
    return deferred;
  }

  @Override
  public TConditionalSession startConditionalUpdate(TInfo tinfo, TCredentials credentials,
      List<ByteBuffer> authorizations, String tableIdStr, TDurability tdurabilty,
      String classLoaderContext) throws ThriftSecurityException, TException {

    TableId tableId = TableId.of(tableIdStr);
    Authorizations userauths;
    NamespaceId namespaceId = getNamespaceId(credentials, tableId);
    if (!security.canConditionallyUpdate(credentials, tableId, namespaceId)) {
      throw new ThriftSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED);
    }

    userauths = security.getUserAuthorizations(credentials);
    for (ByteBuffer auth : authorizations) {
      if (!userauths.contains(ByteBufferUtil.toBytes(auth))) {
        throw new ThriftSecurityException(credentials.getPrincipal(),
            SecurityErrorCode.BAD_AUTHORIZATIONS);
      }
    }

    ConditionalSession cs = new ConditionalSession(credentials, new Authorizations(authorizations),
        tableId, DurabilityImpl.fromThrift(tdurabilty));

    long sid = server.sessionManager.createSession(cs, false);
    return new TConditionalSession(sid, server.getLockID().serialize(),
        server.sessionManager.getMaxIdleTime());
  }

  @Override
  public List<TCMResult> conditionalUpdate(TInfo tinfo, long sessID,
      Map<TKeyExtent,List<TConditionalMutation>> mutations, List<String> symbols)
      throws NoSuchScanIDException, TException {

    ConditionalSession cs = null;
    Long opid = null;

    try {
      cs = (ConditionalSession) server.sessionManager.reserveSession(sessID);
      if (cs == null || cs.interruptFlag.get()) {
        throw new NoSuchScanIDException();
      }

      if (!cs.tableId.equals(SystemTables.METADATA.tableId())
          && !cs.tableId.equals(SystemTables.ROOT.tableId())) {
        try {
          server.resourceManager.waitUntilCommitsAreEnabled();
        } catch (HoldTimeoutException hte) {
          // Assumption is that the client has timed out and is gone. If that's not the case throw
          // an exception that will cause it to retry.
          log.debug("HoldTimeoutException during conditionalUpdate, reporting no such session");
          throw new NoSuchScanIDException();
        }
      }

      TableId tid = cs.tableId;
      opid = writeTracker.startWrite(TabletType.type(new KeyExtent(tid, null, null)));

      // @formatter:off
      Map<KeyExtent, List<ServerConditionalMutation>> updates = mutations.entrySet().stream().collect(Collectors.toMap(
                      entry -> KeyExtent.fromThrift(entry.getKey()),
                      entry -> entry.getValue().stream().map(ServerConditionalMutation::new).collect(Collectors.toList())
      ));
      // @formatter:on
      for (KeyExtent ke : updates.keySet()) {
        if (!ke.tableId().equals(tid)) {
          throw new IllegalArgumentException("Unexpected table id " + tid + " != " + ke.tableId());
        }
      }

      var lambdaCs = cs;
      // Conditional updates read data into memory, examine it, and then make an update. This can be
      // CPU, I/O, and memory intensive. Using a thread pool directly limits CPU usage and
      // indirectly limits memory and I/O usage.
      Future<ArrayList<TCMResult>> future =
          server.resourceManager.executeConditionalUpdate(cs.tableId, () -> {
            ArrayList<TCMResult> results = new ArrayList<>();

            Map<KeyExtent,List<ServerConditionalMutation>> deferred =
                conditionalUpdate(lambdaCs, updates, results, symbols);

            while (!deferred.isEmpty()) {
              deferred = conditionalUpdate(lambdaCs, deferred, results, symbols);
            }

            return results;
          });

      return future.get();
    } catch (ExecutionException | InterruptedException e) {
      log.warn("Exception returned for conditionalUpdate. tableId: {}, opid: {}",
          cs == null ? null : cs.tableId, opid, e);
      throw new TException(e);
    } catch (RuntimeException e) {
      log.warn("Exception returned for conditionalUpdate. tableId: {}, opid: {}",
          cs == null ? null : cs.tableId, opid, e);
      throw e;
    } finally {
      if (opid != null) {
        writeTracker.finishWrite(opid);
      }

      if (cs != null) {
        server.sessionManager.unreserveSession(sessID);
      }
    }
  }

  @Override
  public void invalidateConditionalUpdate(TInfo tinfo, long sessID) {
    // For the given session, this method should wait for any running conditional update to
    // complete. After this method returns a conditional update should not be able to start against
    // this session and nothing should be running.

    ConditionalSession cs = (ConditionalSession) server.sessionManager.getSession(sessID);
    if (cs != null) {
      // Setting this may cause anything running to fail. Setting this will prevent anything from
      // starting.
      cs.interruptFlag.set(true);
    }

    // If a thread is currently running and working on the update, then this should block until it
    // un-reserves the session.
    cs = (ConditionalSession) server.sessionManager.reserveSession(sessID, true);
    if (cs != null) {
      server.sessionManager.removeSession(sessID, true);
    }
  }

  @Override
  public void closeConditionalUpdate(TInfo tinfo, long sessID) {
    server.sessionManager.removeSession(sessID, false);
  }

  @Override
  public TabletServerStatus getTabletServerStatus(TInfo tinfo, TCredentials credentials) {
    return server.getStats(server.sessionManager.getActiveScansPerTable());
  }

  @Override
  public List<TabletStats> getTabletStats(TInfo tinfo, TCredentials credentials, String tableId) {
    List<TabletStats> result = new ArrayList<>();
    TableId text = TableId.of(tableId);
    KeyExtent start = new KeyExtent(text, new Text(), null);
    for (Entry<KeyExtent,Tablet> entry : server.getOnlineTablets().tailMap(start).entrySet()) {
      KeyExtent ke = entry.getKey();
      if (ke.tableId().compareTo(text) == 0) {
        Tablet tablet = entry.getValue();
        TabletStats stats = tablet.getTabletStats();
        stats.extent = ke.toThrift();
        stats.ingestRate = tablet.ingestRate();
        stats.queryRate = tablet.queryRate();
        stats.numEntries = tablet.getNumEntries();
        result.add(stats);
      }
    }
    return result;
  }

  static void checkPermission(ServerContext context, TabletHostingServer server,
      TCredentials credentials, String lock, final String request) throws ThriftSecurityException {
    boolean canPerformSystemActions = false;
    try {
      canPerformSystemActions = context.getSecurityOperation().canPerformSystemActions(credentials);
    } catch (ThriftSecurityException e) {
      log.warn("Got {} message from unauthenticatable user: {}", request, e.getUser());
      if (context.getCredentials().getToken().getClass().getName()
          .equals(credentials.getTokenClassName())) {
        log.error("Got message from a service with a mismatched configuration."
            + " Please ensure a compatible configuration.", e);
      }
      throw e;
    }

    if (!canPerformSystemActions) {
      log.warn("Denied {} request from user: {}", request, credentials.getPrincipal());
      throw new ThriftSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED);
    }

    if (server.getLock() == null || !server.getLock().wasLockAcquired()) {
      log.debug("Got {} message from user {} before my lock was acquired, ignoring...", request,
          credentials.getPrincipal());
      throw new RuntimeException("Lock not acquired");
    }

    log.trace("Got {} message from user: {}", request, credentials.getPrincipal());
    if (server.getLock() != null && server.getLock().wasLockAcquired()
        && !server.getLock().isLocked()) {
      Halt.halt(1,
          "Tablet server no longer holds lock during checkPermission() : " + request + ", exiting",
          () -> context.getLowMemoryDetector().logGCInfo(server.getConfiguration()));
    }

    if (lock != null) {
      ZooUtil.LockID lid = ZooUtil.LockID.deserialize(lock);

      try {
        if (!ServiceLock.isLockHeld(server.getContext().getZooCache(), lid)) {
          // maybe the cache is out of date and a new manager holds the
          // lock?
          server.getContext().getZooCache().clear(
              (path) -> path.equals(ServiceLockPaths.parse(Optional.empty(), lid.path).toString()));
          if (!ServiceLock.isLockHeld(server.getContext().getZooCache(), lid)) {
            log.warn("Got {} message from a manager that does not hold the current lock {}",
                request, lock);
            throw new RuntimeException("bad manager lock");
          }
        }
      } catch (Exception e) {
        throw new RuntimeException("bad manager lock", e);
      }
    }
  }

  @Override
  public void loadTablet(TInfo tinfo, TCredentials credentials, String lock,
      final TKeyExtent textent) {

    try {
      checkPermission(context, server, credentials, lock, "loadTablet");
    } catch (ThriftSecurityException e) {
      log.error("Caller doesn't have permission to load a tablet", e);
      throw new RuntimeException(e);
    }

    final KeyExtent extent = KeyExtent.fromThrift(textent);

    synchronized (server.unopenedTablets) {
      synchronized (server.openingTablets) {
        synchronized (server.onlineTablets) {

          // Checking if the current tablet is in any of the sets
          // below is not a strong enough check to catch all overlapping tablets
          // when splits and fix splits are occurring
          Set<KeyExtent> unopenedOverlapping =
              KeyExtent.findOverlapping(extent, server.unopenedTablets);
          Set<KeyExtent> openingOverlapping =
              KeyExtent.findOverlapping(extent, server.openingTablets);
          Set<KeyExtent> onlineOverlapping =
              KeyExtent.findOverlapping(extent, server.getOnlineTablets());

          Set<KeyExtent> all = new HashSet<>();
          all.addAll(unopenedOverlapping);
          all.addAll(openingOverlapping);
          all.addAll(onlineOverlapping);

          if (!all.isEmpty()) {

            // ignore self, for error logging
            all.remove(extent);

            if (!all.isEmpty()) {
              log.error(
                  "Tablet {} overlaps a previously assigned tablet, possibly due to a recent split. "
                      + "Overlapping tablets:  Unopened: {}, Opening: {}, Online: {}",
                  extent, unopenedOverlapping, openingOverlapping, onlineOverlapping);
            }
            return;
          }

          server.unopenedTablets.add(extent);
        }
      }
    }

    TabletLogger.loading(extent, server.getTabletSession());

    final AssignmentHandler ah = new AssignmentHandler(server, extent);
    // Root tablet assignment must take place immediately

    if (extent.isRootTablet()) {
      Threads.createNonCriticalThread("Root Tablet Assignment", () -> {
        ah.run();
        if (server.getOnlineTablets().containsKey(extent)) {
          log.info("Root tablet loaded: {}", extent);
        } else {
          log.info("Root tablet failed to load");
        }
      }).start();
    } else if (extent.isMeta()) {
      server.resourceManager.addMetaDataAssignment(extent, log, ah);
    } else {
      server.resourceManager.addAssignment(extent, log, ah);
    }
  }

  @Override
  public void unloadTablet(TInfo tinfo, TCredentials credentials, String lock, TKeyExtent textent,
      TUnloadTabletGoal goal, long requestTime) {
    try {
      checkPermission(context, server, credentials, lock, "unloadTablet");
    } catch (ThriftSecurityException e) {
      log.error("Caller doesn't have permission to unload a tablet", e);
      throw new RuntimeException(e);
    }

    KeyExtent extent = KeyExtent.fromThrift(textent);

    server.resourceManager.addMigration(extent, new UnloadTabletHandler(server, extent, goal,
        SteadyTime.from(requestTime, TimeUnit.MILLISECONDS)));
  }

  @Override
  public void flush(TInfo tinfo, TCredentials credentials, String lock, String tableId,
      ByteBuffer startRow, ByteBuffer endRow) {
    try {
      checkPermission(context, server, credentials, lock, "flush");
    } catch (ThriftSecurityException e) {
      log.error("Caller doesn't have permission to flush a table", e);
      throw new RuntimeException(e);
    }

    KeyExtent ke = new KeyExtent(TableId.of(tableId), ByteBufferUtil.toText(endRow),
        ByteBufferUtil.toText(startRow));

    List<Tablet> tabletsToFlush = server.getOnlineTablets().values().stream()
        .filter(tablet -> ke.overlaps(tablet.getExtent())).collect(toList());

    if (tabletsToFlush.isEmpty()) {
      return; // no tablets to flush
    }

    // read the flush id once from zookeeper instead of reading it for each tablet
    final long flushID;
    try {
      Tablet firstTablet = tabletsToFlush.get(0);
      flushID = firstTablet.getFlushID();
    } catch (NoNodeException e) {
      // table was probably deleted
      log.info("Asked to flush table that has no flush id {} {}", ke, e.getMessage());
      return;
    }

    tabletsToFlush.forEach(tablet -> tablet.flush(flushID));
  }

  @Override
  public void flushTablet(TInfo tinfo, TCredentials credentials, String lock, TKeyExtent textent) {
    try {
      checkPermission(context, server, credentials, lock, "flushTablet");
    } catch (ThriftSecurityException e) {
      log.error("Caller doesn't have permission to flush a tablet", e);
      throw new RuntimeException(e);
    }

    Tablet tablet = server.getOnlineTablet(KeyExtent.fromThrift(textent));
    if (tablet != null) {
      log.info("Flushing {}", tablet.getExtent());
      try {
        tablet.flush(tablet.getFlushID());
      } catch (NoNodeException nne) {
        log.info("Asked to flush tablet that has no flush id {} {}", KeyExtent.fromThrift(textent),
            nne.getMessage());
      }
    }
  }

  @Override
  public void halt(TInfo tinfo, TCredentials credentials, String lock)
      throws ThriftSecurityException {

    checkPermission(context, server, credentials, lock, "halt");

    Halt.halt(0, "Manager requested tablet server halt", () -> {
      context.getLowMemoryDetector().logGCInfo(server.getConfiguration());
      server.requestStop();
      try {
        server.getLock().unlock();
      } catch (Exception e) {
        log.error("Caught exception unlocking TabletServer lock", e);
      }
    });
  }

  @Override
  public void fastHalt(TInfo info, TCredentials credentials, String lock) {
    try {
      halt(info, credentials, lock);
    } catch (Exception e) {
      log.warn("Error halting", e);
    }
  }

  @Override
  public TabletStats getHistoricalStats(TInfo tinfo, TCredentials credentials) {
    return server.statsKeeper.getTabletStats();
  }

  @Override
  public List<ActiveCompaction> getActiveCompactions(TInfo tinfo, TCredentials credentials)
      throws ThriftSecurityException, TException {
    try {
      checkPermission(context, server, credentials, null, "getActiveCompactions");
    } catch (ThriftSecurityException e) {
      log.error("Caller doesn't have permission to get active compactions", e);
      throw e;
    }

    List<CompactionInfo> compactions = FileCompactor.getRunningCompactions();
    List<ActiveCompaction> ret = new ArrayList<>(compactions.size());

    for (CompactionInfo compactionInfo : compactions) {
      ret.add(compactionInfo.toThrift());
    }

    return ret;
  }

  @Override
  public List<TKeyExtent> refreshTablets(TInfo tinfo, TCredentials credentials,
      List<TKeyExtent> refreshes) throws TException {
    if (!security.canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    // get a snapshot of the current online tablets, may miss some that are loading but will only
    // handle that more expensive case if needed.
    var tabletsSnapshot = server.getOnlineTablets();

    Map<KeyExtent,Tablet.RefreshSession> refreshSessions = new HashMap<>();

    // Created this as synchronized list because it's passed to a lambda that could possibly run in
    // another thread.
    List<TKeyExtent> unableToRefresh = Collections.synchronizedList(new ArrayList<>());

    for (var tkextent : refreshes) {
      var extent = KeyExtent.fromThrift(tkextent);
      var tablet = tabletsSnapshot.get(extent);
      if (tablet != null) {
        refreshSessions.put(extent, tablet.startRefresh());
      } else {
        unableToRefresh.add(extent.toThrift());
      }
    }

    // Read the metadata for all of the tablets that need to be refreshed. Its very important that
    // this metadata read is done after calling Tablet.startRefresh, this is because the refresh
    // session can detect changes that may necessitate rereading the tablet metadata. The refresh
    // session assumes the metadata was read after the session was created in order to determine if
    // its still valid.
    try (var tabletsMeta = context.getAmple().readTablets()
        .forTablets(refreshSessions.keySet(), Optional.of(e -> unableToRefresh.add(e.toThrift())))
        .build()) {
      for (var tabletMeta : tabletsMeta) {
        var refreshSession = refreshSessions.get(tabletMeta.getExtent());
        if (!refreshSession.refreshMetadata(RefreshPurpose.REFRESH_RPC, tabletMeta)) {
          unableToRefresh.add(tabletMeta.getExtent().toThrift());
        }
      }
    }

    if (log.isDebugEnabled()) {
      for (var extent : unableToRefresh) {
        // these tablet could hold up bulk import, lets logs the specific tablet in case it stays
        // like this
        log.debug("Unable to refresh tablet : {}", KeyExtent.fromThrift(extent));
      }
    }

    return unableToRefresh;
  }

  @Override
  public Map<TKeyExtent,Long> allocateTimestamps(TInfo tinfo, TCredentials credentials,
      List<TKeyExtent> extents) throws TException {
    if (!security.canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    var tabletsSnapshot = server.getOnlineTablets();

    Map<TKeyExtent,Long> timestamps = new HashMap<>();

    for (var textent : extents) {
      var extent = KeyExtent.fromThrift(textent);
      Tablet tablet = tabletsSnapshot.get(extent);
      if (tablet != null) {
        tablet.allocateTimestamp().ifPresent(timestamp -> timestamps.put(textent, timestamp));
      }
    }

    return timestamps;
  }

  @Override
  public List<String> getActiveLogs(TInfo tinfo, TCredentials credentials) {
    // Might be null if there is no active logger
    LogEntry le = server.logger.getLogEntry();
    return le == null ? Collections.emptyList() : Collections.singletonList(le.getPath());
  }

  @Override
  public void removeLogs(TInfo tinfo, TCredentials credentials, List<String> filenames) {
    log.warn("Garbage collector is attempting to remove logs through the tablet server");
    log.warn("This is probably because your file"
        + " Garbage Collector is an older version than your tablet servers.\n"
        + "Restart your file Garbage Collector.");
  }

  private TSummaries getSummaries(Future<SummaryCollection> future) throws TimeoutException {
    try {
      SummaryCollection sc =
          future.get(MAX_TIME_TO_WAIT_FOR_SCAN_RESULT_MILLIS, TimeUnit.MILLISECONDS);
      return sc.toThrift();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private TSummaries handleTimeout(long sessionId) {
    long timeout = server.getConfiguration().getTimeInMillis(Property.TSERV_CLIENT_TIMEOUT);
    server.sessionManager.removeIfNotAccessed(sessionId, timeout);
    return new TSummaries(false, sessionId, -1, -1, null);
  }

  private TSummaries startSummaryOperation(TCredentials credentials,
      Future<SummaryCollection> future) {
    try {
      return getSummaries(future);
    } catch (TimeoutException e) {
      long sid =
          server.sessionManager.createSession(new SummarySession(credentials, future), false);
      while (sid == 0) {
        server.sessionManager.removeSession(sid);
        sid = server.sessionManager.createSession(new SummarySession(credentials, future), false);
      }
      return handleTimeout(sid);
    }
  }

  @Override
  public TSummaries startGetSummaries(TInfo tinfo, TCredentials credentials,
      TSummaryRequest request)
      throws ThriftSecurityException, ThriftTableOperationException, TException {
    NamespaceId namespaceId;
    TableId tableId = TableId.of(request.getTableId());
    try {
      namespaceId = server.getContext().getNamespaceId(tableId);
    } catch (TableNotFoundException e1) {
      throw new ThriftTableOperationException(tableId.canonical(), null, null,
          TableOperationExceptionType.NOTFOUND, null);
    }

    if (!security.canGetSummaries(credentials, tableId, namespaceId)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    ExecutorService es = server.resourceManager.getSummaryPartitionExecutor();
    var tableConf = context.getTableConfiguration(tableId);
    Future<SummaryCollection> future =
        new Gatherer(server.getContext(), request, tableConf, tableConf.getCryptoService())
            .gather(es);

    return startSummaryOperation(credentials, future);
  }

  @Override
  public TSummaries startGetSummariesForPartition(TInfo tinfo, TCredentials credentials,
      TSummaryRequest request, int modulus, int remainder)
      throws ThriftSecurityException, TException {
    // do not expect users to call this directly, expect other tservers to call this method
    if (!security.canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    ExecutorService spe = server.resourceManager.getSummaryRemoteExecutor();
    TableConfiguration tableConfig =
        context.getTableConfiguration(TableId.of(request.getTableId()));
    Future<SummaryCollection> future =
        new Gatherer(server.getContext(), request, tableConfig, tableConfig.getCryptoService())
            .processPartition(spe, modulus, remainder);

    return startSummaryOperation(credentials, future);
  }

  @Override
  public TSummaries startGetSummariesFromFiles(TInfo tinfo, TCredentials credentials,
      TSummaryRequest request, Map<String,List<TRowRange>> files)
      throws ThriftSecurityException, TException {
    // do not expect users to call this directly, expect other tservers to call this method
    if (!security.canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    ExecutorService srp = server.resourceManager.getSummaryRetrievalExecutor();
    TableConfiguration tableCfg = context.getTableConfiguration(TableId.of(request.getTableId()));
    BlockCache summaryCache = server.resourceManager.getSummaryCache();
    BlockCache indexCache = server.resourceManager.getIndexCache();
    Cache<String,Long> fileLenCache = server.resourceManager.getFileLenCache();
    VolumeManager fs = context.getVolumeManager();
    FileSystemResolver volMgr = fs::getFileSystemByPath;
    Future<SummaryCollection> future =
        new Gatherer(server.getContext(), request, tableCfg, tableCfg.getCryptoService())
            .processFiles(volMgr, files, summaryCache, indexCache, fileLenCache, srp);

    return startSummaryOperation(credentials, future);
  }

  @Override
  public TSummaries contiuneGetSummaries(TInfo tinfo, long sessionId)
      throws NoSuchScanIDException, TException {
    SummarySession session = (SummarySession) server.sessionManager.getSession(sessionId);
    if (session == null) {
      throw new NoSuchScanIDException();
    }

    Future<SummaryCollection> future = session.getFuture();
    try {
      TSummaries tsums = getSummaries(future);
      server.sessionManager.removeSession(sessionId);
      return tsums;
    } catch (TimeoutException e) {
      return handleTimeout(sessionId);
    }
  }
}
