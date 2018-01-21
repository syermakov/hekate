/*
 * Copyright 2018 The Hekate Project
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

package io.hekate.network.netty;

import io.hekate.codec.Codec;
import io.hekate.codec.CodecException;
import io.hekate.core.internal.util.Utils;
import io.hekate.network.NetworkEndpoint;
import io.hekate.network.NetworkFuture;
import io.hekate.network.NetworkSendCallback;
import io.hekate.network.NetworkServerHandler;
import io.hekate.network.netty.DefaultNettyServer.HandlerRegistration;
import io.hekate.network.netty.NetworkProtocol.HandshakeAccept;
import io.hekate.network.netty.NetworkProtocol.HandshakeReject;
import io.hekate.network.netty.NetworkProtocol.HandshakeRequest;
import io.hekate.network.netty.NetworkProtocol.Heartbeat;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.handler.traffic.TrafficCounter;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.ThrowableUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class NettyServerClient extends ChannelInboundHandlerAdapter implements NetworkEndpoint<Object>, NettyChannelSupport {
    private static final ClosedChannelException WRITE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
        new ClosedChannelException(), NettyServerClient.class, "doSend()"
    );

    private final Map<String, HandlerRegistration> handlers;

    private final InetSocketAddress remoteAddress;

    private final InetSocketAddress localAddress;

    private final EventLoopGroup coreEventLoopGroup;

    private final ChannelFutureListener writeListener;

    private final NettyWriteQueue writeQueue = new NettyWriteQueue();

    private final boolean ssl;

    private final int hbInterval;

    private final int hbLossThreshold;

    private final boolean hbDisabled;

    private final GenericFutureListener<Future<? super Void>> hbFlushListener;

    private boolean hbFlushed = true;

    private Logger log = LoggerFactory.getLogger(NettyServerClient.class);

    private boolean debug = log.isDebugEnabled();

    private boolean trace = log.isTraceEnabled();

    private int ignoreReadTimeouts;

    private NetworkServerHandler<Object> serverHandler;

    private HandlerRegistration handlerReg;

    private NettyMetricsSink metrics;

    private String protocol;

    private Codec<Object> codec;

    private EventLoop eventLoop;

    private boolean connectNotified;

    private volatile Object userContext;

    private volatile ChannelHandlerContext handlerCtx;

    public NettyServerClient(InetSocketAddress remoteAddress, InetSocketAddress localAddress, boolean ssl, int hbInterval,
        int hbLossThreshold, boolean hbDisabled, Map<String, HandlerRegistration> handlers, EventLoopGroup coreEventLoopGroup) {
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.ssl = ssl;
        this.hbInterval = hbInterval;
        this.hbLossThreshold = hbLossThreshold;
        this.hbDisabled = hbDisabled;
        this.handlers = handlers;
        this.coreEventLoopGroup = coreEventLoopGroup;

        writeListener = future -> {
            if (future.isSuccess()) {
                // Notify metrics on successful operation.
                if (metrics != null) {
                    metrics.onMessageDequeue();

                    metrics.onMessageSent();
                }
            } else {
                ChannelPipeline pipeline = future.channel().pipeline();

                // Notify on error (only if pipeline is not empty).
                if (pipeline.last() != null) {
                    future.channel().pipeline().fireExceptionCaught(future.cause());
                }

                // Notify metrics on failed operation.
                if (metrics != null) {
                    metrics.onMessageDequeue();

                    metrics.onMessageSendError();
                }
            }
        };

        hbFlushListener = future -> hbFlushed = true;
    }

    @Override
    public String protocol() {
        return protocol;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public InetSocketAddress localAddress() {
        return localAddress;
    }

    @Override
    public boolean isSecure() {
        return ssl;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> C getContext() {
        return (C)userContext;
    }

    @Override
    public void setContext(Object ctx) {
        this.userContext = ctx;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();

        if (debug) {
            log.debug("Got connection [from={}]", channel.remoteAddress());
        }

        this.handlerCtx = ctx;

        mayBeCreateIdleStateHandler().ifPresent(handler ->
            ctx.pipeline().addFirst(IdleStateHandler.class.getName(), handler)
        );
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (handlerReg != null) {
            handlerReg.remove(this);
        }

        if (serverHandler != null && connectNotified) {
            try {
                serverHandler.onDisconnect(this);
            } finally {
                serverHandler = null;
            }
        }

        if (metrics != null) {
            metrics.onDisconnect();
        }

        this.handlerCtx = null;

        if (debug) {
            log.debug("Closed connection [from={}]", address());
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (isHandshakeDone()) {
            if (msg instanceof Heartbeat) {
                if (trace) {
                    log.trace("Received network heartbeat from client [from={}]", address());
                }
            } else {
                NettyMessage netMsg = (NettyMessage)msg;

                netMsg.prepare(log);

                if (trace) {
                    log.trace("Message buffer prepared [from={}, message={}]", address(), netMsg);
                }

                if (metrics != null) {
                    metrics.onMessageReceived();
                }

                try {
                    serverHandler.onMessage(netMsg, this);
                } finally {
                    netMsg.release();
                }
            }
        } else {
            if (debug) {
                log.debug("Received network handshake request [from={}, message={}]", address(), msg);
            }

            HandshakeRequest handshake = (HandshakeRequest)msg;

            String protocol;
            HandlerRegistration handlerReg;

            if (handshake == null) {
                protocol = null;
                handlerReg = null;
            } else {
                this.protocol = protocol = handshake.protocol();

                handlerReg = handlers.get(protocol);
            }

            if (handlerReg == null) {
                if (debug) {
                    log.debug("Closing connection with unsupported protocol [from={}, protocol={}]", address(), protocol);
                }

                HandshakeReject reject = new HandshakeReject("Unsupported protocol [protocol=" + protocol + ']');

                ctx.writeAndFlush(reject).addListener(ChannelFutureListener.CLOSE);
            } else {
                // Map connection to a thread.
                EventLoop eventLoop = mapToThread(handshake.threadAffinity(), handlerReg);

                // Check if we need to re-bind this channel to another thread.
                if (eventLoop.inEventLoop()) {
                    // No need to rebind.
                    init(ctx.channel(), handshake, handlerReg);
                } else {
                    if (debug) {
                        log.debug("Registering channel to a custom NIO thread [from={}, protocol={}]", address(), protocol);
                    }

                    // Unregister and then re-register IdleStateHandler in order to prevent RejectedExecutionException if same
                    // instance is used on different threads.
                    ctx.pipeline().remove(IdleStateHandler.class.getName());

                    Channel channel = ctx.channel();

                    channel.deregister().addListener(deregister -> {
                        if (deregister.isSuccess()) {
                            if (!eventLoop.isShutdown() && channel.isOpen()) {
                                eventLoop.register(channel).addListener(register -> {
                                    if (register.isSuccess() && channel.isOpen()) {
                                        if (debug) {
                                            log.debug("Registered channel to a custom NIO thread "
                                                + "[from={}, protocol={}]", address(), protocol);
                                        }

                                        mayBeCreateIdleStateHandler().ifPresent(handler ->
                                            ctx.pipeline().addFirst(IdleStateHandler.class.getName(), handler)
                                        );

                                        init(channel, handshake, handlerReg);
                                    }
                                });
                            }
                        }
                    });
                }
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) throws Exception {
        Throwable realError = NettyErrorUtils.unwrap(error);

        boolean disconnect = true;

        if (realError instanceof CodecException) {
            disconnect = false;
        } else if (realError instanceof IOException) {
            if (debug) {
                log.debug("Closing inbound network connection due to I/O error "
                    + "[protocol={}, from={}, reason={}]", protocol, address(), realError.toString());
            }
        } else if (log.isErrorEnabled()) {
            log.error("Inbound network connection failure [protocol={}, from={}]", protocol, address(), realError);
        }

        if (disconnect) {
            if (serverHandler != null) {
                serverHandler.onFailure(this, realError);
            }

            ctx.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof AutoReadChangeEvent) {
            if (evt == AutoReadChangeEvent.PAUSE) {
                // Completely ignore read timeouts.
                ignoreReadTimeouts = -1;
            } else {
                // Ignore next timeout.
                ignoreReadTimeouts = 1;
            }
        } else if (evt instanceof IdleStateEvent) {
            IdleStateEvent idle = (IdleStateEvent)evt;

            if (idle.state() == IdleState.WRITER_IDLE) {
                if (hbFlushed && isHandshakeDone()) {
                    // Make sure that we don't push multiple heartbeats to the network buffer simultaneously.
                    // Need to perform this check since remote peer can hang and stop reading
                    // while this channel will still be trying to put more and more heartbeats on its send buffer.
                    hbFlushed = false;

                    ctx.writeAndFlush(Heartbeat.INSTANCE).addListener(hbFlushListener);
                }
            } else {
                // Reader idle.
                // Ignore if auto-reading was disabled since in such case we will not read any heartbeats.
                if (ignoreReadTimeouts != -1 && ctx.channel().config().isAutoRead()) {
                    // Check if timeout should be ignored.
                    if (ignoreReadTimeouts > 0) {
                        // Decrement the counter of ignored timeouts.
                        ignoreReadTimeouts--;
                    } else {
                        throw new SocketTimeoutException();
                    }
                }
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void send(Object msg) {
        doSend(msg, null);
    }

    @Override
    public void send(Object msg, NetworkSendCallback<Object> onSend) {
        doSend(msg, onSend);
    }

    @Override
    public void pauseReceiving(Consumer<NetworkEndpoint<Object>> callback) {
        pauseReceiver(true, callback);
    }

    @Override
    public void resumeReceiving(Consumer<NetworkEndpoint<Object>> callback) {
        pauseReceiver(false, callback);
    }

    @Override
    public boolean isReceiving() {
        ChannelHandlerContext localCtx = this.handlerCtx;

        return localCtx != null && localCtx.channel().config().isAutoRead();
    }

    @Override
    public NetworkFuture<Object> disconnect() {
        NetworkFuture<Object> future = new NetworkFuture<>();

        ChannelHandlerContext localCtx = this.handlerCtx;

        if (localCtx == null) {
            future.complete(this);
        } else {
            this.handlerCtx = null;

            ChannelFuture closeFuture = localCtx.close();

            closeFuture.addListener(completed -> future.complete(this));
        }

        return future;
    }

    @Override
    public Optional<Channel> nettyChannel() {
        ChannelHandlerContext ctx = this.handlerCtx;

        return ctx != null ? Optional.of(ctx.channel()) : Optional.empty();
    }

    private void pauseReceiver(boolean pause, Consumer<NetworkEndpoint<Object>> callback) {
        ChannelHandlerContext localCtx = this.handlerCtx;

        if (localCtx != null) {
            if (debug) {
                if (pause) {
                    log.debug("Pausing inbound receiver [from={}, protocol={}]", address(), protocol);
                } else {
                    log.debug("Resuming Pausing inbound receiver [from={}, protocol={}]", address(), protocol);
                }
            }

            Channel channel = localCtx.channel();
            EventLoop eventLoop = channel.eventLoop();

            if (eventLoop.inEventLoop()) {
                channel.config().setAutoRead(!pause);

                notifyOnReceivePause(pause, callback, channel);
            } else {
                eventLoop.execute(() -> {
                    channel.config().setAutoRead(!pause);

                    notifyOnReceivePause(pause, callback, channel);
                });
            }
        } else if (callback != null) {
            callback.accept(this);
        }
    }

    private void notifyOnReceivePause(boolean pause, Consumer<NetworkEndpoint<Object>> callback, Channel channel) {
        assert channel.eventLoop().inEventLoop() : "Must be on event loop thread.";

        channel.pipeline().fireUserEventTriggered(pause ? AutoReadChangeEvent.PAUSE : AutoReadChangeEvent.RESUME);

        if (callback != null) {
            try {
                callback.accept(this);
            } catch (RuntimeException | Error e) {
                log.error("Got an unexpected runtime error while notifying callback on network inbound receive status change "
                    + "[pause={}, from={}, protocol={}]", pause, address(), protocol, e);
            }
        }
    }

    private Optional<IdleStateHandler> mayBeCreateIdleStateHandler() {
        if (hbInterval > 0 && hbLossThreshold > 0) {
            int interval = hbInterval;
            int readTimeout = hbInterval * hbLossThreshold;

            if (hbDisabled) {
                interval = 0;

                if (debug) {
                    log.debug("Registering heartbeatless timeout handler [from={}, read-timeout={}]", address(), readTimeout);
                }
            } else {
                if (debug) {
                    log.debug("Registering heartbeat handler [from={}, interval={}, loss-threshold={}, read-timeout={}]",
                        address(), interval, hbLossThreshold, readTimeout);
                }
            }

            return Optional.of(new IdleStateHandler(readTimeout, interval, 0, TimeUnit.MILLISECONDS));
        }

        return Optional.empty();
    }

    private void init(Channel channel, HandshakeRequest request, HandlerRegistration handlerReg) {
        NettyServerHandlerConfig<Object> cfg = handlerReg.config();

        if (cfg.getLoggerCategory() != null) {
            log = LoggerFactory.getLogger(cfg.getLoggerCategory());

            debug = log.isDebugEnabled();
            trace = log.isTraceEnabled();
        }

        if (debug) {
            log.debug("Initialized connection [from={}, protocol={}]", address(), cfg.getProtocol());
        }

        this.eventLoop = channel.eventLoop();
        this.serverHandler = cfg.getHandler();
        this.handlerReg = handlerReg;
        this.metrics = handlerReg.metrics();
        this.codec = request.codec();

        // Register this client.
        handlerReg.add(this);

        if (metrics != null) {
            channel.pipeline().addFirst(new ChannelTrafficShapingHandler(0, 0, NettyClient.TRAFFIC_SHAPING_INTERVAL) {
                @Override
                protected void doAccounting(TrafficCounter counter) {
                    metrics.onBytesReceived(counter.lastReadBytes());
                    metrics.onBytesSent(counter.lastWrittenBytes());
                }
            });

            metrics.onConnect();
        }

        // Accept handshake.
        HandshakeAccept accepted = new HandshakeAccept(hbInterval, hbLossThreshold, hbDisabled);

        channel.writeAndFlush(accepted).addListener(future -> {
                if (channel.isOpen()) {
                    connectNotified = true;

                    // Notify on connect.
                    serverHandler.onConnect(request.payload(), this);
                }
            }
        );
    }

    private void doSend(Object msg, NetworkSendCallback<Object> onSend) {
        ChannelHandlerContext localCtx = this.handlerCtx;

        if (localCtx == null) {
            // Notify on failure.
            if (metrics != null) {
                metrics.onMessageSendError();
            }

            if (onSend != null) {
                NettyUtils.runAtAllCost(eventLoop, () ->
                    notifyOnError(msg, onSend, WRITE_CLOSED_CHANNEL_EXCEPTION)
                );
            }
        } else {
            // Write message to the channel.
            write(msg, onSend, localCtx);
        }
    }

    private void write(Object msg, NetworkSendCallback<Object> onSend, ChannelHandlerContext localCtx) {
        if (!codec.baseType().isInstance(msg)) {
            String expected = codec.baseType().getName();
            String real = msg.getClass().getName();

            notifyOnError(msg, onSend, new CodecException("Unsupported message type [expected=" + expected + ", real=" + real + ']'));
        }

        if (debug) {
            log.debug("Sending to a client [to={}, message={}]", address(), msg);
        }

        Channel channel = localCtx.channel();

        if (metrics != null) {
            metrics.onMessageEnqueue();
        }

        // Prepare deferred message.
        DeferredMessage deferred;

        boolean failed = false;

        // Maybe pre-encode message.
        if (codec.isStateful()) {
            deferred = new DeferredMessage(msg, msg, channel);
        } else {
            if (trace) {
                log.trace("Pre-encoding message [to={}, message={}]", address(), msg);
            }

            try {
                ByteBuf buf = NetworkProtocolCodec.preEncode(msg, codec, localCtx.alloc());

                deferred = new DeferredMessage(buf, msg, channel);
            } catch (CodecException e) {
                deferred = fail(msg, channel, e);

                failed = true;
            }
        }

        deferred.addListener((ChannelFuture result) -> {
            if (debug) {
                if (result.isSuccess()) {
                    log.debug("Done sending message to a client [to={}, message={}]", address(), msg);
                } else {
                    log.debug("Failed to send message to a client [to={}, message={}]", address(), msg, result.cause());
                }
            }

            writeListener.operationComplete(result);

            if (onSend != null) {
                try {
                    onSend.onComplete(msg, Optional.ofNullable(result.cause()), NettyServerClient.this);
                } catch (Throwable t) {
                    if (log.isErrorEnabled()) {
                        log.error("Failed to notify network message callback [message={}]", msg, t);
                    }
                }
            }
        });

        if (!failed) {
            writeQueue.enqueue(deferred, localCtx.executor());
        }
    }

    private EventLoop mapToThread(int affinity, HandlerRegistration handler) {
        EventLoopGroup group;

        // Check if a dedicated thread pool is defined for this protocol.
        if (handler.config().getEventLoop() == null) {
            // Use core thread pool.
            group = coreEventLoopGroup;
        } else {
            // Use dedicated thread pool.
            group = handler.config().getEventLoop();
        }

        List<EventLoop> eventLoops = new ArrayList<>();

        // Assumes that the same group always returns its event loops in the same order.
        for (Iterator<EventExecutor> it = group.iterator(); it.hasNext(); ) {
            eventLoops.add((EventLoop)it.next());
        }

        return eventLoops.get(Utils.mod(affinity, eventLoops.size()));
    }

    private DeferredMessage fail(Object msg, Channel channel, Throwable error) {
        DeferredMessage promise = new DeferredMessage(msg, msg, channel);

        promise.setFailure(error);

        return promise;
    }

    private void notifyOnError(Object msg, NetworkSendCallback<Object> onSend, Throwable error) {
        try {
            onSend.onComplete(msg, Optional.of(error), this);
        } catch (RuntimeException | Error e) {
            log.error("Failed to notify callback on network operation failure "
                + "[protocol={}, from={}, message={}]", protocol, address(), msg, e);
        }
    }

    private boolean isHandshakeDone() {
        return serverHandler != null;
    }

    private InetSocketAddress address() {
        return remoteAddress;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "[from=" + address()
            + ", protocol=" + protocol
            + ']';
    }
}
