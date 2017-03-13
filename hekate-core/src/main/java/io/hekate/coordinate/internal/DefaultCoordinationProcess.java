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

import io.hekate.cluster.ClusterTopology;
import io.hekate.coordinate.CoordinationFuture;
import io.hekate.coordinate.CoordinationHandler;
import io.hekate.coordinate.CoordinationProcess;
import io.hekate.core.internal.util.Waiting;
import io.hekate.messaging.Message;
import io.hekate.messaging.MessagingChannel;
import io.hekate.util.StateGuard;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DefaultCoordinationProcess implements CoordinationProcess {
    private static final Logger log = LoggerFactory.getLogger(DefaultCoordinationProcess.class);

    private static final boolean DEBUG = log.isDebugEnabled();

    private final String name;

    private final CoordinationHandler handler;

    private final ExecutorService async;

    private final MessagingChannel<CoordinationProtocol> channel;

    private final long failoverDelay;

    private final StateGuard guard = new StateGuard(DefaultCoordinationProcess.class);

    private final CoordinationFuture future = new CoordinationFuture();

    private DefaultCoordinationContext ctx;

    public DefaultCoordinationProcess(String name, CoordinationHandler handler, ExecutorService async,
        MessagingChannel<CoordinationProtocol> channel, long failoverDelay) {
        assert name != null : "Name is null.";
        assert handler != null : "Protocol is null.";
        assert async != null : "Executor service is null.";
        assert channel != null : "Messaging channel is null.";

        this.name = name;
        this.handler = handler;
        this.async = async;
        this.channel = channel.withAffinityKey(name);
        this.failoverDelay = failoverDelay;
    }

    public void initialize() {
        guard.lockWrite();

        try {
            guard.becomeInitialized();

            async.execute(() -> {
                try {
                    if (DEBUG) {
                        log.debug("Initializing handler [process={}]", name);
                    }

                    handler.initialize();
                } catch (RuntimeException | Error e) {
                    log.error("Got an unexpected runtime error during coordination [process={}]", name, e);
                }
            });
        } finally {
            guard.unlockWrite();
        }
    }

    public Waiting terminate() {
        Waiting waiting;

        guard.lockWrite();

        try {
            if (guard.becomeTerminated()) {
                DefaultCoordinationContext localCtx = this.ctx;

                if (localCtx != null) {
                    localCtx.cancel();

                    async.execute(() -> {
                        try {
                            localCtx.halt();
                        } catch (RuntimeException | Error e) {
                            log.error("Got an unexpected runtime error during coordination [process={}]", name, e);
                        }

                        try {
                            if (DEBUG) {
                                log.debug("Terminating handler [process={}]", name);
                            }

                            handler.terminate();
                        } catch (RuntimeException | Error e) {
                            log.error("Got an unexpected runtime error during coordination [process={}]", name, e);
                        }
                    });
                }

                async.execute(async::shutdown);

                waiting = () -> async.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

                future.cancel(false);
            } else {
                waiting = Waiting.NO_WAIT;
            }

            this.ctx = null;
        } finally {
            guard.unlockWrite();
        }

        return waiting;
    }

    public void processMessage(Message<CoordinationProtocol> msg) {
        assert msg != null : "Message is null.";

        guard.lockRead();

        try {
            DefaultCoordinationContext localCtx = this.ctx;

            if (guard.isInitialized() && localCtx != null) {
                async.execute(() -> {
                    try {
                        localCtx.processMessage(msg);
                    } catch (RuntimeException | Error e) {
                        log.error("Failed to process coordination request [message={}]", msg, e);
                    }
                });
            } else {
                if (DEBUG) {
                    log.debug("Rejected coordination request since process is not initialized [message={}]", msg.get());
                }

                msg.reply(CoordinationProtocol.Reject.INSTANCE);
            }
        } finally {
            guard.unlockRead();
        }
    }

    public void processTopologyChange(ClusterTopology newTopology) {
        assert newTopology != null : "New topology is null.";

        guard.lockWrite();

        try {
            if (guard.isInitialized()) {
                if (DEBUG) {
                    log.debug("Processing topology change [topology={}]", newTopology);
                }

                boolean topologyChanged = true;

                DefaultCoordinationContext oldCtx = this.ctx;

                if (oldCtx != null) {
                    if (oldCtx.getTopology().equals(newTopology)) {
                        topologyChanged = false;
                    } else {
                        oldCtx.cancel();

                        async.execute(() -> {
                            try {
                                oldCtx.halt();
                            } catch (RuntimeException | Error e) {
                                log.error("Got an unexpected runtime error during coordination [process={}]", name, e);
                            }
                        });
                    }
                }

                if (topologyChanged) {
                    DefaultCoordinationContext newCtx = new DefaultCoordinationContext(name, newTopology, channel, async, handler,
                        failoverDelay, () -> future.complete(this)
                    );

                    this.ctx = newCtx;

                    if (DEBUG) {
                        log.debug("Created new context [context={}]", newCtx);
                    }

                    async.execute(() -> {
                        try {
                            newCtx.coordinate();
                        } catch (RuntimeException | Error e) {
                            log.error("Got an unexpected runtime error during coordination [process={}]", name, e);
                        }
                    });
                } else {
                    if (DEBUG) {
                        log.debug("Topology not changed [process={}]", name);
                    }
                }
            }
        } finally {
            guard.unlockWrite();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CoordinationFuture getFuture() {
        return future.fork();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends CoordinationHandler> T getHandler() {
        return (T)handler;
    }
}