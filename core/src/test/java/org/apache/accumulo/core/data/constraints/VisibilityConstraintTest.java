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
package org.apache.accumulo.core.data.constraints;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.constraints.Constraint.Environment;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class VisibilityConstraintTest {

  private VisibilityConstraint vc;
  private Environment env;
  private Mutation mutation;

  static final ColumnVisibility good = new ColumnVisibility("good|bad");
  static final ColumnVisibility bad = new ColumnVisibility("good&bad");

  static final String D = "don't care";

  static final List<Short> ILLEGAL = Arrays.asList((short) 1);

  static final List<Short> ENOAUTH = Arrays.asList((short) 2);

  @BeforeEach
  public void setUp() {
    vc = new VisibilityConstraint();
    mutation = new Mutation("r");

    env = createMock(Environment.class);
    expect(env.getAuthorizationsContainer())
        .andReturn(new ArrayByteSequence("good".getBytes(UTF_8))::equals).anyTimes();

    replay(env);
  }

  @AfterEach
  public void tearDown() {
    verify(env);
  }

  @Test
  public void testNoVisibility() {
    mutation.put(D, D, D);
    assertNull(vc.check(env, mutation), "authorized");
  }

  @Test
  public void testVisibilityNoAuth() {
    mutation.put(D, D, bad, D);
    assertEquals(ENOAUTH, vc.check(env, mutation), "unauthorized");
  }

  @Test
  public void testGoodVisibilityAuth() {
    mutation.put(D, D, good, D);
    assertNull(vc.check(env, mutation), "authorized");
  }

  @Test
  public void testCachedVisibilities() {
    mutation.put(D, D, good, "v");
    mutation.put(D, D, good, "v2");
    assertNull(vc.check(env, mutation), "authorized");
  }

  @Test
  public void testMixedVisibilities() {
    mutation.put(D, D, bad, D);
    mutation.put(D, D, good, D);
    assertEquals(ENOAUTH, vc.check(env, mutation), "unauthorized");
  }

  @Test
  public void testIllegalVisibility() {
    mutation.put(D, D, good, D);
    // set an illegal visibility string
    mutation.at().family(D).qualifier(D).visibility("good&").put(D);
    assertEquals(ILLEGAL, vc.check(env, mutation), "unauthorized");
  }
}
