package io.hekate.rpc;

import io.hekate.failover.FailoverPolicy;
import io.hekate.util.format.ToString;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * RPC client configuration.
 *
 * <p>
 * This class provides support for pre-configuring some of the {@link RpcClientBuilder}'s options. When {@link RpcClientBuilder} is
 * requested from the {@link RpcService} it first searches for an existing configuration by using the following rules:
 * </p>
 *
 * <ul>
 * <li>if {@link RpcService#clientFor(Class)} method is used then it will search for an instance of {@link RpcClientConfig} that has the
 * same {@link #setRpcInterface(Class) RPC interface} and a {@code null} {@link #setTag(String) tag}</li>
 * <li>if {@link RpcService#clientFor(Class, String)} method is used then it will search for an instance of {@link RpcClientConfig} that
 * has the same {@link #setRpcInterface(Class) RPC interface} and the same {@link #setTag(String) tag} value</li>
 * </ul>
 *
 * <p>
 * If such {@link RpcClientConfig} instance is found then its options will be applied to the {@link RpcClientBuilder}.
 * </p>
 *
 * <p>
 * For more details about the Remote Procedure Call API and its capabilities please see the documentation of the {@link RpcService}
 * interface.
 * </p>
 *
 * @see RpcService#clientFor(Class)
 * @see RpcService#clientFor(Class, String)
 */
public class RpcClientConfig {
    private Class<?> rpcInterface;

    private String tag;

    private FailoverPolicy failover;

    private RpcLoadBalancer loadBalancer;

    private long timeout;

    /**
     * Returns the RPC interface (see {@link #setRpcInterface(Class)}).
     *
     * @return RPC interface.
     */
    public Class<?> getRpcInterface() {
        return rpcInterface;
    }

    /**
     * Sets the RPC interface.
     *
     * <p>
     * The specified interface must be public and must be annotated with {@link Rpc}.
     * </p>
     *
     * @param rpcInterface RPC interface.
     */
    public void setRpcInterface(Class<?> rpcInterface) {
        this.rpcInterface = rpcInterface;
    }

    /**
     * Fluent-style version of {@link #setRpcInterface(Class)}.
     *
     * @param rpcInterface RPC interface.
     *
     * @return This instance.
     */
    public RpcClientConfig withRpcInterface(Class<?> rpcInterface) {
        setRpcInterface(rpcInterface);

        return this;
    }

    /**
     * Returns the RPC interface tag (see {@link #setTag(String)}).
     *
     * @return RPC interface tag.
     */
    public String getTag() {
        return tag;
    }

    /**
     * Sets the RPC interface tag.
     *
     * <p>
     * If this parameter is specified then RPC requests will be submitted only to those servers that have the same tag set via {@link
     * RpcServerConfig#setTags(Set)}. If this parameter is not specified then client will be able to submit RPC requests only to
     * those servers that don't have any tags too.
     * </p>
     *
     * @param tag RPC interface tag.
     *
     * @see RpcServerConfig#setTags(Set)
     * @see RpcService#clientFor(Class, String)
     */
    public void setTag(String tag) {
        this.tag = tag;
    }

    /**
     * Fluent-style version of {@link #setTag(String)}.
     *
     * @param tag RPC interface tag.
     *
     * @return This instance.
     */
    public RpcClientConfig withTag(String tag) {
        setTag(tag);

        return this;
    }

    /**
     * Returns the failover policy (see {@link #setFailover(FailoverPolicy)}).
     *
     * @return Failover policy.
     */
    public FailoverPolicy getFailover() {
        return failover;
    }

    /**
     * Sets the failover policy.
     *
     * @param failover Failover policy.
     */
    public void setFailover(FailoverPolicy failover) {
        this.failover = failover;
    }

    /**
     * Fluent-style version of {@link #setFailover(FailoverPolicy)}.
     *
     * @param failover Failover policy.
     *
     * @return This instance.
     *
     * @see RpcClientBuilder#withFailover(FailoverPolicy)
     */
    public RpcClientConfig withFailover(FailoverPolicy failover) {
        setFailover(failover);

        return this;
    }

    /**
     * Returns the RPC load balancer (see {@link #setLoadBalancer(RpcLoadBalancer)}).
     *
     * @return RPC load balancer.
     */
    public RpcLoadBalancer getLoadBalancer() {
        return loadBalancer;
    }

    /**
     * Sets the RPC load balancer.
     *
     * @param loadBalancer RPC load balancer.
     */
    public void setLoadBalancer(RpcLoadBalancer loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    /**
     * Fluent-style version of {@link #setLoadBalancer(RpcLoadBalancer)}.
     *
     * @param loadBalancer RPC load balancer.
     *
     * @return This instance.
     *
     * @see RpcClientBuilder#withLoadBalancer(RpcLoadBalancer)
     */
    public RpcClientConfig withLoadBalancer(RpcLoadBalancer loadBalancer) {
        setLoadBalancer(loadBalancer);

        return this;
    }

    /**
     * Returns the timeout in milliseconds (see {@link #setTimeout(long)}).
     *
     * @return Timeout in milliseconds.
     */
    public long getTimeout() {
        return timeout;
    }

    /**
     * Sets the RPC timeout in milliseconds.
     *
     * <p>
     * If the RPC operation can not be completed at the specified timeout then such operation will end up an error.
     * Specifying a negative or zero value disables the timeout check.
     * </p>
     *
     * @param timeout RPC timeout in milliseconds.
     *
     * @see RpcClientBuilder#withTimeout(long, TimeUnit)
     */
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    /**
     * Fluent-style version of {@link #setTimeout(long)}.
     *
     * @param timeout RPC timeout in milliseconds.
     *
     * @return This instance.
     */
    public RpcClientConfig withTimeout(long timeout) {
        setTimeout(timeout);

        return this;
    }

    @Override
    public String toString() {
        return ToString.format(this);
    }
}
