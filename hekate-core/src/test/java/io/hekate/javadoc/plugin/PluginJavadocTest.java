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

package io.hekate.javadoc.plugin;

import io.hekate.HekateTestBase;
import io.hekate.HekateTestProps;
import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterService;
import io.hekate.cluster.event.ClusterEvent;
import io.hekate.cluster.event.ClusterEventType;
import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.core.HekateException;
import io.hekate.core.plugin.Plugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PluginJavadocTest extends HekateTestBase {
    // Start:plugin
    public static class ClusterInfoPlugin implements Plugin {
        private Path file;

        @Override
        public void install(HekateBootstrap boot) {
            // Nothing to configure.
        }

        @Override
        public void start(Hekate hekate) throws HekateException {
            // Prepare file
            file = Paths.get(hekate.getNode().getName() + "-cluster.txt");

            // Register cluster event listener that will update file on join/change cluster events.
            hekate.get(ClusterService.class).addListener(this::updateFile, ClusterEventType.JOIN, ClusterEventType.CHANGE);
        }

        @Override
        public void stop() throws HekateException {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new HekateException("Failed to delete " + file, e);
            }
        }

        private void updateFile(ClusterEvent event) throws HekateException {
            List<String> nodesInfo = event.getTopology().stream().map(ClusterNode::toString).collect(Collectors.toList());

            try {
                Files.write(file, nodesInfo);
            } catch (IOException e) {
                throw new HekateException("Failed to update " + file, e);
            }
        }
    }
    // End:plugin

    @BeforeClass
    public static void mayBeDisableTest() {
        Assume.assumeTrue(Boolean.valueOf(HekateTestProps.get("MULTICAST_ENABLED")));
    }

    @Test
    public void test() throws Exception {
        // Start:register
        Hekate node = new HekateBootstrap()
            .withNodeName("example-node")
            .withPlugin(new ClusterInfoPlugin())
            .join();
        // End:register

        verifyFileContents(node);

        Hekate n2 = new HekateBootstrap().join();

        node.get(ClusterService.class).futureOf(topology -> topology.size() == 2).get(3, TimeUnit.SECONDS);

        verifyFileContents(node);

        n2.leave();

        node.get(ClusterService.class).futureOf(topology -> topology.size() == 1).get(3, TimeUnit.SECONDS);

        verifyFileContents(node);

        node.leave();
    }

    private void verifyFileContents(Hekate node) throws IOException {
        List<String> fileNodes = Files.readAllLines(Paths.get(node.getNode().getName() + "-cluster.txt"));

        say("File contents: " + fileNodes);

        List<String> nodes = node.get(ClusterService.class).getTopology().stream().map(ClusterNode::toString).collect(Collectors.toList());

        assertEquals(nodes.size(), fileNodes.size());

        for (int i = 0; i < nodes.size(); i++) {
            assertEquals(nodes.get(i), fileNodes.get(i));
        }
    }
}