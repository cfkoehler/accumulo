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
package org.apache.accumulo.manager;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.lang.Math.min;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.FILES;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.LOGS;
import static org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType.MIGRATION;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.TabletAvailability;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.ResourceGroupId;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.logging.ConditionalLogger.EscalatingLogger;
import org.apache.accumulo.core.logging.TabletLogger;
import org.apache.accumulo.core.manager.state.TabletManagement;
import org.apache.accumulo.core.manager.state.TabletManagement.ManagementAction;
import org.apache.accumulo.core.manager.state.tables.TableState;
import org.apache.accumulo.core.manager.thrift.ManagerGoalState;
import org.apache.accumulo.core.manager.thrift.ManagerState;
import org.apache.accumulo.core.manager.thrift.TabletServerStatus;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.metadata.TabletState;
import org.apache.accumulo.core.metadata.schema.Ample;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.CurrentLocationColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.FutureLocationColumnFamily;
import org.apache.accumulo.core.metadata.schema.RootTabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.Location;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.Timer;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.accumulo.core.util.threads.Threads.AccumuloDaemonThread;
import org.apache.accumulo.manager.metrics.ManagerMetrics;
import org.apache.accumulo.manager.state.TableCounts;
import org.apache.accumulo.manager.state.TableStats;
import org.apache.accumulo.manager.upgrade.UpgradeCoordinator;
import org.apache.accumulo.server.ServiceEnvironmentImpl;
import org.apache.accumulo.server.compaction.CompactionJobGenerator;
import org.apache.accumulo.server.conf.CheckCompactionConfig;
import org.apache.accumulo.server.conf.TableConfiguration;
import org.apache.accumulo.server.fs.VolumeUtil;
import org.apache.accumulo.server.log.WalStateManager;
import org.apache.accumulo.server.log.WalStateManager.WalMarkerException;
import org.apache.accumulo.server.manager.LiveTServerSet.TServerConnection;
import org.apache.accumulo.server.manager.state.Assignment;
import org.apache.accumulo.server.manager.state.ClosableIterator;
import org.apache.accumulo.server.manager.state.DistributedStoreException;
import org.apache.accumulo.server.manager.state.TabletGoalState;
import org.apache.accumulo.server.manager.state.TabletManagementIterator;
import org.apache.accumulo.server.manager.state.TabletManagementParameters;
import org.apache.accumulo.server.manager.state.TabletStateStore;
import org.apache.accumulo.server.manager.state.UnassignedTablet;
import org.apache.hadoop.fs.Path;
import org.apache.thrift.TException;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

abstract class TabletGroupWatcher extends AccumuloDaemonThread {

  private static final Logger LOG = LoggerFactory.getLogger(TabletGroupWatcher.class);

  private static final Logger TABLET_UNLOAD_LOGGER =
      new EscalatingLogger(Manager.log, Duration.ofMinutes(5), 1000, Level.INFO);

  private final Manager manager;
  private final TabletStateStore store;
  private final TabletGroupWatcher dependentWatcher;
  final TableStats stats = new TableStats();
  private SortedSet<TServerInstance> lastScanServers = Collections.emptySortedSet();
  private final EventHandler eventHandler;
  private final ManagerMetrics metrics;
  private final WalStateManager walStateManager;
  private volatile Set<TServerInstance> filteredServersToShutdown = Set.of();

  TabletGroupWatcher(Manager manager, TabletStateStore store, TabletGroupWatcher dependentWatcher,
      ManagerMetrics metrics) {
    super("Watching " + store.name());
    this.manager = manager;
    this.store = store;
    this.dependentWatcher = dependentWatcher;
    this.metrics = metrics;
    this.walStateManager = new WalStateManager(manager.getContext());
    this.eventHandler = new EventHandler();
    manager.getEventCoordinator().addListener(store.getLevel(), eventHandler);
  }

  /** Should this {@code TabletGroupWatcher} suspend tablets? */
  abstract boolean canSuspendTablets();

  Map<TableId,TableCounts> getStats() {
    return stats.getLast();
  }

  TableCounts getStats(TableId tableId) {
    return stats.getLast(tableId);
  }

  public Ample.DataLevel getLevel() {
    return store.getLevel();
  }

  /**
   * True if the collection of live tservers specified in 'candidates' hasn't changed since the last
   * time an assignment scan was started.
   */
  synchronized boolean isSameTserversAsLastScan(Set<TServerInstance> candidates) {
    boolean same = candidates.equals(lastScanServers);
    if (!same && Manager.log.isTraceEnabled()) {
      Manager.log.trace("{} set difference candidates-lastScanServers : {}", store.name(),
          Sets.difference(candidates, lastScanServers));
      Manager.log.trace("{} set difference lastScanServers-candidates : {}", store.name(),
          Sets.difference(lastScanServers, candidates));
      Manager.log.trace("{} set intersection(lastScanServers,candidates) size : {}", store.name(),
          Sets.intersection(lastScanServers, candidates).size());
    }
    return same;
  }

  /**
   * Collection of data structures used to track Tablet assignments
   */
  private static class TabletLists {
    private final List<Assignment> assignments = new ArrayList<>();
    private final List<Assignment> assigned = new ArrayList<>();
    private final List<TabletMetadata> assignedToDeadServers = new ArrayList<>();
    private final List<TabletMetadata> suspendedToGoneServers = new ArrayList<>();
    private final Map<KeyExtent,UnassignedTablet> unassigned = new HashMap<>();
    private final Map<TServerInstance,List<Path>> logsForDeadServers = new TreeMap<>();
    // read only list of tablet servers that are not shutting down
    private final SortedMap<TServerInstance,TabletServerStatus> destinations;
    private final Map<ResourceGroupId,Set<TServerInstance>> currentTServerGrouping;
    private final List<VolumeUtil.VolumeReplacements> volumeReplacements = new ArrayList<>();

