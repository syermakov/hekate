package io.hekate.rpc.internal;

import io.hekate.codec.CodecException;
import io.hekate.core.internal.HekateTestNode;
import io.hekate.core.internal.util.ErrorUtils;
import io.hekate.messaging.MessagingException;
import io.hekate.messaging.MessagingRemoteException;
import io.hekate.rpc.Rpc;
import io.hekate.rpc.RpcAffinityKey;
import io.hekate.rpc.RpcException;
import io.hekate.rpc.RpcServerConfig;
import io.hekate.rpc.RpcServiceFactory;
import java.io.InvalidObjectException;
import java.io.NotSerializableException;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class RpcServiceUnicastTest extends RpcServiceTestBase {
    @Rpc
    public interface TestRpcA {
        void callA();
    }

    @Rpc
    public interface TestRpcB extends TestRpcA {
        Object callB();
    }

    @Rpc
    public interface TestRpcC extends TestRpcA {
        Object callC(Object arg1, Object arg2);
    }

    @Rpc
    public interface TestRpcD extends TestRpcB, TestRpcC {
        Object callD();
    }

    @Rpc(version = 3)
    public interface TestAffinityRpc extends TestRpcB, TestRpcC {
        Object call(@RpcAffinityKey Object arg);
    }

    @Rpc
    public interface TestRpcWithDefaultMethod {
        Object call();

        default Object callDefault() {
            return call();
        }
    }

    @Rpc
    public interface TestAsyncRpc {
        CompletableFuture<Object> call();
    }

    @Rpc
    public interface TestRpcWithError {
        Object callWithError() throws TestRpcException;
    }

    public static class TestRpcException extends Exception {
        private static final long serialVersionUID = 1;

        public TestRpcException(String message) {
            super(message);
        }
    }

    @Test
    public void testVoid() throws Exception {
        TestRpcA rpc = mock(TestRpcA.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcA proxy = client.rpc().clientFor(TestRpcA.class).build();

        repeat(3, i -> {
            proxy.callA();

            verify(rpc).callA();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testNoArg() throws Exception {
        TestRpcB rpc = mock(TestRpcB.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcB proxy = client.rpc().clientFor(TestRpcB.class).build();

        repeat(3, i -> {
            when(rpc.callB()).thenReturn("result" + i);

            assertEquals("result" + i, proxy.callB());

            verify(rpc).callB();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testArgs() throws Exception {
        TestRpcC rpc = mock(TestRpcC.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcC proxy = client.rpc().clientFor(TestRpcC.class).build();

        repeat(3, i -> {
            when(rpc.callC(i, -i)).thenReturn("result" + i);

            assertEquals("result" + i, proxy.callC(i, -i));

            verify(rpc).callC(i, -i);
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testAffinityKey() throws Exception {
        TestAffinityRpc rpc1 = mock(TestAffinityRpc.class);
        TestAffinityRpc rpc2 = mock(TestAffinityRpc.class);

        ClientAndServers testCtx = prepareClientAndServers(rpc1, rpc2);

        HekateTestNode server1 = testCtx.servers().get(0);
        HekateTestNode server2 = testCtx.servers().get(1);

        TestAffinityRpc proxy = testCtx.client().rpc().clientFor(TestAffinityRpc.class)
            .withLoadBalancer((call, ctx) -> {
                    assertSame(TestAffinityRpc.class, call.rpcInterface());
                    assertEquals(3, call.rpcVersion());
                    assertEquals("call", call.method().getName());
                    assertTrue(call.hasArgs());
                    assertEquals(1, call.args().length);

                    if (ctx.affinityKey().equals("1")) {
                        return server1.cluster().localNode().id();
                    } else if (ctx.affinityKey().equals("2")) {
                        return server2.cluster().localNode().id();
                    } else {
                        throw new AssertionError("Unexpected affinity key: " + ctx.affinityKey());
                    }
                }
            )
            .build();

        repeat(5, i -> {
            if (i % 2 == 0) {
                proxy.call("1");

                verify(rpc1).call("1");
            } else {
                proxy.call("2");

                verify(rpc2).call("2");
            }

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testTag() throws Exception {
        TestAffinityRpc rpc1 = mock(TestAffinityRpc.class);
        TestAffinityRpc rpc2 = mock(TestAffinityRpc.class);

        HekateTestNode server1 = createNode(c ->
            c.withService(RpcServiceFactory.class, f -> {
                f.withServer(new RpcServerConfig()
                    .withTag("test1")
                    .withHandler(rpc1)
                );
            })
        ).join();

        HekateTestNode server2 = createNode(c ->
            c.withService(RpcServiceFactory.class, f -> {
                f.withServer(new RpcServerConfig()
                    .withTag("test2")
                    .withHandler(rpc2)
                );
            })
        ).join();

        HekateTestNode client = createNode().join();

        awaitForTopology(client, server1, server2);

        repeat(5, i -> {
            if (i % 2 == 0) {
                TestAffinityRpc proxy = client.rpc().clientFor(TestAffinityRpc.class, "test1").build();

                proxy.call("1");

                verify(rpc1).call("1");
            } else {
                TestAffinityRpc proxy = client.rpc().clientFor(TestAffinityRpc.class, "test2").build();

                proxy.call("2");

                verify(rpc2).call("2");
            }

            verifyNoMoreInteractions(rpc1, rpc2);
            reset(rpc1, rpc2);
        });
    }

    @Test
    public void testDefaultMethod() throws Exception {
        AtomicReference<Object> resultRef = new AtomicReference<>();

        TestRpcWithDefaultMethod rpc = resultRef::get;

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcWithDefaultMethod proxy = client.rpc().clientFor(TestRpcWithDefaultMethod.class).build();

        repeat(3, i -> {
            resultRef.set("result" + i);

            assertEquals("result" + i, proxy.callDefault());
        });
    }

    @Test
    public void testInheritedMethods() throws Exception {
        TestRpcD rpc = mock(TestRpcD.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcD proxy = client.rpc().clientFor(TestRpcD.class).build();

        repeat(3, i -> {
            when(rpc.callB()).thenReturn("result" + i);
            when(rpc.callC(i, -i)).thenReturn("result" + i);
            when(rpc.callD()).thenReturn("result" + -i);

            proxy.callA();

            assertEquals("result" + i, proxy.callC(i, -i));
            assertEquals("result" + i, proxy.callB());
            assertEquals("result" + -i, proxy.callD());

            verify(rpc).callA();
            verify(rpc).callB();
            verify(rpc).callC(i, -i);
            verify(rpc).callD();
            verifyNoMoreInteractions(rpc);

            reset(rpc);
        });
    }

    @Test
    public void testUncheckedException() throws Exception {
        TestRpcWithError rpc = mock(TestRpcWithError.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcWithError proxy = client.rpc().clientFor(TestRpcWithError.class).build();

        repeat(3, i -> {
            when(rpc.callWithError()).thenThrow(new RuntimeException(TEST_ERROR_MESSAGE));

            expectExactMessage(RuntimeException.class, TEST_ERROR_MESSAGE, proxy::callWithError);

            verify(rpc).callWithError();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testError() throws Exception {
        TestRpcWithError rpc = mock(TestRpcWithError.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcWithError proxy = client.rpc().clientFor(TestRpcWithError.class).build();

        repeat(3, i -> {
            when(rpc.callWithError()).thenThrow(new NoClassDefFoundError(TEST_ERROR_MESSAGE));

            expectExactMessage(NoClassDefFoundError.class, TEST_ERROR_MESSAGE, proxy::callWithError);

            verify(rpc).callWithError();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testCheckedException() throws Exception {
        TestRpcWithError rpc = mock(TestRpcWithError.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcWithError proxy = client.rpc().clientFor(TestRpcWithError.class).build();

        repeat(3, i -> {
            when(rpc.callWithError()).thenThrow(new TestRpcException(TEST_ERROR_MESSAGE));

            expectExactMessage(TestRpcException.class, TEST_ERROR_MESSAGE, proxy::callWithError);

            verify(rpc).callWithError();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testNonSerializableException() throws Exception {
        TestRpcWithError rpc = mock(TestRpcWithError.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcWithError proxy = client.rpc().clientFor(TestRpcWithError.class).build();

        repeat(3, i -> {
            NonSerializableRpcException testError = new NonSerializableRpcException(true);

            when(rpc.callWithError()).thenThrow(testError);

            RpcException err = expect(RpcException.class, proxy::callWithError);

            assertTrue(ErrorUtils.isCausedBy(MessagingRemoteException.class, err));
            assertTrue(ErrorUtils.stackTrace(err).contains(NonSerializableRpcException.class.getName() + ": " + TEST_ERROR_MESSAGE));

            verify(rpc).callWithError();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testNonDeserializableException() throws Exception {
        TestRpcWithError rpc = mock(TestRpcWithError.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcWithError proxy = client.rpc().clientFor(TestRpcWithError.class).build();

        repeat(3, i -> {
            NonSerializableRpcException testError = new NonSerializableRpcException(false);

            when(rpc.callWithError()).thenThrow(testError);

            RpcException err = expect(RpcException.class, proxy::callWithError);

            assertTrue(ErrorUtils.isCausedBy(CodecException.class, err));
            assertTrue(ErrorUtils.stackTrace(err).contains(InvalidObjectException.class.getName() + ": " + TEST_ERROR_MESSAGE));

            verify(rpc).callWithError();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testNonSerializableArg() throws Exception {
        TestRpcC rpc = mock(TestRpcC.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcC proxy = client.rpc().clientFor(TestRpcC.class).build();

        repeat(3, i -> {
            RpcException err = expect(RpcException.class, () -> proxy.callC(1, new Socket()));

            assertTrue(ErrorUtils.isCausedBy(CodecException.class, err));
            assertTrue(ErrorUtils.stackTrace(err).contains(NotSerializableException.class.getName() + ": " + Socket.class.getName()));

            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testNonSerializableResult() throws Exception {
        TestRpcB rpc = mock(TestRpcB.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestRpcB proxy = client.rpc().clientFor(TestRpcB.class).build();

        repeat(3, i -> {
            when(rpc.callB()).thenReturn(new Socket());

            RpcException err = expect(RpcException.class, proxy::callB);

            assertTrue(ErrorUtils.isCausedBy(MessagingRemoteException.class, err));
            assertTrue(ErrorUtils.stackTrace(err).contains(NotSerializableException.class.getName() + ": " + Socket.class.getName()));

            verify(rpc).callB();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testAsyncNonNullResult() throws Exception {
        TestAsyncRpc rpc = mock(TestAsyncRpc.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestAsyncRpc proxy = client.rpc().clientFor(TestAsyncRpc.class).build();

        repeat(3, i -> {
            CompletableFuture<Object> resultFuture = new CompletableFuture<>();

            when(rpc.call()).thenReturn(resultFuture);

            CompletableFuture<Object> callFuture = proxy.call();

            assertFalse(callFuture.isDone());

            resultFuture.complete("OK" + i);

            assertEquals("OK" + i, get(callFuture));

            verify(rpc).call();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testAsyncNullResult() throws Exception {
        TestAsyncRpc rpc = mock(TestAsyncRpc.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestAsyncRpc proxy = client.rpc().clientFor(TestAsyncRpc.class).build();

        repeat(3, i -> {
            CompletableFuture<Object> resultFuture = new CompletableFuture<>();

            when(rpc.call()).thenReturn(resultFuture);

            CompletableFuture<Object> callFuture = proxy.call();

            assertFalse(callFuture.isDone());

            resultFuture.complete(null);

            assertNull(get(callFuture));

            verify(rpc).call();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testAsyncException() throws Exception {
        TestAsyncRpc rpc = mock(TestAsyncRpc.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestAsyncRpc proxy = client.rpc().clientFor(TestAsyncRpc.class).build();

        repeat(3, i -> {
            CompletableFuture<Object> resultFuture = new CompletableFuture<>();

            when(rpc.call()).thenReturn(resultFuture);

            CompletableFuture<Object> callFuture = proxy.call();

            assertFalse(callFuture.isDone());

            resultFuture.completeExceptionally(TEST_ERROR);

            ExecutionException err = expect(ExecutionException.class, () -> get(callFuture));

            assertSame(ErrorUtils.stackTrace(err), TEST_ERROR.getClass(), err.getCause().getClass());

            verify(rpc).call();
            verifyNoMoreInteractions(rpc);
            reset(rpc);
        });
    }

    @Test
    public void testAsyncResultAfterClientLeave() throws Exception {
        TestAsyncRpc rpc = mock(TestAsyncRpc.class);

        HekateTestNode client = prepareClientAndServer(rpc).client();

        TestAsyncRpc proxy = client.rpc().clientFor(TestAsyncRpc.class).build();

        when(rpc.call()).thenReturn(new CompletableFuture<>());

        // Send RPC request.
        CompletableFuture<Object> callFuture = proxy.call();

        assertFalse(callFuture.isDone());

        // Stop the client node.
        client.leave();

        assertTrue(callFuture.isDone());
        assertTrue(callFuture.isCompletedExceptionally());

        ExecutionException err = expect(ExecutionException.class, () -> get(callFuture));

        assertSame(MessagingException.class, err.getCause().getClass());

        verify(rpc).call();
        verifyNoMoreInteractions(rpc);
        reset(rpc);
    }

    @Test
    public void testAsyncResultAfterServerLeave() throws Exception {
        TestAsyncRpc rpc = mock(TestAsyncRpc.class);

        ClientAndServer ctx = prepareClientAndServer(rpc);

        TestAsyncRpc proxy = ctx.client().rpc().clientFor(TestAsyncRpc.class).build();

        CountDownLatch serverCall = new CountDownLatch(1);

        CompletableFuture<Object> resultFuture = new CompletableFuture<>();

        when(rpc.call()).then(call -> {
            // Notify on server RPC request.
            serverCall.countDown();

            return resultFuture;
        });

        // Send RPC request.
        CompletableFuture<Object> callFuture = proxy.call();

        // Await for server to receive RPC request.
        await(serverCall);

        assertFalse(callFuture.isDone());

        // Stop the server node.
        ctx.server().leave();

        awaitForTopology(ctx.client());

        assertTrue(callFuture.isDone());
        assertTrue(callFuture.isCompletedExceptionally());

        ExecutionException err = expect(ExecutionException.class, () -> get(callFuture));

        assertSame(MessagingException.class, err.getCause().getClass());

        verify(rpc).call();
        verifyNoMoreInteractions(rpc);
        reset(rpc);
    }
}
