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

package io.hekate.coordinate.internal;

import io.hekate.HekateInstanceTestBase;
import io.hekate.coordinate.CoordinationContext;
import io.hekate.coordinate.CoordinationHandler;
import io.hekate.coordinate.CoordinationProcess;
import io.hekate.coordinate.CoordinationProcessConfig;
import io.hekate.coordinate.CoordinationService;
import io.hekate.coordinate.CoordinationServiceFactory;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class CoordinationServiceSingleNodeTest extends HekateInstanceTestBase {
    @Test
    public void testEmptyProcesses() throws Exception {
        CoordinationService coordination = createInstance(boot ->
            boot.withService(new CoordinationServiceFactory())
        ).join().get(CoordinationService.class);

        assertTrue(coordination.getProcesses().isEmpty());

        assertFalse(coordination.has("no-such-process"));

        expect(IllegalArgumentException.class, () -> coordination.get("no-such-process"));
    }

    @Test
    public void testMultipleProcesses() throws Exception {
        CoordinationHandler handler = mock(CoordinationHandler.class);

        doAnswer(invocation -> {
            ((CoordinationContext)invocation.getArguments()[0]).complete();

            return null;
        }).when(handler).coordinate(any());

        CoordinationService coordination = createInstance(boot ->
            boot.withService(new CoordinationServiceFactory()
                .withProcess(new CoordinationProcessConfig("process1").withHandler(handler))
                .withProcess(new CoordinationProcessConfig("process2").withHandler(handler))
            )
        ).join().get(CoordinationService.class);

        assertTrue(coordination.has("process1"));
        assertTrue(coordination.has("process2"));

        CoordinationProcess process1 = coordination.get("process1");
        CoordinationProcess process2 = coordination.get("process1");

        assertNotNull(process1);
        assertNotNull(process2);

        assertEquals(2, coordination.getProcesses().size());
        assertTrue(coordination.getProcesses().contains(process1));
        assertTrue(coordination.getProcesses().contains(process1));

        process1.getFuture().get(3, TimeUnit.SECONDS);
        process2.getFuture().get(3, TimeUnit.SECONDS);
    }
}