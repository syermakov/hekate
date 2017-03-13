/*
 * Copyright 2017 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate.spring.bean.internal;

import io.hekate.HekateTestBase;
import io.hekate.cluster.ClusterService;
import io.hekate.cluster.ClusterTopology;
import io.hekate.core.Hekate;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class XsdTestBase extends HekateTestBase {
    @Autowired
    protected ApplicationContext spring;

    @Autowired
    @Qualifier("node1")
    protected Hekate node1;

    @Autowired
    @Qualifier("node2")
    protected Hekate node2;

    @Test
    public void test() throws Exception {
        assertNotNull(node1);
        assertNotNull(node2);

        node1.get(ClusterService.class).futureOf(top -> top.size() == 2).get(3, TimeUnit.SECONDS);
        node2.get(ClusterService.class).futureOf(top -> top.size() == 2).get(3, TimeUnit.SECONDS);

        ClusterTopology top1 = node1.get(ClusterService.class).getTopology();
        ClusterTopology top2 = node2.get(ClusterService.class).getTopology();

        assertEquals(2, top1.size());
        assertEquals(2, top2.size());

        assertTrue(top1.contains(node1.getNode()));
        assertTrue(top1.contains(node2.getNode()));

        assertTrue(top2.contains(node1.getNode()));
        assertTrue(top2.contains(node2.getNode()));
    }

    @Override
    protected void assertAllThreadsStopped() throws InterruptedException {
        // Do not check threads since Spring context gets terminated after all tests have been run.
    }
}