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
package org.apache.accumulo.core.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.lock.ServiceLockPaths.ServiceLockPath;
import org.junit.jupiter.api.Test;

public class ServiceLockTest {

  private final ServiceLockPath path = ServiceLockPaths.parse(Optional.of(Constants.ZMANAGER_LOCK),
      "/fake/root" + Constants.ZMANAGER_LOCK);

  @Test
  public void testSortAndFindLowestPrevPrefix() {
    List<String> children = new ArrayList<>();
    children.add("zlock#00000000-0000-0000-0000-ffffffffffff#0000000007");
    children.add("zlock#00000000-0000-0000-0000-eeeeeeeeeeee#0000000010");
    children.add("zlock#00000000-0000-0000-0000-bbbbbbbbbbbb#0000000006");
    children.add("zlock#00000000-0000-0000-0000-dddddddddddd#0000000008");
    children.add("zlock#00000000-0000-0000-0000-bbbbbbbbbbbb#0000000004");
    children.add("zlock-123456789");
    children.add("zlock#00000000-0000-0000-0000-cccccccccccc#0000000003");
    children.add("zlock#00000000-0000-0000-0000-aaaaaaaaaaaa#0000000002");
    children.add("zlock#987654321");
    children.add("zlock#00000000-0000-0000-0000-aaaaaaaaaaaa#0000000001");

    final List<String> validChildren = ServiceLock.validateAndSort(path, children);

    assertEquals(8, validChildren.size());
    assertEquals("zlock#00000000-0000-0000-0000-aaaaaaaaaaaa#0000000001", validChildren.get(0));
    assertEquals("zlock#00000000-0000-0000-0000-aaaaaaaaaaaa#0000000002", validChildren.get(1));
    assertEquals("zlock#00000000-0000-0000-0000-cccccccccccc#0000000003", validChildren.get(2));
    assertEquals("zlock#00000000-0000-0000-0000-bbbbbbbbbbbb#0000000004", validChildren.get(3));
    assertEquals("zlock#00000000-0000-0000-0000-bbbbbbbbbbbb#0000000006", validChildren.get(4));
    assertEquals("zlock#00000000-0000-0000-0000-ffffffffffff#0000000007", validChildren.get(5));
    assertEquals("zlock#00000000-0000-0000-0000-dddddddddddd#0000000008", validChildren.get(6));
    assertEquals("zlock#00000000-0000-0000-0000-eeeeeeeeeeee#0000000010", validChildren.get(7));

    assertEquals("zlock#00000000-0000-0000-0000-bbbbbbbbbbbb#0000000004",
        ServiceLock.findLowestPrevPrefix(validChildren,
            "zlock#00000000-0000-0000-0000-ffffffffffff#0000000007"));

    assertEquals("zlock#00000000-0000-0000-0000-aaaaaaaaaaaa#0000000001",
        ServiceLock.findLowestPrevPrefix(validChildren,
            "zlock#00000000-0000-0000-0000-cccccccccccc#0000000003"));

    assertEquals("zlock#00000000-0000-0000-0000-dddddddddddd#0000000008",
        ServiceLock.findLowestPrevPrefix(validChildren,
            "zlock#00000000-0000-0000-0000-eeeeeeeeeeee#0000000010"));

    assertThrows(IndexOutOfBoundsException.class,
        () -> ServiceLock.findLowestPrevPrefix(validChildren,
            "zlock#00000000-0000-0000-0000-aaaaaaaaaaaa#0000000001"));

    assertThrows(IndexOutOfBoundsException.class,
        () -> ServiceLock.findLowestPrevPrefix(validChildren,
            "zlock#00000000-0000-0000-0000-XXXXXXXXXXXX#0000000099"));
  }

  @Test
  public void rejectInvalidUUID() {
    List<String> children = new ArrayList<>();
    String uuid = "1-1-1-1-1";
    String seq = "1234567891";
    children.add("zlock#" + uuid + "#" + seq);

    // pass as UUID, but fail on string compare.
    assertEquals("00000001-0001-0001-0001-000000000001", UUID.fromString(uuid).toString());
    final List<String> validChildren = ServiceLock.validateAndSort(path, children);
    assertEquals(0, validChildren.size());
  }

  @Test
  public void uuidTest() {
    List<String> children = new ArrayList<>();
    String uuid = "219ad0f6-ebe0-416e-a20f-c0f32922841d";
    String seq = "1234567891";
    children.add("zlock#" + uuid + "#" + seq);

    final List<String> validChildren = ServiceLock.validateAndSort(path, children);
    assertEquals(1, validChildren.size());
    String candidate = validChildren.get(0);
    assertTrue(candidate.contains(uuid));
    assertTrue(candidate.contains(seq));
  }
}
