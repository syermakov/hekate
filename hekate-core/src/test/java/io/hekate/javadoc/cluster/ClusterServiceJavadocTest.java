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

package io.hekate.javadoc.cluster;

import io.hekate.HekateInstanceTestBase;
import io.hekate.cluster.ClusterService;
import io.hekate.cluster.ClusterServiceFactory;
import io.hekate.cluster.ClusterTopology;
import io.hekate.cluster.event.ClusterChangeEvent;
import io.hekate.cluster.event.ClusterJoinEvent;
import io.hekate.cluster.event.ClusterLeaveEvent;
import io.hekate.cluster.health.DefaultFailureDetector;
import io.hekate.cluster.health.DefaultFailureDetectorConfig;
import io.hekate.cluster.seed.multicast.MulticastSeedNodeProvider;
import io.hekate.cluster.seed.multicast.MulticastSeedNodeProviderConfig;
import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class ClusterServiceJavadocTest extends HekateInstanceTestBase {
    @Test
    public void exampleClusterService() throws Exception {
        // Start:configure
        // Prepare service factory and configure some options.
        ClusterServiceFactory factory = new ClusterServiceFactory()
            .withGossipInterval(1000)
            .withSeedNodeProvider(new MulticastSeedNodeProvider(
                new MulticastSeedNodeProviderConfig()
                    .withGroup("224.1.2.12")
                    .withPort(45454)
                    .withInterval(200)
                    .withWaitTime(1000)
            ))
            .withFailureDetector(new DefaultFailureDetector(
                new DefaultFailureDetectorConfig()
                    .withHeartbeatInterval(500)
                    .withHeartbeatLossThreshold(6)
                    .withFailureDetectionQuorum(2)
            ));

        // ...other options...

        // Start node.
        Hekate hekate = new HekateBootstrap()
            .withService(factory)
            .join();
        // End:configure

        try {
            // Start:get_service
            ClusterService cluster = hekate.get(ClusterService.class);
            // End:get_service

            // Start:cluster_event_listener
            cluster.addListener(event -> {
                switch (event.getType()) {
                    case JOIN: {
                        ClusterJoinEvent join = event.asJoin();

                        System.out.println("Joined : " + join.getTopology());

                        break;
                    }
                    case CHANGE: {
                        ClusterChangeEvent change = event.asChange();

                        System.out.println("Topology change :" + change.getTopology());
                        System.out.println("      added nodes=" + change.getAdded());
                        System.out.println("    removed nodes=" + change.getRemoved());

                        break;
                    }
                    case LEAVE: {
                        ClusterLeaveEvent leave = event.asLeave();

                        System.out.println("Left : " + leave.getTopology());

                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Unsupported event type: " + event);
                    }
                }
            });
            // End:cluster_event_listener

            // Start:list_topology
            // Immutable snapshot of the current cluster topology.
            ClusterTopology topology = cluster.getTopology();

            System.out.println("   Local node: " + topology.getLocalNode());
            System.out.println("    All nodes: " + topology.getNodes());
            System.out.println(" Remote nodes: " + topology.getRemoteNodes());
            System.out.println("   Join order: " + topology.getJoinOrder());
            System.out.println("  Oldest node: " + topology.getOldest());
            System.out.println("Youngest node: " + topology.getYoungest());
            // End:list_topology

            // Start:filter_topology
            // Immutable copy that contains only nodes with the specified role.
            ClusterTopology filtered = topology.filter(node -> node.hasRole("my_role"));
            // End:filter_topology

            assertNotNull(filtered);
        } finally {
            hekate.leave();
        }
    }
}