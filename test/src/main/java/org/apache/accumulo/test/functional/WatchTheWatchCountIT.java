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
package org.apache.accumulo.test.functional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Socket;
import java.time.Duration;
import java.util.Properties;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.conf.ClientProperty;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloConfigImpl;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.net.HostAndPort;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

// ACCUMULO-2757 - make sure we don't make too many more watchers
public class WatchTheWatchCountIT extends ConfigurableMacBase {
  private static final Logger log = LoggerFactory.getLogger(WatchTheWatchCountIT.class);

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(1);
  }

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.getClusterServerConfiguration().setNumDefaultTabletServers(3);
  }

  @SuppressFBWarnings(value = "UNENCRYPTED_SOCKET",
      justification = "unencrypted socket is okay for testing")
  @Test
  public void test() throws Exception {
    Properties props = getClientProperties();
    try (AccumuloClient c = Accumulo.newClient().from(props).build()) {
      String[] tableNames = getUniqueNames(3);
      for (String tableName : tableNames) {
        c.tableOperations().create(tableName);
      }
      c.tableOperations().list();
      String zooKeepers = ClientProperty.INSTANCE_ZOOKEEPERS.getValue(props);
      // expect about 30-45 base + about 12 per table in a single-node 3-tserver instance
      final long MIN = 75L;
      final long MAX = 300L;
      long total = 0;
      final HostAndPort hostAndPort = HostAndPort.fromString(zooKeepers);
      for (int i = 0; i < 5; i++) {
        try (Socket socket = new Socket(hostAndPort.getHost(), hostAndPort.getPort())) {
          socket.getOutputStream().write("wchs\n".getBytes(UTF_8), 0, 5);
          byte[] buffer = new byte[1024];
          int n = socket.getInputStream().read(buffer);
          String response = new String(buffer, 0, n, UTF_8);
          total = Long.parseLong(response.split(":")[1].trim());
          log.info("Total: {}", total);
          if (total > MIN && total < MAX) {
            break;
          }
          log.debug("Expected number of watchers to be contained in ({}, {}), currently have {}"
              + ". Sleeping and retrying", MIN, MAX, total);
          Thread.sleep(5000);
        }
      }
      log.info("Number of watchers to be expected in range: ({}, {}), currently have {}", MIN, MAX,
          total);

      assertTrue(total > MIN && total < MAX, "Expected number of watchers to be contained in ("
          + MIN + ", " + MAX + "), but actually was " + total);

    }
  }

}
