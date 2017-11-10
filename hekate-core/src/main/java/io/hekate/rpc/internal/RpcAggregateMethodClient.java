package io.hekate.rpc.internal;

import io.hekate.cluster.ClusterNode;
import io.hekate.messaging.MessagingChannel;
import io.hekate.messaging.MessagingFutureException;
import io.hekate.messaging.broadcast.AggregateFuture;
import io.hekate.messaging.broadcast.AggregateResult;
import io.hekate.rpc.RpcAggregate;
import io.hekate.rpc.RpcAggregateException;
import io.hekate.rpc.RpcInterfaceInfo;
import io.hekate.rpc.RpcMethodInfo;
import io.hekate.rpc.RpcService;
import io.hekate.rpc.internal.RpcProtocol.CallRequest;
import io.hekate.rpc.internal.RpcProtocol.ObjectResponse;
import io.hekate.util.format.ToString;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RpcAggregateMethodClient<T> extends RpcMethodClientBase<T> {
    private static final Logger log = LoggerFactory.getLogger(RpcService.class);

    private final Function<AggregateResult<RpcProtocol>, ?> converter;

    public RpcAggregateMethodClient(RpcInterfaceInfo<T> rpc, String tag, RpcMethodInfo method, MessagingChannel<RpcProtocol> channel) {
        super(rpc, tag, method, channel);

        assert method.aggregate().isPresent() : "Not an aggregate method [rpc=" + rpc + ", method=" + method + ']';

        Consumer<AggregateResult<RpcProtocol>> errorCheck;

        RpcAggregate config = method.aggregate().get();

        if (config.remoteErrors() == RpcAggregate.RemoteErrors.IGNORE) {
            errorCheck = null;
        } else {
            errorCheck = aggregate -> {
                if (!aggregate.isSuccess()) {
                    if (config.remoteErrors() == RpcAggregate.RemoteErrors.WARN) {
                        if (log.isWarnEnabled()) {
                            aggregate.errors().forEach((node, err) ->
                                log.warn("RPC aggregation failed [remote-node={}, rpc={}, method={}]", node, rpc, method, err)
                            );
                        }
                    } else {
                        String errMsg = "RPC aggregation failed [" + ToString.formatProperties(aggregate.request()) + ']';

                        Map<ClusterNode, Object> partialResults = new HashMap<>(aggregate.resultsByNode().size(), 1.0f);

                        aggregate.resultsByNode().forEach((node, response) -> {
                            if (response instanceof ObjectResponse) {
                                partialResults.put(node, ((ObjectResponse)response).object());
                            } else {
                                partialResults.put(node, null);
                            }
                        });

                        throw new RpcAggregateException(errMsg, aggregate.errors(), partialResults);
                    }
                }
            };
        }

        if (method.realReturnType().equals(Map.class)) {
            converter = aggregate -> {
                if (errorCheck != null) {
                    errorCheck.accept(aggregate);
                }

                Map<Object, Object> merged = new HashMap<>();

                aggregate.results().forEach(rpcResult -> {
                    if (rpcResult instanceof ObjectResponse) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> part = (Map<Object, Object>)((ObjectResponse)rpcResult).object();

                        merged.putAll(part);
                    }
                });

                return merged;
            };
        } else if (method.realReturnType().equals(Set.class)) {
            converter = aggregate -> {
                if (errorCheck != null) {
                    errorCheck.accept(aggregate);
                }

                Set<Object> merged = new HashSet<>();

                aggregate.results().forEach(rpcResult -> {
                    if (rpcResult instanceof ObjectResponse) {
                        @SuppressWarnings("unchecked")
                        Collection<Object> part = (Collection<Object>)((ObjectResponse)rpcResult).object();

                        merged.addAll(part);
                    }
                });

                return merged;
            };
        } else {
            converter = aggregate -> {
                if (errorCheck != null) {
                    errorCheck.accept(aggregate);
                }

                List<Object> merged = new ArrayList<>();

                aggregate.results().forEach(rpcResult -> {
                    if (rpcResult instanceof ObjectResponse) {
                        @SuppressWarnings("unchecked")
                        Collection<Object> part = (Collection<Object>)((ObjectResponse)rpcResult).object();

                        merged.addAll(part);
                    }
                });

                return merged;
            };
        }
    }

    @Override
    protected Object doInvoke(MessagingChannel<RpcProtocol> callChannel, Object[] args) throws MessagingFutureException,
        InterruptedException, TimeoutException {
        CallRequest<T> request = new CallRequest<>(rpc(), tag(), method(), args);

        AggregateFuture<RpcProtocol> future = callChannel.aggregate(request);

        if (method().isAsync()) {
            return future.thenApply(converter);
        } else {
            AggregateResult<RpcProtocol> result;

            if (callChannel.timeout() > 0) {
                result = future.get(callChannel.timeout(), TimeUnit.MILLISECONDS);
            } else {
                result = future.get();
            }

            return converter.apply(result);
        }
    }
}