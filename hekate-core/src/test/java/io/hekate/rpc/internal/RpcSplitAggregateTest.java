package io.hekate.rpc.internal;

import io.hekate.core.internal.HekateTestNode;
import io.hekate.messaging.loadbalance.EmptyTopologyException;
import io.hekate.rpc.Rpc;
import io.hekate.rpc.RpcAggregate;
import io.hekate.rpc.RpcException;
import io.hekate.rpc.RpcSplit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.Test;

import static io.hekate.rpc.RpcAggregate.RemoteErrors.IGNORE;
import static io.hekate.rpc.RpcAggregate.RemoteErrors.WARN;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class RpcSplitAggregateTest extends RpcServiceTestBase {
    @Rpc
    public interface AggregateRpc {
        @RpcAggregate
        List<Object> list(@RpcSplit List<Object> arg);

        @RpcAggregate
        Set<Object> set(@RpcSplit Set<Object> arg);

        @RpcAggregate
        Map<Object, Object> map(@RpcSplit Map<Object, Object> arg);

        @RpcAggregate
        Collection<Object> collection(@RpcSplit Collection<Object> arg);

        @RpcAggregate(remoteErrors = IGNORE)
        Collection<Object> ignoreErrors(@RpcSplit List<Object> arg);

        @RpcAggregate(remoteErrors = WARN)
        Collection<Object> warnErrors(@RpcSplit List<Object> arg);

        @RpcAggregate
        Collection<Object> errors(@RpcSplit List<Object> arg);
    }

    private final AggregateRpc rpc1 = mock(AggregateRpc.class);

    private final AggregateRpc rpc2 = mock(AggregateRpc.class);

    private AggregateRpc client;

    private HekateTestNode server1;

    private HekateTestNode server2;

    public RpcSplitAggregateTest(MultiCodecTestContext ctx) {
        super(ctx);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        ClientAndServers ctx = prepareClientAndServers(rpc1, rpc2);

        server1 = ctx.servers().get(0);
        server2 = ctx.servers().get(1);

        client = ctx.client().rpc().clientFor(AggregateRpc.class)
            .withTimeout(3, TimeUnit.SECONDS)
            .build();
    }

    @Test
    public void testList() throws Exception {
        repeat(3, i -> {
            when(rpc1.list(singletonList(i))).thenReturn(asList(i, i, i));
            when(rpc2.list(singletonList(i))).thenReturn(asList(i, i, i));

            List<Object> result = client.list(asList(i, i));

            assertEquals(6, result.size());
            assertTrue(result.stream().allMatch(r -> (Integer)r == i));

            verify(rpc1).list(singletonList(i));
            verify(rpc2).list(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testLargerList() throws Exception {
        repeat(3, i -> {
            when(rpc1.list(or(eq(asList(i + 1, i + 3)), eq(asList(i + 2, i + 4))))).thenReturn(asList(i, i, i));
            when(rpc2.list(or(eq(asList(i + 1, i + 3)), eq(asList(i + 2, i + 4))))).thenReturn(asList(i, i, i));

            List<Object> result = client.list(asList(i + 1, i + 2, i + 3, i + 4));

            assertEquals(6, result.size());
            assertTrue(result.stream().allMatch(r -> (Integer)r == i));

            verify(rpc1).list(or(eq(asList(i + 1, i + 3)), eq(asList(i + 2, i + 4))));
            verify(rpc2).list(or(eq(asList(i + 1, i + 3)), eq(asList(i + 2, i + 4))));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testListPartialNull() throws Exception {
        repeat(3, i -> {
            when(rpc1.list(singletonList(i))).thenReturn(asList(i, i, i));
            when(rpc2.list(singletonList(i))).thenReturn(null);

            List<Object> result = client.list(asList(i, i));

            assertEquals(3, result.size());
            assertTrue(result.stream().allMatch(r -> (Integer)r == i));

            verify(rpc1).list(singletonList(i));
            verify(rpc2).list(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testSet() throws Exception {
        repeat(3, i -> {
            when(rpc1.set(or(eq(singleton(i + 1)), eq(singleton(i + 2))))).thenReturn(toSet("a" + i, "b" + i, "c" + i));
            when(rpc2.set(or(eq(singleton(i + 1)), eq(singleton(i + 2))))).thenReturn(toSet("d" + i, "e" + i, "f" + i));

            Set<Object> result = client.set(toSet(i + 1, i + 2));

            assertEquals(result, toSet("a" + i, "b" + i, "c" + i, "d" + i, "e" + i, "f" + i));

            verify(rpc1).set(or(eq(singleton(i + 1)), eq(singleton(i + 2))));
            verify(rpc2).set(or(eq(singleton(i + 1)), eq(singleton(i + 2))));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testLargerSet() throws Exception {
        repeat(3, i -> {
            when(rpc1.set(or(eq(toSet(i + 1, i + 3)), eq(toSet(i + 2, i + 4))))).thenReturn(toSet("a" + i));
            when(rpc2.set(or(eq(toSet(i + 1, i + 3)), eq(toSet(i + 2, i + 4))))).thenReturn(toSet("b" + i));

            Set<Object> result = client.set(toSet(i + 1, i + 2, i + 3, i + 4));

            assertEquals(result, toSet("a" + i, "b" + i));

            verify(rpc1).set(or(eq(toSet(i + 1, i + 3)), eq(toSet(i + 2, i + 4))));
            verify(rpc2).set(or(eq(toSet(i + 1, i + 3)), eq(toSet(i + 2, i + 4))));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testSetPartialNull() throws Exception {
        repeat(3, i -> {
            when(rpc1.set(or(eq(singleton(i + 1)), eq(singleton(i + 2))))).thenReturn(toSet("a" + i, "b" + i, "c" + i));
            when(rpc2.set(or(eq(singleton(i + 1)), eq(singleton(i + 2))))).thenReturn(null);

            Set<Object> result = client.set(toSet(i + 1, i + 2));

            assertEquals(result, toSet("a" + i, "b" + i, "c" + i));

            verify(rpc1).set(or(eq(singleton(i + 1)), eq(singleton(i + 2))));
            verify(rpc2).set(or(eq(singleton(i + 1)), eq(singleton(i + 2))));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testMap() throws Exception {
        repeat(3, i -> {
            Map<Object, Object> m1 = Stream.of("a" + i, "b" + i, "c" + i).collect(toMap(o -> o, o -> o + "test"));
            Map<Object, Object> m2 = Stream.of("d" + i, "e" + i, "f" + i).collect(toMap(o -> o, o -> o + "test"));

            when(rpc1.map(or(eq(singletonMap(i + 1, i)), eq(singletonMap(i + 2, i))))).thenReturn(m1);
            when(rpc2.map(or(eq(singletonMap(i + 1, i)), eq(singletonMap(i + 2, i))))).thenReturn(m2);

            Map<Object, Object> args = new HashMap<>();
            args.put(i + 1, i);
            args.put(i + 2, i);

            Map<Object, Object> result = client.map(args);

            Map<Object, Object> expected = new HashMap<>();
            expected.putAll(m1);
            expected.putAll(m2);

            assertEquals(expected, result);

            verify(rpc1).map(or(eq(singletonMap(i + 1, i)), eq(singletonMap(i + 2, i))));
            verify(rpc2).map(or(eq(singletonMap(i + 1, i)), eq(singletonMap(i + 2, i))));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testLargerMap() throws Exception {
        repeat(3, i -> {
            Map<Object, Object> m1 = Stream.of(i + 1, i + 3).collect(toMap(o -> o, o -> o + "test"));
            Map<Object, Object> m2 = Stream.of(i + 2, i + 4).collect(toMap(o -> o, o -> o + "test"));

            when(rpc1.map(or(eq(m1), eq(m2)))).thenReturn(m1);
            when(rpc2.map(or(eq(m1), eq(m2)))).thenReturn(m2);

            Map<Object, Object> args = new HashMap<>();
            args.putAll(m1);
            args.putAll(m2);

            Map<Object, Object> result = client.map(args);

            Map<Object, Object> expected = new HashMap<>();
            expected.putAll(m1);
            expected.putAll(m2);

            assertEquals(expected, result);

            verify(rpc1).map(or(eq(m1), eq(m2)));
            verify(rpc2).map(or(eq(m1), eq(m2)));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testMapPartialNull() throws Exception {
        repeat(3, i -> {
            Map<Object, Object> m1 = Stream.of("a" + i, "b" + i, "c" + i).collect(toMap(o -> o, o -> o + "test"));

            when(rpc1.map(or(eq(singletonMap(i + 1, i)), eq(singletonMap(i + 2, i))))).thenReturn(m1);
            when(rpc2.map(or(eq(singletonMap(i + 1, i)), eq(singletonMap(i + 2, i))))).thenReturn(null);

            Map<Object, Object> args = new HashMap<>();
            args.put(i + 1, i);
            args.put(i + 2, i);

            Map<Object, Object> result = client.map(args);

            assertEquals(m1, result);

            verify(rpc1).map(or(eq(singletonMap(i + 1, i)), eq(singletonMap(i + 2, i))));
            verify(rpc2).map(or(eq(singletonMap(i + 1, i)), eq(singletonMap(i + 2, i))));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testCollection() throws Exception {
        repeat(3, i -> {
            when(rpc1.collection(singletonList(i))).thenReturn(asList(i, i, i));
            when(rpc2.collection(singletonList(i))).thenReturn(toSet(i));

            Collection<Object> result = client.collection(asList(i, i));

            assertEquals(4, result.size());
            assertTrue(result.stream().allMatch(r -> (Integer)r == i));

            verify(rpc1).collection(singletonList(i));
            verify(rpc2).collection(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testIgnoreError() throws Exception {
        repeat(3, i -> {
            when(rpc1.ignoreErrors(singletonList(i))).thenThrow(TEST_ERROR);
            when(rpc2.ignoreErrors(singletonList(i))).thenThrow(TEST_ERROR);

            Collection<Object> result = client.ignoreErrors(asList(i, i));

            assertEquals(0, result.size());

            verify(rpc1).ignoreErrors(singletonList(i));
            verify(rpc2).ignoreErrors(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testIgnorePartialError() throws Exception {
        repeat(3, i -> {
            when(rpc1.ignoreErrors(singletonList(i))).thenReturn(asList(i, i, i));
            when(rpc2.ignoreErrors(singletonList(i))).thenThrow(TEST_ERROR);

            Collection<Object> result = client.ignoreErrors(asList(i, i));

            assertEquals(3, result.size());
            assertTrue(result.stream().allMatch(r -> (Integer)r == i));

            verify(rpc1).ignoreErrors(singletonList(i));
            verify(rpc2).ignoreErrors(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testWarnPartialError() throws Exception {
        repeat(3, i -> {
            when(rpc1.warnErrors(singletonList(i))).thenReturn(asList(i, i, i));
            when(rpc2.warnErrors(singletonList(i))).thenThrow(TEST_ERROR);

            Collection<Object> result = client.warnErrors(asList(i, i));

            assertEquals(3, result.size());
            assertTrue(result.stream().allMatch(r -> (Integer)r == i));

            verify(rpc1).warnErrors(singletonList(i));
            verify(rpc2).warnErrors(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testError() throws Exception {
        repeat(3, i -> {
            when(rpc1.errors(singletonList(i))).thenThrow(TEST_ERROR);
            when(rpc2.errors(singletonList(i))).thenThrow(TEST_ERROR);

            expect(RpcException.class, () -> client.errors(asList(i, i)));

            verify(rpc1).errors(singletonList(i));
            verify(rpc2).errors(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testPartialError() throws Exception {
        repeat(3, i -> {
            when(rpc1.errors(singletonList(i))).thenReturn(asList(i, i, i));
            when(rpc2.errors(singletonList(i))).thenThrow(TEST_ERROR);

            expect(RpcException.class, () -> client.errors(asList(i, i)));

            verify(rpc1).errors(singletonList(i));
            verify(rpc2).errors(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testRecursiveResult() throws Exception {
        AggregateRpc nestedRpc1 = server1.rpc().clientFor(AggregateRpc.class)
            .withTimeout(1, TimeUnit.SECONDS)
            .forRemotes()
            .build();

        repeat(3, i -> {
            when(rpc1.list(singletonList(i))).thenAnswer(invocation -> nestedRpc1.list(singletonList(i)));
            when(rpc2.list(singletonList(i))).thenReturn(Arrays.asList(i, i, i));

            List<Object> result = client.list(asList(i, i));

            assertEquals(6, result.size());
            assertTrue(result.stream().allMatch(r -> (Integer)r == i));

            verify(rpc1).list(singletonList(i));
            verify(rpc2, times(2)).list(singletonList(i));

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testEmptyTopology() throws Exception {
        server1.leave();
        server2.leave();

        RpcException err = expect(RpcException.class, () -> client.list(singletonList(1)));

        assertTrue(err.isCausedBy(EmptyTopologyException.class));
    }
}
