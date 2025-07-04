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
package org.apache.accumulo.tserver.log;

import static java.util.Collections.singletonList;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_WAL_CREATOR_POOL;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.accumulo.core.client.Durability;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.file.blockfile.impl.BasicCacheProvider;
import org.apache.accumulo.core.file.blockfile.impl.CacheProvider;
import org.apache.accumulo.core.lock.ServiceLock;
import org.apache.accumulo.core.logging.LoggingBlockCache;
import org.apache.accumulo.core.spi.cache.CacheType;
import org.apache.accumulo.core.tabletserver.log.LogEntry;
import org.apache.accumulo.core.util.Halt;
import org.apache.accumulo.core.util.Retry;
import org.apache.accumulo.core.util.Retry.RetryFactory;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.tserver.TabletMutations;
import org.apache.accumulo.tserver.TabletServer;
import org.apache.accumulo.tserver.TabletServerResourceManager;
import org.apache.accumulo.tserver.log.DfsLogger.LoggerOperation;
import org.apache.accumulo.tserver.tablet.CommitSession;
import org.apache.hadoop.fs.Path;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Central logging facility for the TServerInfo.
 *
 * Forwards in-memory updates to remote logs, carefully writing the same data to every log, while
 * maintaining the maximum thread parallelism for greater performance. As new logs are used and
 * minor compactions are performed, the metadata table is kept up-to-date.
 *
 */
public class TabletServerLogger {

  private static final Logger log = LoggerFactory.getLogger(TabletServerLogger.class);

  private final AtomicLong logSizeEstimate = new AtomicLong();
  private final long maxSize;
  private final long maxAge;

  private final TabletServer tserver;

  // The current logger
  private DfsLogger currentLog = null;
  private final SynchronousQueue<Object> nextLog = new SynchronousQueue<>();
  private ThreadPoolExecutor nextLogMaker;

  // The current generation of logs.
  // Because multiple threads can be using a log at one time, a log
  // failure is likely to affect multiple threads, who will all attempt to
  // create a new log. This will cause many unnecessary updates to the
  // metadata table.
  // We'll use this generational counter to determine if another thread has
  // already fetched a new log.
  private final AtomicInteger logId = new AtomicInteger();

  // Use a ReadWriteLock to allow multiple threads to use the log set, but obtain a write lock to
  // change them
  private final ReentrantReadWriteLock logIdLock = new ReentrantReadWriteLock();

  private final AtomicLong syncCounter;
  private final AtomicLong flushCounter;

  private long createTime = 0;

  private final RetryFactory createRetryFactory;
  private Retry createRetry = null;

  private final RetryFactory writeRetryFactory;

  private final Cache<LogEntry,ResolvedSortedLog> sortedLogCache;

  private abstract static class TestCallWithWriteLock {
    abstract boolean test();

    abstract void withWriteLock() throws IOException;
  }

  /**
   * Pattern taken from the documentation for ReentrantReadWriteLock
   *
   * @param rwlock lock to use
   * @param code a test/work pair
   */
  private static void testLockAndRun(final ReadWriteLock rwlock, TestCallWithWriteLock code)
      throws IOException {
    // Get a read lock
    rwlock.readLock().lock();
    try {
      // does some condition exist that needs the write lock?
      if (code.test()) {
        // Yes, let go of the readLock
        rwlock.readLock().unlock();
        // Grab the write lock
        rwlock.writeLock().lock();
        try {
          // double-check the condition, since we let go of the lock
          if (code.test()) {
            // perform the work with with write lock held
            code.withWriteLock();
          }
        } finally {
          // regain the readLock
          rwlock.readLock().lock();
          // unlock the write lock
          rwlock.writeLock().unlock();
        }
      }
    } finally {
      // always let go of the lock
      rwlock.readLock().unlock();
    }
  }

  public TabletServerLogger(TabletServer tserver, long maxSize, AtomicLong syncCounter,
      AtomicLong flushCounter, RetryFactory createRetryFactory, RetryFactory writeRetryFactory,
      long maxAge) {
    this.tserver = tserver;
    this.maxSize = maxSize;
    this.syncCounter = syncCounter;
    this.flushCounter = flushCounter;
    this.createRetryFactory = createRetryFactory;
    this.createRetry = null;
    this.writeRetryFactory = writeRetryFactory;
    this.maxAge = maxAge;
    this.sortedLogCache = Caffeine.newBuilder().expireAfterWrite(3, TimeUnit.SECONDS).build();
  }