    public TabletLists(SortedMap<TServerInstance,TabletServerStatus> curTServers,
        Map<ResourceGroupId,Set<TServerInstance>> grouping,
        Set<TServerInstance> serversToShutdown) {

      var destinationsMod = new TreeMap<>(curTServers);
      if (!serversToShutdown.isEmpty()) {
        // Remove servers that are in the process of shutting down from the lists of tablet
        // servers.
        destinationsMod.keySet().removeAll(serversToShutdown);
        HashMap<ResourceGroupId,Set<TServerInstance>> groupingCopy = new HashMap<>();
        grouping.forEach((group, groupsServers) -> {
          if (Collections.disjoint(groupsServers, serversToShutdown)) {
            groupingCopy.put(group, groupsServers);
          } else {
            var serversCopy = new HashSet<>(groupsServers);
            serversCopy.removeAll(serversToShutdown);
            groupingCopy.put(group, Collections.unmodifiableSet(serversCopy));
          }
        });

        this.currentTServerGrouping = Collections.unmodifiableMap(groupingCopy);
      } else {
        this.currentTServerGrouping = grouping;
      }

      this.destinations = Collections.unmodifiableSortedMap(destinationsMod);
    }

    public void reset() {
      assignments.clear();
      assigned.clear();
      assignedToDeadServers.clear();
      suspendedToGoneServers.clear();
      unassigned.clear();
      volumeReplacements.clear();
    }
  }

  class EventHandler implements EventCoordinator.Listener {

    // Setting this to true to start with because its not know what happended before this object was
    // created, so just start off with full scan.
    private boolean needsFullScan = true;

    private final BlockingQueue<Range> rangesToProcess;

