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
package org.apache.accumulo.test;

import static org.apache.accumulo.core.Constants.IMPORT_MAPPINGS_FILE;
import static org.apache.accumulo.core.util.LazySingletons.RANDOM;
import static org.apache.accumulo.test.TableOperationsIT.setExpectedTabletAvailability;
import static org.apache.accumulo.test.TableOperationsIT.verifyTabletAvailabilities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;

import org.apache.accumulo.cluster.AccumuloCluster;
import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.CompactionConfig;
import org.apache.accumulo.core.client.admin.ImportConfiguration;
import org.apache.accumulo.core.client.admin.TabletAvailability;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.TabletId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.manager.state.tables.TableState;
import org.apache.accumulo.core.metadata.StoredTabletFile;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.DataFileColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.ServerColumnFamily;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloClusterImpl;
import org.apache.accumulo.test.util.FileMetadataUtil;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

/**
 * ImportTable didn't correctly place absolute paths in metadata. This resulted in the imported
 * table only being usable when the actual HDFS directory for Accumulo was the same as
 * Property.INSTANCE_DFS_DIR. If any other HDFS directory was used, any interactions with the table
 * would fail because the relative path in the metadata table (created by the ImportTable process)
 * would be converted to a non-existent absolute path.
 * <p>
 * ACCUMULO-3215
 */
public class ImportExportIT extends AccumuloClusterHarness {

