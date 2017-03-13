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

package io.hekate.microbench;

import io.hekate.codec.kryo.KryoCodecFactory;
import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.metrics.MetricsServiceFactory;
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public abstract class MultiNodeBenchmarkContext {
    private final int nodesCount;

    private List<Hekate> nodes;

    public MultiNodeBenchmarkContext(int nodesCount) {
        this.nodesCount = nodesCount;
    }

    protected abstract void configure(int index, HekateBootstrap boot) throws Exception;

    protected abstract void initialize(List<Hekate> nodes) throws Exception;

    @Setup
    public void setUp() throws Exception {
        nodes = new ArrayList<>(nodesCount);

        for (int i = 0; i < nodesCount; i++) {
            HekateBootstrap boot = new HekateBootstrap()
                .withNodeName("node" + i)
                .withDefaultCodec(new KryoCodecFactory<>())
                .withService(new MetricsServiceFactory());

            configure(i, boot);

            Hekate node = boot.join();

            nodes.add(node);
        }

        initialize(nodes);
    }

    public List<Hekate> getNodes() {
        return nodes;
    }

    @TearDown
    public void tearDown() {
        nodes.forEach(n -> n.leaveAsync().join());
    }
}