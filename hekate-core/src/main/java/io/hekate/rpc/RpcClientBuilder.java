package io.hekate.rpc;

import io.hekate.cluster.ClusterFilterSupport;
import io.hekate.cluster.ClusterView;
import io.hekate.failover.FailoverPolicy;
import io.hekate.failover.FailoverPolicyBuilder;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Builder for RPC client proxies.
 *
 * <p>
 * For more details about the Remote Procedure Call API and its capabilities please see the documentation of the {@link RpcService}
 * interface.
 * </p>
 *
 * @param <T> RPC interface type.
 *
 * @see RpcService#clientFor(Class)
 * @see RpcService#clientFor(Class, String)
 */
public interface RpcClientBuilder<T> extends ClusterFilterSupport<RpcClientBuilder<T>> {
    /**
     * Constructs a new RPC client proxy.
     *
     * <p>
     * <b>Note:</b> this operation is relatively expensive and it is highly recommended to cache RPC proxy instances that are produced by
     * this method.
     * </p>
     *
     * @return new RPC client proxy instance.
     */
    T build();

    /**
     * Returns the RPC interface type.
     *
     * @return RPC interface type.
     *
     * @see Rpc
     */
    Class<T> type();

    /**
     * Returns the RPC tag value.
     *
     * @return RPC tag value.
     *
     * @see RpcService#clientFor(Class, String)
     * @see RpcServerConfig#setTags(Set)
     */
    String tag();

    /**
     * Returns the failover policy (see {@link #withFailover(FailoverPolicy)}).
     *
     * @return Failover policy.
     */
    FailoverPolicy failover();

    /**
     * Returns a new builder that will apply the specifier failover policy to all of the clients that it will produce.
     *
     * <p>
     * Alternatively, the failover policy can be pre-configured via {@link RpcClientConfig#setFailover(FailoverPolicy)} method.
     * </p>
     *
     * @param policy Failover policy.
     *
     * @return New builder that will use the specified failover policy and will inherit all other options from this builder.
     */
    RpcClientBuilder<T> withFailover(FailoverPolicy policy);

    /**
     * Returns a new builder that will apply the specifier failover policy to all of the clients that it will produce.
     *
     * <p>
     * Alternatively, the failover policy can be pre-configured via {@link RpcClientConfig#setFailover(FailoverPolicy)} method.
     * </p>
     *
     * @param policy Failover policy builder.
     *
     * @return New builder that will use the specified failover policy and will inherit all other options from this builder.
     */
    RpcClientBuilder<T> withFailover(FailoverPolicyBuilder policy);

    /**
     * Returns a new builder that will apply the specifier load balancer to all of the clients that it will produce.
     *
     * <p>
     * Alternatively, the load balancer can be pre-configured via {@link RpcClientConfig#setLoadBalancer(RpcLoadBalancer)} method.
     * </p>
     *
     * @param balancer Load balancer.
     *
     * @return New builder that will use the specified load balancer and will inherit all other options from this builder.
     */
    RpcClientBuilder<T> withLoadBalancer(RpcLoadBalancer balancer);

    /**
     * Returns the timeout value in milliseconds (see {@link #withTimeout(long, TimeUnit)}).
     *
     * @return Timeout in milliseconds or 0, if timeout was not specified.
     */
    long timeout();

    /**
     * Returns a new builder that will apply the specifier timeout to all of the clients that it will produce.
     *
     * <p>
     * If the RPC operation can not be completed at the specified timeout then such operation will end up an error.
     * Specifying a negative or zero value disables the timeout check.
     * </p>
     *
     * <p>
     * Alternatively, the timeout value can be pre-configured via {@link RpcClientConfig#setTimeout(long)} method.
     * </p>
     *
     * @param timeout Timeout.
     * @param unit Time unit.
     *
     * @return New builder that will use the specified timeout and will inherit all other options from this builder.
     */
    RpcClientBuilder<T> withTimeout(long timeout, TimeUnit unit);

    /**
     * Returns the cluster view of this builder.
     *
     * <p>
     * All clients that are produced by this builder will use the same cluster view for RPC requests routing and load balancing.
     * </p>
     *
     * @return Cluster view of this builder.
     */
    ClusterView cluster();
}