  private static final Logger log = LoggerFactory.getLogger(ImportExportIT.class);

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(1);
  }

  private void doExportImportThenScan(boolean fenced, AccumuloClient client, String srcTable,
      String destTable) throws Exception {

    client.tableOperations().create(srcTable);

    try (BatchWriter bw = client.createBatchWriter(srcTable)) {
      for (int row = 0; row < 1000; row++) {
        Mutation m = new Mutation("row_" + String.format("%010d", row));
        for (int col = 0; col < 100; col++) {
          m.put(Integer.toString(col), "", Integer.toString(col * 2));
        }
        bw.addMutation(m);
      }
    }

    client.tableOperations().compact(srcTable, null, null, true, true);

    int expected = 100000;
    // Test that files with ranges and are fenced work with export/import
    if (fenced) {
      // Split file into 3 ranges of 10000, 20000, and 5000 for a total of 35000
      FileMetadataUtil.splitFilesIntoRanges(getServerContext(), srcTable, createRanges());
      expected = 35000;
    }

    // Make a directory we can use to throw the export and import directories
    // Must exist on the filesystem the cluster is running.
    FileSystem fs = cluster.getFileSystem();
    Path baseDir = createBaseDir(cluster, getClass());
    Path exportDir = new Path(baseDir, "export");
    fs.deleteOnExit(exportDir);
    Path importDirA = new Path(baseDir, "import-a");
    Path importDirB = new Path(baseDir, "import-b");
    fs.deleteOnExit(importDirA);
    fs.deleteOnExit(importDirB);
    for (Path p : new Path[] {exportDir, importDirA, importDirB}) {
      assertTrue(fs.mkdirs(p), "Failed to create " + p);
    }

    Set<String> importDirs = Set.of(importDirA.toString(), importDirB.toString());

    Path[] importDirAry = new Path[] {importDirA, importDirB};

    log.info("Exporting table to {}", exportDir);
    log.info("Importing table from {}", importDirs);

    // test fast fail offline check
    assertThrows(IllegalStateException.class,
        () -> client.tableOperations().exportTable(srcTable, exportDir.toString()));

    // Offline the table
    client.tableOperations().offline(srcTable, true);
    // Then export it
    client.tableOperations().exportTable(srcTable, exportDir.toString());

    // Make sure the distcp.txt file that exporttable creates is available
    Path distcp = new Path(exportDir, "distcp.txt");
    fs.deleteOnExit(distcp);
    assertTrue(fs.exists(distcp), "Distcp file doesn't exist");
    FSDataInputStream is = fs.open(distcp);
    BufferedReader reader = new BufferedReader(new InputStreamReader(is));

    // Copy each file that was exported to one of the imports directory
    String line;

    while ((line = reader.readLine()) != null) {
      Path p = new Path(line.substring(5));
      assertTrue(fs.exists(p), "File doesn't exist: " + p);
      Path importDir = importDirAry[RANDOM.get().nextInt(importDirAry.length)];
      Path dest = new Path(importDir, p.getName());
      assertFalse(fs.exists(dest), "Did not expect " + dest + " to exist");
      FileUtil.copy(fs, p, fs, dest, false, fs.getConf());
    }

    reader.close();

    log.info("Import dir A: {}", Arrays.toString(fs.listStatus(importDirA)));
    log.info("Import dir B: {}", Arrays.toString(fs.listStatus(importDirB)));

    // Import the exported data into a new table
    client.tableOperations().importTable(destTable, importDirs, ImportConfiguration.empty());

    // Get the table ID for the table that the importtable command created
    final String tableId = client.tableOperations().tableIdMap().get(destTable);
    assertNotNull(tableId);

    // Get all `file` colfams from the metadata table for the new table
    log.info("Imported into table with ID: {}", tableId);

    try (
        Scanner s = client.createScanner(SystemTables.METADATA.tableName(), Authorizations.EMPTY)) {
      s.setRange(TabletsSection.getRange(TableId.of(tableId)));
      s.fetchColumnFamily(DataFileColumnFamily.NAME);
      ServerColumnFamily.DIRECTORY_COLUMN.fetch(s);

      // Should find a single entry
      for (Entry<Key,Value> fileEntry : s) {
        Key k = fileEntry.getKey();
        String value = fileEntry.getValue().toString();
        if (k.getColumnFamily().equals(DataFileColumnFamily.NAME)) {
          // The file should be an absolute URI (file:///...), not a relative path
          // (/b-000.../I000001.rf)
          var tabFile = StoredTabletFile.of(k.getColumnQualifier());
          // Verify that the range is set correctly on the StoredTabletFile
          assertEquals(fenced,
              !tabFile.getRange().isInfiniteStartKey() || !tabFile.getRange().isInfiniteStopKey());
          assertFalse(looksLikeRelativePath(tabFile.getMetadataPath()),
              "Imported files should have absolute URIs, not relative: " + tabFile);
        } else if (k.getColumnFamily().equals(ServerColumnFamily.NAME)) {
          assertFalse(looksLikeRelativePath(value),
              "Server directory should have absolute URI, not relative: " + value);
        } else {
          fail("Got expected pair: " + k + "=" + fileEntry.getValue());
        }
      }

    }
    // Online the original table before we verify equivalence
    client.tableOperations().online(srcTable, true);

    verifyTableEquality(client, srcTable, destTable, expected);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testExportImportThenScan(boolean fenced) throws Exception {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      String[] tableNames = getUniqueNames(2);
      doExportImportThenScan(fenced, client, tableNames[0], tableNames[1]);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testExportImportSameTableNameThenScan(boolean fenced) throws Exception {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      String ns1 = "namespace1";
      client.namespaceOperations().create(ns1);
      String ns2 = "namespace2";
      client.namespaceOperations().create(ns2);
      String tableName = getUniqueNames(1)[0];
      doExportImportThenScan(fenced, client, ns1 + "." + tableName, ns2 + "." + tableName);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testExportImportOffline(boolean fenced) throws Exception {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {

      String[] tableNames = getUniqueNames(2);
      String srcTable = tableNames[0];
      String destTable = tableNames[1];
      client.tableOperations().create(srcTable);

      try (BatchWriter bw = client.createBatchWriter(srcTable)) {
        for (int row = 0; row < 1000; row++) {
          Mutation m = new Mutation("row_" + String.format("%010d", row));
          for (int col = 0; col < 100; col++) {
            m.put(Integer.toString(col), "", Integer.toString(col * 2));
          }
          bw.addMutation(m);
        }
      }

      client.tableOperations().compact(srcTable, new CompactionConfig());

      int expected = 100000;
      // Test that files with ranges and are fenced work with export/import
      if (fenced) {
        // Split file into 3 ranges of 10000, 20000, and 5000 for a total of 35000
        FileMetadataUtil.splitFilesIntoRanges(getServerContext(), srcTable, createRanges());
        expected = 35000;
      }

      // Make export and import directories
      FileSystem fs = cluster.getFileSystem();
      Path baseDir = createBaseDir(cluster, getClass());
      Path exportDir = new Path(baseDir, "export");
      fs.deleteOnExit(exportDir);
      Path importDirA = new Path(baseDir, "import-a");
      Path importDirB = new Path(baseDir, "import-b");
      fs.deleteOnExit(importDirA);
      fs.deleteOnExit(importDirB);
      for (Path p : new Path[] {exportDir, importDirA, importDirB}) {
        assertTrue(fs.mkdirs(p), "Failed to create " + p);
      }

      Set<String> importDirs = Set.of(importDirA.toString(), importDirB.toString());

      Path[] importDirAry = new Path[] {importDirA, importDirB};

      log.info("Exporting table to {}", exportDir);
      log.info("Importing table from {}", importDirs);

      // Offline the table
      client.tableOperations().offline(srcTable, true);
      // Then export it
      client.tableOperations().exportTable(srcTable, exportDir.toString());

      // Make sure the distcp.txt file that exporttable creates is available
      Path distcp = new Path(exportDir, "distcp.txt");
      fs.deleteOnExit(distcp);
      assertTrue(fs.exists(distcp), "Distcp file doesn't exist");
      FSDataInputStream is = fs.open(distcp);
      BufferedReader reader = new BufferedReader(new InputStreamReader(is));

      // Copy each file that was exported to one of the imports directory
      String line;

      while ((line = reader.readLine()) != null) {
        Path p = new Path(line.substring(5));
        assertTrue(fs.exists(p), "File doesn't exist: " + p);
        Path importDir = importDirAry[RANDOM.get().nextInt(importDirAry.length)];
        Path dest = new Path(importDir, p.getName());
        assertFalse(fs.exists(dest), "Did not expect " + dest + " to exist");
        FileUtil.copy(fs, p, fs, dest, false, fs.getConf());
      }

      reader.close();

      log.info("Import dir A: {}", Arrays.toString(fs.listStatus(importDirA)));
      log.info("Import dir B: {}", Arrays.toString(fs.listStatus(importDirB)));

      // Import the exported data into a new offline table and keep mappings file
      ImportConfiguration importConfig =
          ImportConfiguration.builder().setKeepOffline(true).setKeepMappings(true).build();
      client.tableOperations().importTable(destTable, importDirs, importConfig);

      // Get the table ID for the table that the importtable command created
      final String tableId = client.tableOperations().tableIdMap().get(destTable);
      assertNotNull(tableId);

      log.info("Imported into table with ID: {}", tableId);

      // verify the new table is offline
      assertFalse(client.tableOperations().isOnline(destTable), "Table should have been offline.");
      assertEquals(getServerContext().getTableState(TableId.of(tableId)), TableState.OFFLINE);
      client.tableOperations().online(destTable, true);

      // Get all `file` colfams from the metadata table for the new table
      try (Scanner s =
          client.createScanner(SystemTables.METADATA.tableName(), Authorizations.EMPTY)) {
        s.setRange(TabletsSection.getRange(TableId.of(tableId)));
        s.fetchColumnFamily(DataFileColumnFamily.NAME);
        ServerColumnFamily.DIRECTORY_COLUMN.fetch(s);

        // Should find a single entry
        for (Entry<Key,Value> fileEntry : s) {
          Key k = fileEntry.getKey();
          String value = fileEntry.getValue().toString();
          if (k.getColumnFamily().equals(DataFileColumnFamily.NAME)) {
            // file should be an absolute URI (file:///...), not relative (/b-000.../I000001.rf)
            var tabFile = StoredTabletFile.of(k.getColumnQualifier());
            // Verify that the range is set correctly on the StoredTabletFile
            assertEquals(fenced, !tabFile.getRange().isInfiniteStartKey()
                || !tabFile.getRange().isInfiniteStopKey());
            assertFalse(looksLikeRelativePath(tabFile.getMetadataPath()),
                "Imported files should have absolute URIs, not relative: "
                    + tabFile.getMetadataPath());
          } else if (k.getColumnFamily().equals(ServerColumnFamily.NAME)) {
            assertFalse(looksLikeRelativePath(value),
                "Server directory should have absolute URI, not relative: " + value);
          } else {
            fail("Got expected pair: " + k + "=" + fileEntry.getValue());
          }
        }
      }
      // Online the original table before we verify equivalence
      client.tableOperations().online(srcTable, true);

      verifyTableEquality(client, srcTable, destTable, expected);
      assertTrue(verifyMappingsFile(tableId), "Did not find mappings file");
    }
  }

  /**
   * Ensure all tablets in an imported table are ONDEMAND.
   *
   * Create a table with multiple tablets, each with a different tablet availability. Export the
   * table. Import the table and make sure that all tablets on the imported table have the ONDEMAND
   * tablet availability.
   *
   * This test case stitches together code from TableOperationsIT to create the table, set up the
   * tablets and verify. The code to export then import the table is from
   * ImportExportIT.testExportImportOffline()
   */
  @Test
  public void testImportedTableIsOnDemand() throws Exception {

    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      String[] tableNames = getUniqueNames(2);
      String srcTable = tableNames[0];
      String destTable = tableNames[1];

      client.tableOperations().create(srcTable);
      String srcTableId = client.tableOperations().tableIdMap().get(srcTable);

      // add split 'h' and 'q'. Leave first as ONDEMAND, set second to UNHOSTED, and third to HOSTED
      SortedSet<Text> splits = Sets.newTreeSet(Arrays.asList(new Text("h"), new Text("q")));
      client.tableOperations().addSplits(srcTable, splits);
      Range range = new Range(new Text("h"), false, new Text("q"), true);
      client.tableOperations().setTabletAvailability(srcTable, range, TabletAvailability.UNHOSTED);
      range = new Range(new Text("q"), false, null, true);
      client.tableOperations().setTabletAvailability(srcTable, range, TabletAvailability.HOSTED);

      // verify
      Map<TabletId,TabletAvailability> expectedTabletAvailability = new HashMap<>();
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, "h", null,
          TabletAvailability.ONDEMAND);
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, "q", "h",
          TabletAvailability.UNHOSTED);
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, null, "q",
          TabletAvailability.HOSTED);
      verifyTabletAvailabilities(client, srcTable, new Range(), expectedTabletAvailability);

      // Add a split within each of the existing tablets. Adding 'd', 'm', and 'v'
      splits = Sets.newTreeSet(Arrays.asList(new Text("d"), new Text("m"), new Text("v")));
      client.tableOperations().addSplits(srcTable, splits);

      // verify results
      expectedTabletAvailability.clear();
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, "d", null,
          TabletAvailability.ONDEMAND);
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, "h", "d",
          TabletAvailability.ONDEMAND);
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, "m", "h",
          TabletAvailability.UNHOSTED);
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, "q", "m",
          TabletAvailability.UNHOSTED);
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, "v", "q",
          TabletAvailability.HOSTED);
      setExpectedTabletAvailability(expectedTabletAvailability, srcTableId, null, "v",
          TabletAvailability.HOSTED);
      verifyTabletAvailabilities(client, srcTable, new Range(), expectedTabletAvailability);

      // Make a directory we can use to throw the export and import directories
      // Must exist on the filesystem the cluster is running.
      FileSystem fs = cluster.getFileSystem();
      Path baseDir = createBaseDir(cluster, getClass());
      Path exportDir = new Path(baseDir, "export");
      fs.deleteOnExit(exportDir);
      Path importDirA = new Path(baseDir, "import-a");
      Path importDirB = new Path(baseDir, "import-b");
      fs.deleteOnExit(importDirA);
      fs.deleteOnExit(importDirB);
      for (Path p : new Path[] {exportDir, importDirA, importDirB}) {
        assertTrue(fs.mkdirs(p), "Failed to create " + p);
      }

      Set<String> importDirs = Set.of(importDirA.toString(), importDirB.toString());

      Path[] importDirAry = new Path[] {importDirA, importDirB};

      log.info("Exporting table to {}", exportDir);
      log.info("Importing table from {}", importDirs);

      // test fast fail offline check
      assertThrows(IllegalStateException.class,
          () -> client.tableOperations().exportTable(srcTable, exportDir.toString()));

      // Offline the table
      client.tableOperations().offline(srcTable, true);
      // Then export it
      client.tableOperations().exportTable(srcTable, exportDir.toString());

      // Make sure the distcp.txt file that exporttable creates is available
      Path distcp = new Path(exportDir, "distcp.txt");
      fs.deleteOnExit(distcp);
      assertTrue(fs.exists(distcp), "Distcp file doesn't exist");
      FSDataInputStream is = fs.open(distcp);
      BufferedReader reader = new BufferedReader(new InputStreamReader(is));

      // Copy each file that was exported to one of the imports directory
      String line;

      while ((line = reader.readLine()) != null) {
        Path p = new Path(line.substring(5));
        assertTrue(fs.exists(p), "File doesn't exist: " + p);
        Path importDir = importDirAry[RANDOM.get().nextInt(importDirAry.length)];
        Path dest = new Path(importDir, p.getName());
        assertFalse(fs.exists(dest), "Did not expect " + dest + " to exist");
        FileUtil.copy(fs, p, fs, dest, false, fs.getConf());
      }

      reader.close();

      log.info("Import dir A: {}", Arrays.toString(fs.listStatus(importDirA)));
      log.info("Import dir B: {}", Arrays.toString(fs.listStatus(importDirB)));

      // Import the exported data into a new table
      client.tableOperations().importTable(destTable, importDirs, ImportConfiguration.empty());

      // Get the table ID for the table that the importtable command created
      final String destTableId = client.tableOperations().tableIdMap().get(destTable);
      assertNotNull(destTableId);

      // Get all `file` colfams from the metadata table for the new table
      log.info("Imported into table with ID: {}", destTableId);

      client.tableOperations().getTabletInformation(destTable, new Range())
          .forEach(tabletInformation -> assertEquals(TabletAvailability.ONDEMAND,
              tabletInformation.getTabletAvailability(),
              "Expected all tablets in imported table to be ONDEMAND"));
    }
  }

  private boolean verifyMappingsFile(String destTableId) throws IOException {
    AccumuloCluster cluster = getCluster();
    assertTrue(cluster instanceof MiniAccumuloClusterImpl);
    MiniAccumuloClusterImpl mac = (MiniAccumuloClusterImpl) cluster;
    FileSystem fs = getCluster().getFileSystem();
    // the following path expects mini to be configured with a single volume
    final Path tablePath = new Path(mac.getSiteConfiguration().get(Property.INSTANCE_VOLUMES) + "/"
        + Constants.TABLE_DIR + "/" + destTableId);
    FileStatus[] status = fs.listStatus(tablePath);
    for (FileStatus tabletDir : status) {
      var contents = fs.listStatus(tabletDir.getPath());
      for (FileStatus file : contents) {
        if (file.isFile() && file.getPath().getName().equals(IMPORT_MAPPINGS_FILE)) {
          log.debug("Found mappings file: {}", file);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Validate that files exported with Accumulo 2.x without fence ranges can be imported into
   * version that require the fenced ranges (4.0 and later)
   */
  @Test
  public void importV2data() throws Exception {
    final String dataRoot = "./target/classes/v2_import_test";
    final String dataSrc = dataRoot + "/data";
    final String importDir = dataRoot + "/import";

    // copy files each run will "move the files" on import, allows multiple runs in IDE without
    // rebuild
    java.nio.file.Path importDirPath = java.nio.file.Path.of(importDir);
    java.nio.file.Files.createDirectories(importDirPath);
    FileUtils.copyDirectory(java.nio.file.Path.of(dataSrc).toFile(),
        java.nio.file.Path.of(importDir).toFile());

    String table = getUniqueNames(1)[0];

    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      log.debug("importing from: {} into table: {}", importDir, table);
      client.tableOperations().importTable(table, importDir);

      int rowCount = 0;
      try (Scanner s = client.createScanner(table, Authorizations.EMPTY)) {
        for (Entry<Key,Value> entry : s) {
          log.trace("data:{}", entry);
          rowCount++;
        }
      }
      assertEquals(7, rowCount);
      int metaFileCount = 0;
      try (Scanner s =
          client.createScanner(SystemTables.METADATA.tableName(), Authorizations.EMPTY)) {
        TableId tid = TableId.of(client.tableOperations().tableIdMap().get(table));
        s.setRange(TabletsSection.getRange(tid));
        s.fetchColumnFamily(DataFileColumnFamily.NAME);
        for (Entry<Key,Value> entry : s) {
          log.trace("metadata file:{}", entry);
          metaFileCount++;
        }
      }
      final List<Text> expectedSplits = List.of(new Text("2"), new Text("4"), new Text("6"));
      assertEquals(expectedSplits, client.tableOperations().listSplits(table));
      assertEquals(4, metaFileCount);
    }
  }

  private void verifyTableEquality(AccumuloClient client, String srcTable, String destTable,
      int expected) throws Exception {
    Iterator<Entry<Key,Value>> src =
        client.createScanner(srcTable, Authorizations.EMPTY).iterator();
    Iterator<Entry<Key,Value>> dest =
        client.createScanner(destTable, Authorizations.EMPTY).iterator();
    assertTrue(src.hasNext(), "Could not read any data from source table");
    assertTrue(dest.hasNext(), "Could not read any data from destination table");
    int entries = 0;
    while (src.hasNext() && dest.hasNext()) {
      Entry<Key,Value> orig = src.next();
      Entry<Key,Value> copy = dest.next();
      assertEquals(orig.getKey(), copy.getKey());
      assertEquals(orig.getValue(), copy.getValue());
      entries++;
    }
    assertFalse(src.hasNext(), "Source table had more data to read");
    assertFalse(dest.hasNext(), "Dest table had more data to read");
    assertEquals(expected, entries);
  }

  private boolean looksLikeRelativePath(String uri) {
    if (uri.startsWith("/" + Constants.BULK_PREFIX)) {
      return uri.charAt(10) == '/';
    } else {
      return uri.startsWith("/" + Constants.CLONE_PREFIX);
    }
  }

  private Set<Range> createRanges() {
    // Split file into ranges of 10000, 20000, and 5000 for a total of 35000
    return Set.of(
        new Range("row_" + String.format("%010d", 99), false, "row_" + String.format("%010d", 199),
            true),
        new Range("row_" + String.format("%010d", 299), false, "row_" + String.format("%010d", 499),
            true),
        new Range("row_" + String.format("%010d", 699), false, "row_" + String.format("%010d", 749),
            true));
  }

  public static Path createBaseDir(AccumuloCluster cluster, Class<?> clazz) throws IOException {
    FileSystem fs = cluster.getFileSystem();
    log.info("Using FileSystem: " + fs);
    Path baseDir = new Path(cluster.getTemporaryPath(), clazz.getName());
    fs.deleteOnExit(baseDir);
    if (fs.exists(baseDir)) {
      log.info("{} exists on filesystem, deleting", baseDir);
      assertTrue(fs.delete(baseDir, true), "Failed to deleted " + baseDir);
    }
    log.info("Creating {}", baseDir);
    assertTrue(fs.mkdirs(baseDir), "Failed to create " + baseDir);
    return baseDir;
  }
}
