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

package io.hekate.lock.internal;

import io.hekate.cluster.ClusterHash;
import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterNodeId;
import io.hekate.cluster.ClusterTopology;
import io.hekate.lock.DistributedLock;
import io.hekate.lock.LockOwnerInfo;
import io.hekate.lock.internal.LockProtocol.LockOwnerRequest;
import io.hekate.lock.internal.LockProtocol.LockOwnerResponse;
import io.hekate.lock.internal.LockProtocol.LockRequest;
import io.hekate.lock.internal.LockProtocol.LockResponse;
import io.hekate.lock.internal.LockProtocol.UnlockRequest;
import io.hekate.lock.internal.LockProtocol.UnlockResponse;
import io.hekate.messaging.MessagingChannel;
import io.hekate.messaging.unicast.RequestCallback;
import io.hekate.messaging.unicast.RequestCondition;
import io.hekate.messaging.unicast.Response;
import io.hekate.partition.PartitionMapper;
import io.hekate.util.format.ToString;
import io.hekate.util.format.ToStringIgnore;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.hekate.messaging.unicast.ReplyDecision.ACCEPT;
import static io.hekate.messaging.unicast.ReplyDecision.REJECT;

class LockControllerClient {
    enum Status {
        LOCKING,

        LOCKED,

        UNLOCKING,

        UNLOCKED,

        TERMINATED
    }

    private static final Logger log = LoggerFactory.getLogger(LockControllerClient.class);

    private static final boolean DEBUG = log.isDebugEnabled();

    private static final boolean TRACE = log.isTraceEnabled();

    private final LockKey key;

    private final long lockId;

    private final long threadId;

    private final ClusterNodeId localNode;

    private final long lockTimeout;

    @ToStringIgnore
    private final MessagingChannel<LockProtocol> channel;

    @ToStringIgnore
    private final ReentrantLock lock = new ReentrantLock();

    @ToStringIgnore
    private final LockFuture lockFuture;

    @ToStringIgnore
    private final LockFuture unlockFuture;

    @ToStringIgnore
    private final LockRegionMetrics metrics;

    @ToStringIgnore
    private final AsyncLockCallbackAdaptor asyncCallback;

    @ToStringIgnore
    private ClusterTopology topology;

    @ToStringIgnore
    private LockOwnerInfo lockOwner;

    @ToStringIgnore
    private ClusterNodeId manager;

    private Status status = Status.UNLOCKED;

    public LockControllerClient(
        long lockId,
        ClusterNodeId localNode,
        long threadId,
        DistributedLock lock,
        MessagingChannel<LockProtocol> channel,
        long lockTimeout,
        LockRegionMetrics metrics,
        AsyncLockCallbackAdaptor asyncCallback
    ) {
        assert localNode != null : "Cluster node is null.";
        assert lock != null : "Lock is null.";
        assert channel != null : "Channel is null.";
        assert metrics != null : "Metrics are null.";

        this.key = new LockKey(lock.regionName(), lock.name());
        this.lockId = lockId;
        this.localNode = localNode;
        this.threadId = threadId;
        this.channel = channel;
        this.lockTimeout = lockTimeout;
        this.metrics = metrics;
        this.asyncCallback = asyncCallback;

        lockFuture = new LockFuture(this);
        unlockFuture = new LockFuture(this);
    }

    public LockKey key() {
        return key;
    }

    public long lockId() {
        return lockId;
    }

    public long threadId() {
        return threadId;
    }

    public ClusterNodeId manager() {
        lock.lock();

        try {
            return manager;
        } finally {
            lock.unlock();
        }
    }

    public LockFuture lockFuture() {
        return lockFuture;
    }

    public LockFuture unlockFuture() {
        return unlockFuture;
    }

    public ClusterNodeId localNode() {
        return localNode;
    }

