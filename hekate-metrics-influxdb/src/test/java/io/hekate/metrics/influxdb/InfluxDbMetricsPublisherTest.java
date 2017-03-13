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

package io.hekate.metrics.influxdb;

import io.hekate.core.internal.util.Waiting;
import io.hekate.metrics.Metric;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.influxdb.InfluxDB;
import org.influxdb.dto.BatchPoints;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class InfluxDbMetricsPublisherTest extends InfluxDbMetricsTestBase {
    private interface WriteDelegate {
        void write(InfluxDB db, BatchPoints points, Runnable original);
    }

    private static class TestMetric implements Metric {
        private final String name;

        private final long value;

        public TestMetric(String name, long value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getValue() {
            return value;
        }
    }

    private InfluxDbMetricsPublisher publisher;

    private WriteDelegate writeDelegate;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        InfluxDbMetricsConfig cfg = new InfluxDbMetricsConfig();

        cfg.setUrl(url);
        cfg.setDatabase(database);
        cfg.setUser(user);
        cfg.setPassword(password);

        publisher = new InfluxDbMetricsPublisher(cfg) {
            @Override
            void doWrite(InfluxDB db, BatchPoints points) {
                if (writeDelegate == null) {
                    super.doWrite(db, points);
                } else {
                    writeDelegate.write(db, points, () ->
                        super.doWrite(db, points)
                    );
                }
            }
        };
    }

    @After
    @Override
    public void tearDown() throws Exception {
        try {
            if (publisher != null) {
                publisher.stop();
            }
        } finally {
            super.tearDown();
        }
    }

    @Test
    public void testStartStop() throws Exception {
        repeat(10, i -> {
            assertTrue(publisher.publish(single("no-way-before-start", i)).isCancelled());

            publisher.start("test" + i, "test-host" + i, 10002);

            publisher.publish(single("test", i)).get(3, TimeUnit.SECONDS);

            publisher.stop();

            assertTrue(publisher.publish(single("no-way-after-stop", i)).isCancelled());
        });
    }

    @Test
    public void testPublishSingle() throws Exception {
        publisher.start("test", "test-host", 10002);

        repeat(3, i -> {
            publisher.publish(single("test.metric", 100 + i)).get(3, TimeUnit.SECONDS);

            assertLatestValue("test.metric", 100 + i);

            publisher.publish(single("test.metric with   spaces", 100 + i)).get(3, TimeUnit.SECONDS);

            assertLatestValue("test.metric_with___spaces", 100 + i);

            publisher.publish(single(" test ^ &&$metric with  _,@@signs ", 100 + i)).get(3, TimeUnit.SECONDS);

            assertLatestValue("test______metric_with______signs", 100 + i);
        });

        publisher.stop();
    }

    @Test
    public void testPublishBulk() throws Exception {
        publisher.start("test", "test-host", 10002);

        repeat(3, i -> {
            publisher.publish(Arrays.asList(
                metric("test.metric1", 100 + i),
                metric("test.metric2", 1000 + i),
                metric("test.metric3", 10000 + i),
                metric("test.metric4", 100000 + i)
            )).get(3, TimeUnit.SECONDS);

            assertLatestValue("test.metric1", 100 + i);
            assertLatestValue("test.metric2", 1000 + i);
            assertLatestValue("test.metric3", 10000 + i);
            assertLatestValue("test.metric4", 100000 + i);
        });

        publisher.stop();
    }

    @Test
    public void testQueue() throws Exception {
        int maxQueueSize = InfluxDbMetricsConfig.DEFAULT_QUEUE_SIZE;

        CountDownLatch resume = new CountDownLatch(1);

        writeDelegate = (db, points, original) -> {
            await(resume);

            original.run();
        };

        publisher.start("test", "test-host", 10002);

        List<Future<?>> okFuture = new ArrayList<>();
        List<Future<?>> failFuture = new ArrayList<>();

        for (int i = 0; i < maxQueueSize * 2; i++) {
            CompletableFuture<Void> future = publisher.publish(single("test.metric", i));

            if (i == 0) {
                busyWait("empty queue", () ->
                    publisher.getQueueSize() == 0
                );
            }

            if (i <= maxQueueSize) {
                okFuture.add(future);
            } else {
                failFuture.add(future);
            }
        }

        assertEquals(maxQueueSize, publisher.getQueueSize());

        resume.countDown();

        busyWait("empty queue", () ->
            publisher.getQueueSize() == 0
        );

        publisher.stop();

        for (Future<?> future : okFuture) {
            future.get(3, TimeUnit.MILLISECONDS);
        }

        for (Future<?> future : failFuture) {
            assertTrue(future.isCancelled());
        }

        assertEquals("Success size: " + okFuture.size(), okFuture.size(), maxQueueSize + 1);
    }

    @Test
    public void testStopWithFullQueue() throws Exception {
        int maxQueueSize = InfluxDbMetricsConfig.DEFAULT_QUEUE_SIZE;

        CountDownLatch resume = new CountDownLatch(1);

        writeDelegate = (db, points, original) -> {
            await(resume);

            original.run();
        };

        publisher.start("test", "test-host", 10002);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < maxQueueSize * 2; i++) {
            CompletableFuture<Void> future = publisher.publish(single("test.metric", i));

            // Ignore the first one since it could be consumed or not consumed by the publisher thread.
            if (i > 0) {
                futures.add(future);
            }
        }

        assertEquals(maxQueueSize, publisher.getQueueSize());

        Waiting stopped = publisher.stopAsync();

        resume.countDown();

        stopped.await();

        for (Future<?> future : futures) {
            assertTrue(future.isCancelled());
        }
    }

    @Test
    public void testStopOnError() throws Exception {
        writeDelegate = (db, points, original) -> {
            throw TEST_ERROR;
        };

        publisher.start("test", "test-host", 10002);

        try {
            publisher.publish(single("test.metric", 10)).get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertSame(TEST_ERROR, e.getCause());
        }

        assertTrue(publisher.isStopped());
    }

    @Test
    public void testErrorRecover() throws Exception {
        publisher.start("test", "test-host", 10002);

        publisher.publish(single("test", 1)).get(3, TimeUnit.SECONDS);

        assertLatestValue("test", 1);

        influxDb.deleteDatabase(database);

        expect(ExecutionException.class, "database not found: \\\"" + database + "\\\"", () ->
            publisher.publish(single("test", 2)).get(3, TimeUnit.SECONDS)
        );

        influxDb.createDatabase(database);

        publisher.publish(single("test", 3)).get(3, TimeUnit.SECONDS);
    }

    @Test
    public void testToString() {
        assertTrue(publisher.toString(), publisher.toString().startsWith("[url="));
    }

    private void assertLatestValue(String metric, long expected) {
        long real = getLatestValue(metric);

        assertEquals(expected, real);
    }

    private static Collection<Metric> single(String name, long value) {
        return Collections.singleton(metric(name, value));
    }

    private static TestMetric metric(String name, long value) {
        return new TestMetric(name, value);
    }
}