  private DfsLogger initializeLoggers(final AtomicInteger logIdOut) throws IOException {
    final AtomicReference<DfsLogger> result = new AtomicReference<>();
    testLockAndRun(logIdLock, new TestCallWithWriteLock() {
      @Override
      boolean test() {
        result.set(currentLog);
        if (currentLog != null) {
          logIdOut.set(logId.get());
        }
        return currentLog == null;
      }

      @Override
      void withWriteLock() {
        createLogger();
        result.set(currentLog);
        if (currentLog != null) {
          logIdOut.set(logId.get());
        } else {
          logIdOut.set(-1);
        }
      }
    });
    return result.get();
  }

  /**
   * Get the current log entry
   *
   * @return the current log entry, or null if there is no current log
   */
  @Nullable
  public LogEntry getLogEntry() {
    logIdLock.readLock().lock();
    try {
      return currentLog == null ? null : currentLog.getLogEntry();
    } finally {
      logIdLock.readLock().unlock();
    }
  }

  private synchronized void createLogger() {
    if (!logIdLock.isWriteLockedByCurrentThread()) {
      throw new IllegalStateException("createLoggers should be called with write lock held!");
    }

    if (currentLog != null) {
      throw new IllegalStateException("createLoggers should not be called when current log is set");
    }

    try {
      startLogMaker();
      Object next = nextLog.take();
      if (next instanceof Exception) {
        throw (Exception) next;
      }
      if (next instanceof DfsLogger) {
        currentLog = (DfsLogger) next;
        logId.incrementAndGet();
        log.info("Using next log {}", currentLog.getLogEntry());

        // When we successfully create a WAL, make sure to reset the Retry.
        if (createRetry != null) {
          createRetry = null;
        }

        this.createTime = System.currentTimeMillis();
      } else {
        throw new RuntimeException("Error: unexpected type seen: " + next);
      }
    } catch (Exception t) {
      if (createRetry == null) {
        createRetry = createRetryFactory.createRetry();
      }

      // We have more retries or we exceeded the maximum number of accepted failures
      if (createRetry.canRetry()) {
        // Use the createRetry and record the time in which we did so
        createRetry.useRetry();

        try {
          // Backoff
          createRetry.waitForNextAttempt(log, "create new WAL ");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      } else {
        // We didn't have retries or we failed too many times.
        Halt.halt(1, "Experienced too many errors creating WALs, giving up", t);
      }

      // The exception will trigger the log creation to be re-attempted.
      throw new RuntimeException(t);
    }
  }

  private synchronized void startLogMaker() {
    if (nextLogMaker != null) {
      return;
    }
    nextLogMaker = ThreadPools.getServerThreadPools().getPoolBuilder(TSERVER_WAL_CREATOR_POOL)
        .numCoreThreads(1).enableThreadPoolMetrics().build();
    nextLogMaker.execute(() -> {
      final VolumeManager fs = tserver.getVolumeManager();
      while (!nextLogMaker.isShutdown()) {
        log.debug("Creating next WAL");
        DfsLogger alog = null;

        try {
          alog = DfsLogger.createNew(tserver.getContext(), syncCounter, flushCounter,
              tserver.getAdvertiseAddress().toString());
        } catch (Exception t) {
          log.error("Failed to open WAL", t);
          // the log is not advertised in ZK yet, so we can just delete it if it exists
          if (alog != null) {
            try {
              alog.close();
            } catch (Exception e) {
              log.error("Failed to close WAL after it failed to open", e);
            }

            try {
              Path path = alog.getPath();
              if (fs.exists(path)) {
                fs.delete(path);
              }
            } catch (Exception e) {
              log.warn("Failed to delete a WAL that failed to open", e);
            }
          }

          try {
            nextLog.offer(t, 12, TimeUnit.HOURS);
          } catch (InterruptedException ex) {
            // Throw an Error, not an Exception, so the AccumuloUncaughtExceptionHandler
            // will log this then halt the VM.
            throw new Error("Next log maker thread interrupted", ex);
          }

          continue;
        }

        log.debug("Created next WAL {}", alog.getLogEntry());

        try {
          tserver.addNewLogMarker(alog);
        } catch (Exception t) {
          log.error("Failed to add new WAL marker for " + alog.getLogEntry(), t);

          try {
            // Intentionally not deleting walog because it may have been advertised in ZK. See
            // #949
            alog.close();
          } catch (Exception e) {
            log.error("Failed to close WAL after it failed to open", e);
          }

          // it's possible the log was advertised in ZK even though we got an
          // exception. If there's a chance the WAL marker may have been created,
          // this will ensure it's closed. Either the close will be written and
          // the GC will clean it up, or the tserver is about to die due to sesson
          // expiration and the GC will also clean it up.
          try {
            tserver.walogClosed(alog);
          } catch (Exception e) {
            log.error("Failed to close WAL that failed to open: " + alog.getLogEntry(), e);
          }

          try {
            nextLog.offer(t, 12, TimeUnit.HOURS);
          } catch (InterruptedException ex) {
            // Throw an Error, not an Exception, so the AccumuloUncaughtExceptionHandler
            // will log this then halt the VM.
            throw new Error("Next log maker thread interrupted", ex);
          }

          continue;
        }

        try {
          while (!nextLog.offer(alog, 12, TimeUnit.HOURS)) {
            log.info("Our WAL was not used for 12 hours: {}", alog.getLogEntry());
          }
        } catch (InterruptedException e) {
          // Throw an Error, not an Exception, so the AccumuloUncaughtExceptionHandler
          // will log this then halt the VM.
          throw new Error("Next log maker thread interrupted", e);
        }
      }
    });
  }

  private synchronized void close() throws IOException {
    if (!logIdLock.isWriteLockedByCurrentThread()) {
      throw new IllegalStateException("close should be called with write lock held!");
    }
    try {
      if (currentLog != null) {
        try {
          currentLog.close();
        } catch (DfsLogger.LogClosedException ex) {
          // ignore
        } catch (Exception ex) {
          log.error("Unable to cleanly close log " + currentLog.getLogEntry() + ": " + ex, ex);
        } finally {
          this.tserver.walogClosed(currentLog);
          currentLog = null;
          logSizeEstimate.set(0);
        }
      }
    } catch (Exception t) {
      throw new IOException(t);
    }
  }

  interface Writer {
    LoggerOperation write(DfsLogger logger) throws Exception;
  }

  private void write(final Collection<CommitSession> sessions, boolean mincFinish, Writer writer,
      Retry writeRetry) throws IOException {
    // Work very hard not to lock this during calls to the outside world
    int currentLogId = logId.get();

    boolean success = false;
    while (!success) {
      Throwable sawWriteFailure = null;
      try {
        // get a reference to the loggers that no other thread can touch
        AtomicInteger currentId = new AtomicInteger(-1);
        DfsLogger copy = initializeLoggers(currentId);
        currentLogId = currentId.get();

        // add the logger to the log set for the memory in the tablet,
        // update the metadata table if we've never used this tablet

        if (currentLogId == logId.get()) {
          for (CommitSession commitSession : sessions) {
            if (commitSession.beginUpdatingLogsUsed(copy, mincFinish)) {
              try {
                // Scribble out a tablet definition and then write to the metadata table
                write(singletonList(commitSession), false,
                    logger -> logger.defineTablet(commitSession), writeRetry);
              } finally {
                commitSession.finishUpdatingLogsUsed();
              }
            }
          }
        }

        // Make sure that the logs haven't changed out from underneath our copy
        if (currentLogId == logId.get()) {

          // write the mutation to the logs
          LoggerOperation lop = writer.write(copy);
          lop.await();

          // double-check: did the log set change?
          success = (currentLogId == logId.get());
        }
      } catch (DfsLogger.LogClosedException | ClosedChannelException ex) {
        writeRetry.logRetry(log, "Logs closed while writing", ex);
      } catch (Exception t) {
        writeRetry.logRetry(log, "Failed to write to WAL", t);
        sawWriteFailure = t;
        try {
          // Backoff
          writeRetry.waitForNextAttempt(log, "write to WAL");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      } finally {
        writeRetry.useRetry();
      }
      // Some sort of write failure occurred. Grab the write lock and reset the logs.
      // But since multiple threads will attempt it, only attempt the reset when
      // the logs haven't changed.
      final int finalCurrent = currentLogId;
      if (!success) {
        final ServiceLock tabletServerLock = tserver.getLock();
        if (sawWriteFailure != null) {
          log.info("WAL write failure, validating server lock in ZooKeeper", sawWriteFailure);
          if (tabletServerLock == null || !tabletServerLock.verifyLockAtSource()) {
            Halt.halt(-1, "Writing to WAL has failed and TabletServer lock does not exist",
                sawWriteFailure);
          }
        }

        testLockAndRun(logIdLock, new TestCallWithWriteLock() {

          @Override
          boolean test() {
            return finalCurrent == logId.get();
          }

          @Override
          void withWriteLock() throws IOException {
            close();
          }
        });
      }
    }
    // if the log gets too big or too old, reset it .. grab the write lock first
    logSizeEstimate.addAndGet(4 * 3); // event, tid, seq overhead
    testLockAndRun(logIdLock, new TestCallWithWriteLock() {
      @Override
      boolean test() {
        return (logSizeEstimate.get() > maxSize)
            || ((System.currentTimeMillis() - createTime) > maxAge);
      }

      @Override
      void withWriteLock() throws IOException {
        close();
      }
    });
  }

  /**
   * Log a single mutation. This method expects mutations that have a durability other than NONE.
   */
  public void log(final CommitSession commitSession, final Mutation m, final Durability durability)
      throws IOException {
    if (durability == Durability.DEFAULT || durability == Durability.NONE) {
      throw new IllegalArgumentException("Unexpected durability " + durability);
    }
    write(singletonList(commitSession), false, logger -> logger.log(commitSession, m, durability),
        writeRetryFactory.createRetry());
    logSizeEstimate.addAndGet(m.numBytes());
  }

  /**
   * Log mutations. This method expects mutations that have a durability other than NONE.
   */
  public void logManyTablets(Map<CommitSession,TabletMutations> loggables) throws IOException {
    if (loggables.isEmpty()) {
      return;
    }

    write(loggables.keySet(), false, logger -> logger.logManyTablets(loggables.values()),
        writeRetryFactory.createRetry());
    for (TabletMutations entry : loggables.values()) {
      if (entry.getMutations().size() < 1) {
        throw new IllegalArgumentException("logManyTablets: logging empty mutation list");
      }
      for (Mutation m : entry.getMutations()) {
        logSizeEstimate.addAndGet(m.numBytes());
      }
    }
  }

  public void minorCompactionFinished(final CommitSession commitSession, final long walogSeq,
      final Durability durability) throws IOException {
    write(singletonList(commitSession), true,
        logger -> logger.minorCompactionFinished(walogSeq, commitSession.getLogId(), durability),
        writeRetryFactory.createRetry());
  }

  public long minorCompactionStarted(final CommitSession commitSession, final long seq,
      final String fullyQualifiedFileName, final Durability durability) throws IOException {
    write(
        singletonList(commitSession), false, logger -> logger.minorCompactionStarted(seq,
            commitSession.getLogId(), fullyQualifiedFileName, durability),
        writeRetryFactory.createRetry());
    return seq;
  }

  private List<ResolvedSortedLog> resolve(Collection<LogEntry> walogs) {
    List<ResolvedSortedLog> sortedLogs = new ArrayList<>(walogs.size());
    for (var logEntry : walogs) {
      var sortedLog = sortedLogCache.get(logEntry, le1 -> {
        try {
          return ResolvedSortedLog.resolve(le1, tserver.getVolumeManager());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });

      sortedLogs.add(sortedLog);
    }
    return sortedLogs;
  }

  private CacheProvider createCacheProvider(TabletServerResourceManager resourceMgr) {
    return new BasicCacheProvider(
        LoggingBlockCache.wrap(CacheType.INDEX, resourceMgr.getIndexCache()),
        LoggingBlockCache.wrap(CacheType.DATA, resourceMgr.getDataCache()));
  }

  public boolean needsRecovery(ServerContext context, KeyExtent extent, Collection<LogEntry> walogs)
      throws IOException {
    try {
      var resourceMgr = tserver.getResourceManager();
      var cacheProvider = createCacheProvider(resourceMgr);
      SortedLogRecovery recovery =
          new SortedLogRecovery(context, resourceMgr.getFileLenCache(), cacheProvider);
      return recovery.needsRecovery(extent, resolve(walogs));
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  public void recover(ServerContext context, KeyExtent extent, Collection<LogEntry> walogs,
      Set<String> tabletFiles, MutationReceiver mr) throws IOException {
    try {
      var resourceMgr = tserver.getResourceManager();
      var cacheProvider = createCacheProvider(resourceMgr);
      SortedLogRecovery recovery =
          new SortedLogRecovery(context, resourceMgr.getFileLenCache(), cacheProvider);
      recovery.recover(extent, resolve(walogs), tabletFiles, mr);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }
}