    public void update(PartitionMapper mapping) {
        if (mapping != null) {
            lock.lock();

            try {
                this.topology = mapping.topology();

                this.manager = mapping.map(key).primaryNode().id();

                if (TRACE) {
                    log.trace("Updated partition mapping [key={}, manager={}, topology={}]", key, manager, topology);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public boolean updateAndCheckLocked(ClusterTopology topology) {
        lock.lock();

        try {
            this.topology = topology;

            if (DEBUG) {
                log.trace("Updated topology [key={}, topology={}]", key, topology);
            }

            return status == Status.LOCKED;
        } finally {
            lock.unlock();
        }
    }

    public void becomeLocking(PartitionMapper mapping) {
        lock.lock();

        try {
            assert status == Status.UNLOCKED;

            status = Status.LOCKING;

            if (DEBUG) {
                log.debug("Became {} [key={}]", status, key);
            }

            update(mapping);

            remoteLock();
        } finally {
            lock.unlock();
        }
    }

    public LockFuture becomeUnlocking() {
        doBecomeUnlocking(false);

        return unlockFuture;
    }

    public void becomeUnlockingIfNotLocked() {
        doBecomeUnlocking(true);
    }

    public void becomeTerminated() {
        lock.lock();

        try {
            status = Status.TERMINATED;

            if (DEBUG) {
                log.debug("Became {} [key={}]", status, key);
            }

            if (!lockFuture.isDone()) {
                lockFuture.completeExceptionally(new CancellationException("Lock service terminated."));
            }

            if (unlockFuture.complete(true)) {
                metrics.onUnlock();

                if (asyncCallback != null) {
                    asyncCallback.onLockRelease();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void doBecomeUnlocking(boolean ignoreIfLocked) {
        lock.lock();

        try {
            switch (status) {
                case LOCKING: {
                    status = Status.UNLOCKING;

                    if (DEBUG) {
                        log.debug("Became {} [key={}]", status, key);
                    }

                    if (!lockFuture.isDone()) {
                        lockFuture.complete(false);
                    }

                    remoteUnlock();

                    break;
                }
                case LOCKED: {
                    if (!ignoreIfLocked) {
                        status = Status.UNLOCKING;

                        if (DEBUG) {
                            log.debug("Became {} [key={}]", status, key);
                        }

                        remoteUnlock();
                    }

                    break;
                }
                case UNLOCKING: {
                    // No-op.
                    break;
                }
                case UNLOCKED: {
                    // No-op.
                    break;
                }
                case TERMINATED: {
                    // No-op.
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unexpected lock status: " + status);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean becomeLocked(ClusterHash requestTopology) {
        lock.lock();

        try {
            if (topology == null || !requestTopology.equals(topology.hash())) {
                if (TRACE) {
                    log.trace("Rejected to become {} [key={}, topology={}]", Status.LOCKED, key, topology);
                }

                return false;
            }

            switch (status) {
                case LOCKING: {
                    status = Status.LOCKED;

                    lockOwner = new DefaultLockOwnerInfo(threadId, topology.localNode());

                    if (DEBUG) {
                        log.debug("Became {} [key={}]", status, key);
                    }

                    metrics.onLock();

                    lockFuture.complete(true);

                    if (asyncCallback != null) {
                        asyncCallback.onLockAcquire(this);
                    }

                    break;
                }
                case LOCKED: {
                    // No-op.
                    break;
                }
                case UNLOCKING: {
                    // No-op.
                    break;
                }
                case UNLOCKED: {
                    remoteUnlock();

                    break;
                }
                case TERMINATED: {
                    // No-op.
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unexpected lock status: " + status);
                }
            }
        } finally {
            lock.unlock();
        }

        return true;
    }

    private void becomeUnlocked() {
        doBecomeUnlocked();
    }

    private boolean tryBecomeUnlocked(ClusterHash requestTopology) {
        lock.lock();

        try {
            if (topology == null || (requestTopology != null && !requestTopology.equals(topology.hash()))) {
                if (TRACE) {
                    log.trace("Rejected to become {} [key={}, topology={}]", Status.UNLOCKED, key, topology);
                }

                return false;
            } else {
                doBecomeUnlocked();

                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    private void doBecomeUnlocked() {
        lock.lock();

        try {
            switch (status) {
                case LOCKING: {
                    status = Status.UNLOCKED;

                    if (DEBUG) {
                        log.debug("Became {} [key={}]", status, key);
                    }

                    lockFuture.complete(false);

                    break;
                }
                case LOCKED: {
                    illegalStateTransition(Status.UNLOCKED);

                    break;
                }
                case UNLOCKING: {
                    status = Status.UNLOCKED;

                    if (DEBUG) {
                        log.debug("Became {} [key={}]", status, key);
                    }

                    metrics.onUnlock();

                    unlockFuture.complete(true);

                    if (asyncCallback != null) {
                        asyncCallback.onLockRelease();
                    }

                    break;
                }
                case UNLOCKED: {
                    // No-op.
                    break;
                }
                case TERMINATED: {
                    // No-op.
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unexpected lock status: " + status);
                }
            }
        } finally {
            lockOwner = null;

            lock.unlock();
        }
    }

    private void remoteLock() {
        LockRequest lockReq = new LockRequest(lockId, key.region(), key.name(), localNode, lockTimeout, threadId);

        RequestCondition<LockProtocol> until = (err, reply) -> {
            // Do not retry if not LOCKING anymore.
            if (!is(Status.LOCKING)) {
                return ACCEPT;
            }

            if (err == null) {
                LockResponse lockRsp = reply.get(LockResponse.class);

                if (DEBUG) {
                    log.debug("Got lock response [from={}, response={}]", reply.from(), reply);
                }

                switch (lockRsp.status()) {
                    case OK: {
                        ClusterHash topology = reply.topology().hash();

                        if (becomeLocked(topology)) {
                            return ACCEPT;
                        } else {
                            return REJECT;
                        }
                    }
                    case RETRY: {
                        // Retry.
                        return REJECT;
                    }
                    case LOCK_TIMEOUT: {
                        becomeUnlocked();

                        return ACCEPT;
                    }
                    case LOCK_BUSY: {
                        becomeUnlocked();

                        return ACCEPT;
                    }
                    case LOCK_OWNER_CHANGE: {
                        throw new IllegalArgumentException("Got an unexpected lock owner update message: " + reply);
                    }
                    default: {
                        throw new IllegalArgumentException("Unexpected status: " + lockRsp.status());
                    }
                }
            } else {
                if (DEBUG) {
                    log.debug("Failed to send lock request [error={}, request={}]", err.toString(), lockReq);
                }

                // Retry on error.
                return REJECT;
            }
        };

        RequestCallback<LockProtocol> onResponse = (err, rsp) -> {
            if (err == null) {
                LockResponse lockRsp = rsp.get(LockResponse.class);

                if (lockRsp.status() == LockResponse.Status.LOCK_OWNER_CHANGE) {
                    processLockOwnerChange(lockRsp, rsp);
                }
            } else if (is(Status.LOCKING)) {
                log.error("Failed to submit lock request [request={}]", lockReq, err);
            }
        };

        if (asyncCallback == null) {
            if (DEBUG) {
                log.debug("Submitting lock request [request={}]", lockReq);
            }

            // Send single request if we don't need to subscribe for updates.
            channel.request(lockReq)
                .withAffinity(key)
                .until(until)
                .submit(onResponse);
        } else {
            if (DEBUG) {
                log.debug("Submitting lock subscription [request={}]", lockReq);
            }

            // Send subscription request if we need to receive lock owner updates.
            channel.subscribe(lockReq)
                .withAffinity(key)
                .until(until)
                .submit(onResponse);
        }
    }

    private void remoteUnlock() {
        UnlockRequest unlockReq = new UnlockRequest(lockId, key.region(), key.name(), localNode);

        if (DEBUG) {
            log.debug("Submitting unlock request [request={}]", unlockReq);
        }

        channel.request(unlockReq)
            .withAffinity(key)
            .until((err, rsp) -> {
                if (!is(Status.UNLOCKING)) {
                    // Do not retry if not UNLOCKING anymore.
                    return ACCEPT;
                }

                if (err == null) {
                    UnlockResponse lockRsp = rsp.get(UnlockResponse.class);

                    if (DEBUG) {
                        log.debug("Got unlock response [from={}, response={}]", rsp.from(), lockRsp);
                    }

                    if (lockRsp.status() == UnlockResponse.Status.OK && tryBecomeUnlocked(rsp.topology().hash())) {
                        return ACCEPT;
                    } else {
                        return REJECT;
                    }
                } else {
                    if (DEBUG) {
                        log.debug("Failed to send unlock request [error={}, request={}]", err.toString(), unlockReq);
                    }

                    // Retry on error.
                    return REJECT;
                }
            })
            .submit((err, rsp) -> {
                if (err != null && is(Status.UNLOCKING)) {
                    log.error("Failed to submit unlock request [request={}]", unlockReq, err);
                }
            });
    }

    private void processLockOwnerChange(LockResponse lockRsp, Response<LockProtocol> msg) {
        boolean notified = tryNotifyOnLockOwnerChange(lockRsp.owner(), lockRsp.ownerThreadId(), msg.topology().hash());

        if (!notified) {
            if (DEBUG) {
                log.debug("Sending explicit lock owner query [to={}, key={}]", msg.from(), key);
            }

            channel.request(new LockOwnerRequest(key.region(), key.name()))
                .withAffinity(key)
                .until((err, rsp) -> {
                    // Do not retry if not LOCKING anymore.
                    if (!is(Status.LOCKING)) {
                        return ACCEPT;
                    }

                    if (err == null) {
                        if (DEBUG) {
                            log.debug("Got explicit lock owner query response [from={}, response={}]", rsp.from(), rsp);
                        }

                        LockOwnerResponse ownerRsp = rsp.get(LockOwnerResponse.class);

                        if (ownerRsp.status() != LockOwnerResponse.Status.OK
                            || !tryNotifyOnLockOwnerChange(ownerRsp.owner(), ownerRsp.threadId(), rsp.topology().hash())) {
                            // Retry if update got rejected.
                            return REJECT;
                        } else {
                            return ACCEPT;
                        }
                    } else {
                        if (DEBUG) {
                            log.debug("Failed to query for explicit lock owner [to={}, key={}, cause={}]", rsp.from(), key, err.toString());
                        }

                        // Retry on error.
                        return REJECT;
                    }
                })
                .submit();
        }
    }

    private boolean tryNotifyOnLockOwnerChange(ClusterNodeId ownerId, long ownerThreadId, ClusterHash requestTopology) {
        lock.lock();

        try {
            if (status != Status.LOCKING) {
                if (TRACE) {
                    log.trace("Ignored lock owner change because status is not {} [key={}, status={}]", Status.LOCKING, key, status);
                }

                // Not locking anymore (should not retry).
                return true;
            } else if (topology == null || !requestTopology.equals(topology.hash())) {
                if (TRACE) {
                    log.trace("Ignored lock owner change because of topology mismatch [key={}, topology={}]", key, topology);
                }

                // Should retry.
                return false;
            } else {
                ClusterNode ownerNode = topology.get(ownerId);

                LockOwnerInfo newOwner = new DefaultLockOwnerInfo(ownerThreadId, ownerNode);

                if (lockOwner == null) {
                    if (DEBUG) {
                        log.debug("Set initial lock owner [key={}, owner={}]", key, newOwner);
                    }

                    lockOwner = newOwner;

                    asyncCallback.onLockBusy(newOwner);
                } else if (!lockOwner.equals(newOwner)) {
                    if (DEBUG) {
                        log.debug("Updated lock owner [key={}, owner={}]", key, newOwner);
                    }

                    lockOwner = newOwner;

                    asyncCallback.onLockOwnerChange(newOwner);
                }

                // Successfully updated (should not retry).
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean is(Status status) {
        lock.lock();

        try {
            return this.status == status;
        } finally {
            lock.unlock();
        }
    }

    private void illegalStateTransition(Status newStatus) {
        throw new IllegalStateException("Illegal lock state transition from " + status + " to " + newStatus);
    }

    @Override
    public String toString() {
        return ToString.format(this);
    }
}
