package io.hekate.rpc;

import io.hekate.HekateTestBase;
import io.hekate.failover.FailoverPolicy;
import io.hekate.util.format.ToString;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class RpcClientConfigTest extends HekateTestBase {
    public interface TestRpc {
        // No-op.
    }

    private final RpcClientConfig cfg = new RpcClientConfig();

    @Test
    public void testRpcInterface() {
        assertNull(cfg.getRpcInterface());

        cfg.setRpcInterface(TestRpc.class);

        assertSame(TestRpc.class, cfg.getRpcInterface());

        cfg.setRpcInterface(null);

        assertNull(cfg.getRpcInterface());

        assertSame(cfg, cfg.withRpcInterface(TestRpc.class));
        assertSame(TestRpc.class, cfg.getRpcInterface());
    }

    @Test
    public void testTag() {
        assertNull(cfg.getTag());

        cfg.setTag("test");

        assertEquals("test", cfg.getTag());

        cfg.setTag(null);

        assertNull(cfg.getTag());

        assertSame(cfg, cfg.withTag("test"));
        assertEquals("test", cfg.getTag());
    }

    @Test
    public void testFailover() {
        assertNull(cfg.getFailover());

        FailoverPolicy policy = ctx -> null;

        cfg.setFailover(policy);

        assertSame(policy, cfg.getFailover());

        cfg.setFailover(null);

        assertNull(cfg.getFailover());

        assertSame(cfg, cfg.withFailover(policy));
        assertSame(policy, cfg.getFailover());
    }

    @Test
    public void testLoadBalancer() {
        assertNull(cfg.getLoadBalancer());

        RpcLoadBalancer lb = (message, ctx) -> null;

        cfg.setLoadBalancer(lb);

        assertSame(lb, cfg.getLoadBalancer());

        cfg.setLoadBalancer(null);

        assertNull(cfg.getLoadBalancer());

        assertSame(cfg, cfg.withLoadBalancer(lb));
        assertSame(lb, cfg.getLoadBalancer());
    }

    @Test
    public void testTimeout() {
        assertEquals(0, cfg.getTimeout());

        cfg.setTimeout(10001);

        assertEquals(10001, cfg.getTimeout());

        assertSame(cfg, cfg.withTimeout(10002));

        assertEquals(10002, cfg.getTimeout());
    }

    @Test
    public void testToString() {
        assertEquals(ToString.format(cfg), cfg.toString());
    }
}