    class RangeProccessor implements Runnable {
      @Override
      public void run() {
        try {
          while (manager.stillManager()) {
            var range = rangesToProcess.poll(100, TimeUnit.MILLISECONDS);
            if (range == null) {
              // check to see if still the manager
              continue;
            }

            ArrayList<Range> ranges = new ArrayList<>();
            ranges.add(range);

            rangesToProcess.drainTo(ranges);

            if (!processRanges(ranges)) {
              setNeedsFullScan();
            }
          }
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }

    EventHandler() {
      rangesToProcess = new ArrayBlockingQueue<>(10000);

      Threads.createCriticalThread("TGW [" + store.name() + "] event range processor",
          new RangeProccessor()).start();
    }

    private synchronized void setNeedsFullScan() {
      needsFullScan = true;
      notifyAll();
    }

    public synchronized void clearNeedsFullScan() {
      needsFullScan = false;
    }

    public synchronized boolean isNeedsFullScan() {
      return needsFullScan;
    }

    @Override
    public void process(EventCoordinator.Event event) {

      switch (event.getScope()) {
        case ALL:
        case DATA_LEVEL:
          setNeedsFullScan();
          break;
        case TABLE:
        case TABLE_RANGE:
          if (!rangesToProcess.offer(event.getExtent().toMetaRange())) {
            Manager.log.debug("[{}] unable to process event range {} because queue is full",
                store.name(), event.getExtent());
            setNeedsFullScan();
          }
          break;
        default:
          throw new IllegalArgumentException("Unhandled scope " + event.getScope());
      }
    }

    synchronized void waitForFullScan(long millis) {
      if (!needsFullScan) {
        try {
          wait(millis);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private boolean processRanges(List<Range> ranges) {
    if (manager.getManagerGoalState() == ManagerGoalState.CLEAN_STOP) {
      return false;
    }

    TabletManagementParameters tabletMgmtParams = createTabletManagementParameters(false);

    var currentTservers = getCurrentTservers(tabletMgmtParams.getOnlineTsevers());
    if (currentTservers.isEmpty()) {
      return false;
    }

    try (var iter = store.iterator(ranges, tabletMgmtParams)) {
      long t1 = System.currentTimeMillis();
      manageTablets(iter, tabletMgmtParams, currentTservers, false);
      long t2 = System.currentTimeMillis();
      Manager.log.debug(String.format("[%s]: partial scan time %.2f seconds for %,d ranges",
          store.name(), (t2 - t1) / 1000., ranges.size()));
    } catch (Exception e) {
      Manager.log.error("Error processing {} ranges for store {} ", ranges.size(), store.name(), e);
    }

    return true;
  }

  private final Set<KeyExtent> hostingRequestInProgress = new ConcurrentSkipListSet<>();

  public void hostOndemand(Collection<KeyExtent> extents) {
    // This is only expected to be called for the user level
    Preconditions.checkState(getLevel() == Ample.DataLevel.USER);

    final List<KeyExtent> inProgress = new ArrayList<>();
    extents.forEach(ke -> {
      if (hostingRequestInProgress.add(ke)) {
        LOG.info("Tablet hosting requested for: {} ", ke);
        inProgress.add(ke);
      } else {
        LOG.trace("Ignoring hosting request because another thread is currently processing it {}",
            ke);
      }
    });
    // Do not add any code here, it may interfere with the finally block removing extents from
    // hostingRequestInProgress
    try (var mutator = manager.getContext().getAmple().conditionallyMutateTablets()) {
      inProgress.forEach(ke -> mutator.mutateTablet(ke).requireAbsentOperation()
          .requireTabletAvailability(TabletAvailability.ONDEMAND).requireAbsentLocation()
          .setHostingRequested()
          .submit(TabletMetadata::getHostingRequested, () -> "host ondemand"));

      List<Range> ranges = new ArrayList<>();

      mutator.process().forEach((extent, result) -> {
        if (result.getStatus() == Ample.ConditionalResult.Status.ACCEPTED) {
          // cache this success for a bit
          ranges.add(extent.toMetaRange());
        } else {
          if (LOG.isTraceEnabled()) {
            // only read the metadata if the logging is enabled
            LOG.trace("Failed to set hosting request {}", result.readMetadata());
          }
        }
      });

      if (!ranges.isEmpty()) {
        processRanges(ranges);
      }
    } finally {
      inProgress.forEach(hostingRequestInProgress::remove);
    }
  }

  private TabletManagementParameters
      createTabletManagementParameters(boolean lookForTabletsNeedingVolReplacement) {

    HashMap<Ample.DataLevel,Boolean> parentLevelUpgrade = new HashMap<>();
    UpgradeCoordinator.UpgradeStatus upgradeStatus = manager.getUpgradeStatus();
    for (var level : Ample.DataLevel.values()) {
      parentLevelUpgrade.put(level, upgradeStatus.isParentLevelUpgraded(level));
    }

    Set<TServerInstance> shutdownServers;
    if (store.getLevel() == Ample.DataLevel.USER) {
      shutdownServers = manager.shutdownServers();
    } else {
      // Use the servers to shutdown filtered by the dependent watcher. These are servers to
      // shutdown that the dependent watcher has determined it has no tablets hosted on or assigned
      // to.
      shutdownServers = dependentWatcher.getFilteredServersToShutdown();
    }

    var tServersSnapshot = manager.tserversSnapshot();

    var tabletMgmtParams = new TabletManagementParameters(manager.getManagerState(),
        parentLevelUpgrade, manager.onlineTables(), tServersSnapshot, shutdownServers,
        store.getLevel(), manager.getCompactionHints(store.getLevel()), canSuspendTablets(),
        lookForTabletsNeedingVolReplacement ? manager.getContext().getVolumeReplacements()
            : Map.of(),
        manager.getSteadyTime());

    if (LOG.isTraceEnabled()) {
      // Log the json that will be passed to iterators to make tablet filtering decisions.
      LOG.trace("{}:{}", TabletManagementParameters.class.getSimpleName(),
          tabletMgmtParams.serialize());
    }

    return tabletMgmtParams;
  }

  private Set<TServerInstance> getFilteredServersToShutdown() {
    return filteredServersToShutdown;
  }

  private static class TableMgmtStats {
    final int[] counts = new int[TabletState.values().length];
    private int totalUnloaded;
    private long totalVolumeReplacements;
    private int tabletsWithErrors;
  }

  private TableMgmtStats manageTablets(Iterator<TabletManagement> iter,
      TabletManagementParameters tableMgmtParams,
      SortedMap<TServerInstance,TabletServerStatus> currentTServers, boolean isFullScan)
      throws TException, DistributedStoreException, WalMarkerException, IOException {

    // When upgrading the Manager needs the TabletGroupWatcher
    // to assign and balance the root and metadata tables, but
    // the Manager does not fully start up until the upgrade
    // is complete. This means that objects like the Splitter
    // are not going to be initialized and the Coordinator
    // is not going to be started.
    final boolean currentlyUpgrading = manager.isUpgrading();
    if (currentlyUpgrading) {
      LOG.debug(
          "Currently upgrading, splits and compactions for tables in level {} will occur once upgrade is completed.",
          store.getLevel());
    }

    final TableMgmtStats tableMgmtStats = new TableMgmtStats();
    final boolean shuttingDownAllTabletServers =
        tableMgmtParams.getServersToShutdown().equals(currentTServers.keySet());
    if (shuttingDownAllTabletServers && !isFullScan) {
      // If we are shutting down all of the TabletServers, then don't process any events
      // from the EventCoordinator.
      LOG.debug("Partial scan requested, but aborted due to shutdown of all TabletServers");
      return tableMgmtStats;
    }

    int unloaded = 0;

    TabletLists tLists = new TabletLists(currentTServers, tableMgmtParams.getGroupedTServers(),
        tableMgmtParams.getServersToShutdown());

    CompactionJobGenerator compactionGenerator =
        new CompactionJobGenerator(new ServiceEnvironmentImpl(manager.getContext()),
            tableMgmtParams.getCompactionHints(), tableMgmtParams.getSteadyTime());

    try {
      CheckCompactionConfig.validate(manager.getConfiguration(), Level.TRACE);
      this.metrics.clearCompactionServiceConfigurationError();
    } catch (RuntimeException | ReflectiveOperationException e) {
      this.metrics.setCompactionServiceConfigurationError();
      LOG.error(
          "Error validating compaction configuration, all {} compactions are paused until the configuration is fixed.",
          store.getLevel(), e);
      compactionGenerator = null;
    }

    Set<TServerInstance> filteredServersToShutdown =
        new HashSet<>(tableMgmtParams.getServersToShutdown());

    while (iter.hasNext()) {
      final TabletManagement mti = iter.next();
      if (mti == null) {
        throw new IllegalStateException("State store returned a null ManagerTabletInfo object");
      }

      final String mtiError = mti.getErrorMessage();
      if (mtiError != null) {
        LOG.warn(
            "Error on TabletServer trying to get Tablet management information for metadata tablet. Error message: {}",
            mtiError);
        this.metrics.incrementTabletGroupWatcherError(this.store.getLevel());
        tableMgmtStats.tabletsWithErrors++;
        continue;
      }

      final TabletMetadata tm = mti.getTabletMetadata();
      final TableId tableId = tm.getTableId();
      // ignore entries for tables that do not exist in zookeeper
      if (manager.getTableManager().getTableState(tableId) == TableState.UNKNOWN) {
        continue;
      }

      // Don't overwhelm the tablet servers with work
      if (tLists.unassigned.size() + unloaded
          > Manager.MAX_TSERVER_WORK_CHUNK * currentTServers.size()
          || tLists.volumeReplacements.size() > 1000) {
        flushChanges(tLists);
        tLists.reset();
        unloaded = 0;
      }

      final TableConfiguration tableConf = manager.getContext().getTableConfiguration(tableId);

      TabletState state = TabletState.compute(tm, currentTServers.keySet());
      if (state == TabletState.ASSIGNED_TO_DEAD_SERVER) {
        /*
         * This code exists to deal with a race condition caused by two threads running in this
         * class that compute tablets actions. One thread does full scans and the other reacts to
         * events and does partial scans. Below is an example of the race condition this is
         * handling.
         *
         * - TGW Thread 1 : reads the set of tablets servers and its empty
         *
         * - TGW Thread 2 : reads the set of tablet servers and its [TS1]
         *
         * - TGW Thread 2 : Sees tabletX without a location and assigns it to TS1
         *
         * - TGW Thread 1 : Sees tabletX assigned to TS1 and assumes it's assigned to a dead tablet
         * server because its set of live servers is the empty set.
         *
         * To deal with this race condition, this code recomputes the tablet state using the latest
         * tservers when a tablet is seen assigned to a dead tserver.
         */

        TabletState newState = TabletState.compute(tm, manager.tserversSnapshot().getTservers());
        if (newState != state) {
          LOG.debug("Tablet state changed when using latest set of tservers {} {} {}",
              tm.getExtent(), state, newState);
          state = newState;
        }
      }
      tableMgmtStats.counts[state.ordinal()]++;

      // This is final because nothing in this method should change the goal. All computation of the
      // goal should be done in TabletGoalState.compute() so that all parts of the Accumulo code
      // will compute a consistent goal.
      final TabletGoalState goal = TabletGoalState.compute(tm, state,
          manager.getBalanceManager().getBalancer(), tableMgmtParams);

      final Set<ManagementAction> actions = mti.getActions();

      if (actions.contains(ManagementAction.NEEDS_RECOVERY) && goal != TabletGoalState.HOSTED) {
        LOG.warn("Tablet has wals, but goal is not hosted. Tablet: {}, goal:{}", tm.getExtent(),
            goal);
      }

      if (actions.contains(ManagementAction.NEEDS_VOLUME_REPLACEMENT)) {
        tableMgmtStats.totalVolumeReplacements++;
        if (state == TabletState.UNASSIGNED || state == TabletState.SUSPENDED) {
          var volRep =
              VolumeUtil.computeVolumeReplacements(tableMgmtParams.getVolumeReplacements(), tm);
          if (volRep.logsToRemove.size() + volRep.filesToRemove.size() > 0) {
            if (tm.getLocation() != null) {
              // since the totalVolumeReplacements counter was incremented, should try this again
              // later after its unassigned
              LOG.debug("Volume replacement needed for {} but it has a location {}.",
                  tm.getExtent(), tm.getLocation());
            } else if (tm.getOperationId() != null) {
              LOG.debug("Volume replacement needed for {} but it has an active operation {}.",
                  tm.getExtent(), tm.getOperationId());
            } else {
              LOG.debug("Volume replacement needed for {}.", tm.getExtent());
              // buffer replacements so that multiple mutations can be done at once
              tLists.volumeReplacements.add(volRep);
            }
          } else {
            LOG.debug("Volume replacement evaluation for {} returned no changes.", tm.getExtent());
          }
        } else {
          LOG.debug("Volume replacement needed for {} but its tablet state is {}.", tm.getExtent(),
              state);
        }
      }

      if (actions.contains(ManagementAction.BAD_STATE) && tm.isFutureAndCurrentLocationSet()) {
        Manager.log.error("{}, saw tablet with multiple locations, which should not happen",
            tm.getExtent());
        logIncorrectTabletLocations(tm);
        // take no further action for this tablet
        continue;
      }

      final Location location = tm.getLocation();
      Location current = null;
      Location future = null;
      if (tm.hasCurrent()) {
        current = tm.getLocation();
      } else {
        future = tm.getLocation();
      }
      TabletLogger.missassigned(tm.getExtent(), goal.toString(), state.toString(),
          future != null ? future.getServerInstance() : null,
          current != null ? current.getServerInstance() : null, tm.getLogs().size());

      if (isFullScan) {
        stats.update(tableId, state);
      }

      if (Manager.log.isTraceEnabled()) {
        Manager.log.trace(
            "[{}] Shutting down all Tservers: {}, dependentCount: {} Extent: {}, state: {}, goal: {} actions:{} #wals:{}",
            store.name(), tableMgmtParams.getServersToShutdown().equals(currentTServers.keySet()),
            dependentWatcher == null ? "null" : dependentWatcher.assignedOrHosted(), tm.getExtent(),
            state, goal, actions, tm.getLogs().size());
      }

      final boolean needsSplit = actions.contains(ManagementAction.NEEDS_SPLITTING);
      if (!currentlyUpgrading && needsSplit) {
        LOG.debug("{} may need splitting.", tm.getExtent());
        manager.getSplitter().initiateSplit(tm.getExtent());
      }

      if (!currentlyUpgrading && actions.contains(ManagementAction.NEEDS_COMPACTING)
          && compactionGenerator != null) {
        // Check if tablet needs splitting, priority should be giving to splits over
        // compactions because it's best to compact after a split
        if (!needsSplit) {
          var jobs = compactionGenerator.generateJobs(tm,
              TabletManagementIterator.determineCompactionKinds(actions));
          LOG.debug("{} may need compacting adding {} jobs", tm.getExtent(), jobs.size());
          manager.getCompactionCoordinator().addJobs(tm, jobs);
        } else {
          LOG.trace("skipping compaction job generation because {} may need splitting.",
              tm.getExtent());
        }
      }

      if (actions.contains(ManagementAction.NEEDS_LOCATION_UPDATE)
          || actions.contains(ManagementAction.NEEDS_RECOVERY)) {

        if (tm.getLocation() != null) {
          filteredServersToShutdown.remove(tm.getLocation().getServerInstance());
        }

        if (goal == TabletGoalState.HOSTED) {

          // RecoveryManager.recoverLogs will return false when all of the logs
          // have been sorted so that recovery can occur. Delay the hosting of
          // the Tablet until the sorting is finished.
          if ((state != TabletState.HOSTED && actions.contains(ManagementAction.NEEDS_RECOVERY))
              && manager.recoveryManager.recoverLogs(tm.getExtent(), tm.getLogs())) {
            LOG.debug("Not hosting {} as it needs recovery, logs: {}", tm.getExtent(),
                tm.getLogs().size());
            continue;
          }
          switch (state) {
            case ASSIGNED_TO_DEAD_SERVER:
              hostDeadTablet(tLists, tm, location);
              break;
            case SUSPENDED:
              hostSuspendedTablet(tLists, tm, location, tableConf);
              break;
            case UNASSIGNED:
              hostUnassignedTablet(tLists, tm.getExtent(),
                  new UnassignedTablet(location, tm.getLast()));
              break;
            case ASSIGNED:
              // Send another reminder
              tLists.assigned.add(new Assignment(tm.getExtent(),
                  future != null ? future.getServerInstance() : null, tm.getLast()));
              break;
            case HOSTED:
              break;
          }
        } else {
          switch (state) {
            case SUSPENDED:
              // Request a move to UNASSIGNED, so as to allow balancing to continue.
              tLists.suspendedToGoneServers.add(tm);
              break;
            case ASSIGNED_TO_DEAD_SERVER:
              unassignDeadTablet(tLists, tm);
              break;
            case HOSTED:
              TServerConnection client =
                  manager.tserverSet.getConnection(location.getServerInstance());
              if (client != null) {
                TABLET_UNLOAD_LOGGER.trace("[{}] Requesting TabletServer {} unload {} {}",
                    store.name(), location.getServerInstance(), tm.getExtent(), goal.howUnload());
                client.unloadTablet(manager.managerLock, tm.getExtent(), goal.howUnload(),
                    manager.getSteadyTime().getMillis());
                tableMgmtStats.totalUnloaded++;
                unloaded++;
              } else {
                Manager.log.warn("Could not connect to server {}", location);
              }
              break;
            case ASSIGNED:
            case UNASSIGNED:
              break;
          }
        }
      }
    }

    flushChanges(tLists);

    if (isFullScan) {
      this.filteredServersToShutdown = Set.copyOf(filteredServersToShutdown);
    }

    return tableMgmtStats;
  }

  private SortedMap<TServerInstance,TabletServerStatus>
      getCurrentTservers(Set<TServerInstance> onlineTservers) {
    // Get the current status for the current list of tservers
    final SortedMap<TServerInstance,TabletServerStatus> currentTServers = new TreeMap<>();
    for (TServerInstance entry : onlineTservers) {
      currentTServers.put(entry, manager.getTserverStatus().status.get(entry));
    }
    return currentTServers;
  }

  @Override
  public void run() {
    int[] oldCounts = new int[TabletState.values().length];
    boolean lookForTabletsNeedingVolReplacement = true;

    while (manager.stillManager()) {
      if (!eventHandler.isNeedsFullScan()) {
        // If an event handled by the EventHandler.RangeProcessor indicated
        // that we need to do a full scan, then do it. Otherwise wait a bit
        // before re-checking the tablets.
        sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
      }

      final long waitTimeBetweenScans = manager.getConfiguration()
          .getTimeInMillis(Property.MANAGER_TABLET_GROUP_WATCHER_INTERVAL);

      TabletManagementParameters tableMgmtParams =
          createTabletManagementParameters(lookForTabletsNeedingVolReplacement);
      var currentTServers = getCurrentTservers(tableMgmtParams.getOnlineTsevers());

      ClosableIterator<TabletManagement> iter = null;
      try {
        if (currentTServers.isEmpty()) {
          eventHandler.waitForFullScan(waitTimeBetweenScans);
          synchronized (this) {
            lastScanServers = Collections.emptySortedSet();
          }
          continue;
        }

        stats.begin();

        ManagerState managerState = tableMgmtParams.getManagerState();

        // Clear the need for a full scan before starting a full scan inorder to detect events that
        // happen during the full scan.
        eventHandler.clearNeedsFullScan();

        iter = store.iterator(tableMgmtParams);
        manager.getCompactionCoordinator().getJobQueues().beginFullScan(store.getLevel());
        var tabletMgmtStats = manageTablets(iter, tableMgmtParams, currentTServers, true);
        manager.getCompactionCoordinator().getJobQueues().endFullScan(store.getLevel());

        // If currently looking for volume replacements, determine if the next round needs to look.
        if (lookForTabletsNeedingVolReplacement) {
          // Continue to look for tablets needing volume replacement if there was an error
          // processing tablets in the call to manageTablets() or if we are still performing volume
          // replacement. We only want to stop looking for tablets that need volume replacement when
          // we have successfully processed all tablet metadata and no more volume replacements are
          // being performed.
          Manager.log.debug("[{}] saw {} tablets needing volume replacement", store.name(),
              tabletMgmtStats.totalVolumeReplacements);
          lookForTabletsNeedingVolReplacement = tabletMgmtStats.totalVolumeReplacements != 0
              || tabletMgmtStats.tabletsWithErrors != 0;
          if (!lookForTabletsNeedingVolReplacement) {
            Manager.log.debug("[{}] no longer looking for volume replacements", store.name());
          }
        }

        // provide stats after flushing changes to avoid race conditions w/ delete table
        stats.end(managerState);
        Manager.log.trace("[{}] End stats collection: {}", store.name(), stats);

        // Report changes
        for (TabletState state : TabletState.values()) {
          int i = state.ordinal();
          if (tabletMgmtStats.counts[i] > 0 && tabletMgmtStats.counts[i] != oldCounts[i]) {
            manager.nextEvent.event(store.getLevel(), "[%s]: %d tablets are %s", store.name(),
                tabletMgmtStats.counts[i], state.name());
          }
        }
        Manager.log.debug(String.format("[%s]: full scan time %.2f seconds", store.name(),
            stats.getScanTime() / 1000.));
        oldCounts = tabletMgmtStats.counts;
        if (tabletMgmtStats.totalUnloaded > 0) {
          manager.nextEvent.event(store.getLevel(), "[%s]: %d tablets unloaded", store.name(),
              tabletMgmtStats.totalUnloaded);
        }

        synchronized (this) {
          lastScanServers = ImmutableSortedSet.copyOf(currentTServers.keySet());
        }
        if (manager.tserverSet.getCurrentServers().equals(currentTServers.keySet())) {
          Manager.log.debug(String.format("[%s] sleeping for %.2f seconds", store.name(),
              waitTimeBetweenScans / 1000.));
          eventHandler.waitForFullScan(waitTimeBetweenScans);
        } else {
          // Create an event at the store level, this will force the next scan to be a full scan
          manager.nextEvent.event(store.getLevel(), "Set of tablet servers changed");
        }
      } catch (Exception ex) {
        Manager.log.error("Error processing table state for store " + store.name(), ex);
        sleepUninterruptibly(Manager.WAIT_BETWEEN_ERRORS, TimeUnit.MILLISECONDS);
      } finally {
        if (iter != null) {
          try {
            iter.close();
          } catch (IOException ex) {
            Manager.log.warn("Error closing TabletLocationState iterator: " + ex, ex);
          }
        }
      }
    }
  }

  private void unassignDeadTablet(TabletLists tLists, TabletMetadata tm) throws WalMarkerException {
    tLists.assignedToDeadServers.add(tm);
    if (!tLists.logsForDeadServers.containsKey(tm.getLocation().getServerInstance())) {
      tLists.logsForDeadServers.put(tm.getLocation().getServerInstance(),
          walStateManager.getWalsInUse(tm.getLocation().getServerInstance()));
    }
  }

  private void hostUnassignedTablet(TabletLists tLists, KeyExtent tablet,
      UnassignedTablet unassignedTablet) {
    // maybe it's a finishing migration
    TServerInstance dest =
        manager.getContext().getAmple().readTablet(tablet, MIGRATION).getMigration();
    if (dest != null) {
      // if destination is still good, assign it
      if (tLists.destinations.containsKey(dest)) {
        tLists.assignments.add(new Assignment(tablet, dest, unassignedTablet.getLastLocation()));
      } else {
        tLists.unassigned.put(tablet, unassignedTablet);
      }
    } else {
      tLists.unassigned.put(tablet, unassignedTablet);
    }
  }

  private void hostSuspendedTablet(TabletLists tLists, TabletMetadata tm, Location location,
      TableConfiguration tableConf) {
    if (manager.getSteadyTime().minus(tm.getSuspend().suspensionTime).toMillis()
        < tableConf.getTimeInMillis(Property.TABLE_SUSPEND_DURATION)) {
      // Tablet is suspended. See if its tablet server is back.
      TServerInstance returnInstance = null;
      Iterator<TServerInstance> find = tLists.destinations
          .tailMap(new TServerInstance(tm.getSuspend().server, " ")).keySet().iterator();
      if (find.hasNext()) {
        TServerInstance found = find.next();
        if (found.getHostAndPort().equals(tm.getSuspend().server)) {
          returnInstance = found;
        }
      }

      // Old tablet server is back. Return this tablet to its previous owner.
      if (returnInstance != null) {
        tLists.assignments.add(new Assignment(tm.getExtent(), returnInstance, tm.getLast()));
      }
      // else - tablet server not back. Don't ask for a new assignment right now.

    } else {
      // Treat as unassigned, ask for a new assignment.
      tLists.unassigned.put(tm.getExtent(), new UnassignedTablet(location, tm.getLast()));
    }
  }

  private void hostDeadTablet(TabletLists tLists, TabletMetadata tm, Location location)
      throws WalMarkerException {
    tLists.assignedToDeadServers.add(tm);
    TServerInstance tserver = tm.getLocation().getServerInstance();
    if (!tLists.logsForDeadServers.containsKey(tserver)) {
      tLists.logsForDeadServers.put(tserver, walStateManager.getWalsInUse(tserver));
    }
  }

  /**
   * Read tablet metadata entries for tablet that have multiple locations. Not using Ample because
   * it throws an exception when tablets have multiple locations.
   */
  private Stream<? extends Entry<Key,Value>> getMetaEntries(KeyExtent extent)
      throws TableNotFoundException, InterruptedException, KeeperException {
    Ample.DataLevel level = Ample.DataLevel.of(extent.tableId());
    if (level == Ample.DataLevel.ROOT) {
      return RootTabletMetadata.read(manager.getContext()).getKeyValues();
    } else {
      Scanner scanner = manager.getContext().createScanner(level.metaTable(), Authorizations.EMPTY);
      scanner.fetchColumnFamily(CurrentLocationColumnFamily.NAME);
      scanner.fetchColumnFamily(FutureLocationColumnFamily.NAME);
      scanner.setRange(new Range(extent.toMetaRow()));
      return scanner.stream().onClose(scanner::close);
    }
  }

  private void logIncorrectTabletLocations(TabletMetadata tabletMetadata) {
    try {
      Map<Key,Value> locations = new HashMap<>();
      KeyExtent extent = tabletMetadata.getExtent();

      try (Stream<? extends Entry<Key,Value>> entries = getMetaEntries(extent)) {
        entries.forEach(entry -> {
          var family = entry.getKey().getColumnFamily();
          if (family.equals(CurrentLocationColumnFamily.NAME)
              || family.equals(FutureLocationColumnFamily.NAME)) {
            locations.put(entry.getKey(), entry.getValue());
          }
        });
      }

      if (locations.size() <= 1) {
        Manager.log.trace("Tablet {} seems to have correct location based on inspection",
            tabletMetadata.getExtent());
      } else {
        for (Map.Entry<Key,Value> entry : locations.entrySet()) {
          TServerInstance alive = manager.tserverSet.find(entry.getValue().toString());
          Manager.log.debug("Saw duplicate location key:{} value:{} alive:{} ", entry.getKey(),
              entry.getValue(), alive != null);
        }
      }
    } catch (Exception e) {
      Manager.log.error("Error attempting investigation of metadata {}: {}",
          tabletMetadata == null ? null : tabletMetadata.getExtent(), e, e);
    }
  }

  private int assignedOrHosted() {
    return assignedOrHosted(stats.getLast());
  }

  private int assignedOrHosted(Map<TableId,TableCounts> last) {
    int result = 0;
    for (TableCounts counts : last.values()) {
      result += counts.assigned() + counts.hosted();
    }
    return result;
  }

  private void handleDeadTablets(TabletLists tLists)
      throws WalMarkerException, DistributedStoreException {
    var deadTablets = tLists.assignedToDeadServers;
    var deadLogs = tLists.logsForDeadServers;

    if (!deadTablets.isEmpty()) {
      int maxServersToShow = min(deadTablets.size(), 100);
      Manager.log.debug("{} assigned to dead servers: {}...", deadTablets.size(),
          deadTablets.subList(0, maxServersToShow));
      Manager.log.debug("logs for dead servers: {}", deadLogs);
      if (canSuspendTablets()) {
        store.suspend(deadTablets, deadLogs, manager.getSteadyTime());
      } else {
        store.unassign(deadTablets, deadLogs);
      }
      markDeadServerLogsAsClosed(walStateManager, deadLogs);
      manager.nextEvent.event(store.getLevel(),
          "Marked %d tablets as suspended because they don't have current servers",
          deadTablets.size());
    }
    if (!tLists.suspendedToGoneServers.isEmpty()) {
      int maxServersToShow = min(deadTablets.size(), 100);
      Manager.log.debug(deadTablets.size() + " suspended to gone servers: "
          + deadTablets.subList(0, maxServersToShow) + "...");
      store.unsuspend(tLists.suspendedToGoneServers);
    }
  }

  private void getAssignmentsFromBalancer(TabletLists tLists,
      Map<KeyExtent,UnassignedTablet> unassigned) {
    if (!tLists.destinations.isEmpty()) {
      Map<KeyExtent,TServerInstance> assignedOut = new HashMap<>();
      manager.getBalanceManager().getAssignments(tLists.destinations, tLists.currentTServerGrouping,
          unassigned, assignedOut);
      for (Entry<KeyExtent,TServerInstance> assignment : assignedOut.entrySet()) {
        if (unassigned.containsKey(assignment.getKey())) {
          if (assignment.getValue() != null) {
            if (!tLists.destinations.containsKey(assignment.getValue())) {
              Manager.log.warn(
                  "balancer assigned {} to a tablet server that is not current {} ignoring",
                  assignment.getKey(), assignment.getValue());
              continue;
            }

            final UnassignedTablet unassignedTablet = unassigned.get(assignment.getKey());
            tLists.assignments.add(new Assignment(assignment.getKey(), assignment.getValue(),
                unassignedTablet != null ? unassignedTablet.getLastLocation() : null));
          }
        } else {
          Manager.log.warn(
              "{} load balancer assigning tablet that was not nominated for assignment {}",
              store.name(), assignment.getKey());
        }
      }

      if (!unassigned.isEmpty() && assignedOut.isEmpty()) {
        Manager.log.warn("Load balancer failed to assign any tablets");
      }
    }
  }

  private final Lock flushLock = new ReentrantLock();

  private void flushChanges(TabletLists tLists)
      throws DistributedStoreException, TException, WalMarkerException {
    var unassigned = Collections.unmodifiableMap(tLists.unassigned);
    Timer timer;
    flushLock.lock();
    try {
      // This method was originally only ever called by one thread. The code was modified so that
      // two threads could possibly call this flush method concurrently. It is not clear the
      // following methods are thread safe so a lock is acquired out of caution. Balancer plugins
      // may not expect multiple threads to call them concurrently, Accumulo has not done this in
      // the past. The log recovery code needs to be evaluated for thread safety.
      handleDeadTablets(tLists);

      int beforeSize = tLists.assignments.size();
      timer = Timer.startNew();

      getAssignmentsFromBalancer(tLists, unassigned);
      if (!unassigned.isEmpty()) {
        Manager.log.debug("[{}] requested assignments for {} tablets and got {} in {} ms",
            store.name(), unassigned.size(), tLists.assignments.size() - beforeSize,
            timer.elapsed(TimeUnit.MILLISECONDS));
      }
    } finally {
      flushLock.unlock();
    }

    Set<KeyExtent> failedFuture = Set.of();
    if (!tLists.assignments.isEmpty()) {
      Manager.log.info("Assigning {} tablets", tLists.assignments.size());
      failedFuture = store.setFutureLocations(tLists.assignments);
    }
    tLists.assignments.addAll(tLists.assigned);
    timer.restart();
    for (Assignment a : tLists.assignments) {
      if (failedFuture.contains(a.tablet)) {
        // do not ask a tserver to load a tablet where the future location could not be set
        continue;
      }
      try {
        TServerConnection client = manager.tserverSet.getConnection(a.server);
        if (client != null) {
          client.assignTablet(manager.managerLock, a.tablet);
          manager.assignedTablet(a.tablet);
        } else {
          Manager.log.warn("Could not connect to server {} for assignment of {}", a.server,
              a.tablet);
        }
      } catch (TException tException) {
        Manager.log.warn("Could not connect to server {} for assignment of {}", a.server, a.tablet,
            tException);
      }
    }
    if (!tLists.assignments.isEmpty()) {
      Manager.log.debug("[{}] sent {} assignment messages in {} ms", store.name(),
          tLists.assignments.size(), timer.elapsed(TimeUnit.MILLISECONDS));
    }
    replaceVolumes(tLists.volumeReplacements);
  }

  private void replaceVolumes(List<VolumeUtil.VolumeReplacements> volumeReplacementsList) {
    try (var tabletsMutator = manager.getContext().getAmple().conditionallyMutateTablets()) {
      for (VolumeUtil.VolumeReplacements vr : volumeReplacementsList) {
        var tabletMutator =
            tabletsMutator.mutateTablet(vr.tabletMeta.getExtent()).requireAbsentOperation()
                .requireAbsentLocation().requireSame(vr.tabletMeta, FILES, LOGS);
        vr.logsToRemove.forEach(tabletMutator::deleteWal);
        vr.logsToAdd.forEach(tabletMutator::putWal);

        vr.filesToRemove.forEach(tabletMutator::deleteFile);
        vr.filesToAdd.forEach(tabletMutator::putFile);

        tabletMutator.submit(tm -> {
          // Check to see if the logs and files are removed. Checking if the new files or logs were
          // added has a race condition, those could have been successfully added and then removed
          // before this check runs, like if a compaction runs. Once the old volumes are removed
          // nothing should ever add them again.
          var logsRemoved =
              Collections.disjoint(Set.copyOf(tm.getLogs()), Set.copyOf(vr.logsToRemove));
          var filesRemoved = Collections.disjoint(tm.getFiles(), Set.copyOf(vr.filesToRemove));
          LOG.debug(
              "replaceVolume conditional mutation rejection check {} logsRemoved:{} filesRemoved:{}",
              tm.getExtent(), logsRemoved, filesRemoved);
          return logsRemoved && filesRemoved;
        }, () -> "replace volume");
      }

      tabletsMutator.process().forEach((extent, result) -> {
        if (result.getStatus() == Ample.ConditionalResult.Status.REJECTED) {
          // log that failure happened, should try again later
          LOG.debug("Failed to update volumes for tablet {}", extent);
        }
      });
    }

  }

  private static void markDeadServerLogsAsClosed(WalStateManager mgr,
      Map<TServerInstance,List<Path>> logsForDeadServers) throws WalMarkerException {
    for (Entry<TServerInstance,List<Path>> server : logsForDeadServers.entrySet()) {
      for (Path path : server.getValue()) {
        mgr.closeWal(server.getKey(), path);
      }
    }
  }
}
