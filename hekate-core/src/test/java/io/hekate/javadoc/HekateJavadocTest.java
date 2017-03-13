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

package io.hekate.javadoc;

import io.hekate.HekateTestBase;
import io.hekate.cluster.ClusterService;
import io.hekate.cluster.ClusterServiceFactory;
import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.messaging.MessagingService;
import io.hekate.messaging.MessagingServiceFactory;
import io.hekate.network.NetworkServiceFactory;
import io.hekate.task.TaskService;
import io.hekate.task.TaskServiceFactory;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class HekateJavadocTest extends HekateTestBase {
    @Test
    public void exampleBootstrap() throws Exception {
        // Start:bootstrap
        // Start new instance with all configuration parameters set to their default values.
        Hekate hekate = new HekateBootstrap()
            .withNodeName("my-node")
            .withClusterName("my-cluster")
            .join();
        // End:bootstrap

        // Start:sync_leave
        hekate.leaveAsync().get();
        // End:sync_leave

        // Start:sync_join
        hekate.joinAsync().get();
        // End:sync_join

        // Start:sync_terminate
        hekate.terminateAsync().get();
        // End:sync_terminate
    }

    @Test
    public void exampleAccessService() throws Exception {
        // Start:get_service
        // Configure and start new node.
        Hekate hekate = new HekateBootstrap()
            // Configure services that should be available on this node.
            .withService(NetworkServiceFactory.class, net -> {
                net.setPort(10012); // Network port of this node.
                net.setPortRange(100); // Auto-increment range if port is busy.
            })
            .withService(ClusterServiceFactory.class, cluster -> {
                cluster.setGossipInterval(1000); // Gossip once per second.
                cluster.setSpeedUpGossipSize(100); // Speed up convergence if cluster is <= 100 nodes.
            })
            .withService(new MessagingServiceFactory())
            .withService(new TaskServiceFactory())
            // ...some other services...
            .join();

        // Get cluster service.
        ClusterService cluster = hekate.get(ClusterService.class);

        // Get messaging service.
        MessagingService messaging = hekate.get(MessagingService.class);

        // Get task service.
        TaskService tasks = hekate.get(TaskService.class);

        // ...etc...
        // End:get_service

        assertNotNull(cluster);
        assertNotNull(messaging);
        assertNotNull(tasks);

        hekate.leave();
    }
}