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

package io.hekate.election.internal;

import io.hekate.cluster.ClusterNode;
import io.hekate.core.internal.util.ArgAssert;
import io.hekate.core.internal.util.Waiting;
import io.hekate.election.Candidate;
import io.hekate.election.FollowerContext;
import io.hekate.election.LeaderChangeListener;
import io.hekate.election.LeaderContext;
import io.hekate.election.LeaderFuture;
import io.hekate.lock.AsyncLockCallback;
import io.hekate.lock.DistributedLock;
import io.hekate.lock.LockOwnerInfo;
import io.hekate.util.format.ToString;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CandidateHandler implements AsyncLockCallback {
    private static class DefaultFollowerContext implements FollowerContext {
        private final ClusterNode localNode;

        private final AtomicReference<ClusterNode> leader;

        private final List<LeaderChangeListener> listeners = new CopyOnWriteArrayList<>();

        public DefaultFollowerContext(ClusterNode leader, ClusterNode localNode) {
            assert leader != null : "Leader is null.";
            assert localNode != null : "Local node is null.";

            this.localNode = localNode;

            this.leader = new AtomicReference<>(leader);
        }

        @Override
        public ClusterNode getLeader() {
            return leader.get();
        }

        @Override
        public ClusterNode getLocalNode() {
            return localNode;
        }

        @Override
        public void addLeaderChangeListener(LeaderChangeListener listener) {
            ArgAssert.check(listener != null, "Listener is null.");

            listeners.add(listener);
        }

        @Override
        public boolean removeLeaderChangeListener(LeaderChangeListener listener) {
            if (listener != null) {
                return listeners.remove(listener);
            }

            return false;
        }

        void onLeaderChange(ClusterNode leader) {
            this.leader.set(leader);

            listeners.forEach(listener -> listener.onLeaderChange(this));
        }

        @Override
        public String toString() {
            return ToString.format(FollowerContext.class, this);
        }
    }

    private class DefaultLeaderContext implements LeaderContext {
        private boolean disposed;

        @Override
        public ClusterNode getLocalNode() {
            return localNode;
        }

        @Override
        public synchronized void yieldLeadership() {
            if (!disposed) {
                yieldLeadershipAsync();
            }
        }

        synchronized void dispose() {
            disposed = true;
        }

        @Override
        public String toString() {
            return ToString.format(LeaderContext.class, this);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CandidateHandler.class);

    private final String group;

    private final Candidate candidate;

    private final ExecutorService worker;

    private final DistributedLock lock;

    private final ClusterNode localNode;

    private DefaultLeaderContext leaderCtx;

    private DefaultFollowerContext followerCtx;

    private volatile LeaderFuture leaderFuture = new LeaderFuture();

    private volatile boolean terminated;

    public CandidateHandler(String group, Candidate candidate, ExecutorService worker, DistributedLock lock,
        ClusterNode localNode) {
        assert group != null : "Group name is null.";
        assert candidate != null : "Candidate is null.";
        assert worker != null : "Worker is null.";
        assert lock != null : "Lock is null.";
        assert localNode != null : "Local node is null.";

        this.group = group;
        this.candidate = candidate;
        this.worker = worker;
        this.lock = lock;
        this.localNode = localNode;
    }

    @Override
    public void onLockAcquire(DistributedLock lock) {
        if (!terminated) {
            if (log.isInfoEnabled()) {
                log.info("Switching to leader state [group={}, candidate={}]", group, candidate);
            }

            followerCtx = null;

            leaderCtx = new DefaultLeaderContext();

            try {
                candidate.becomeLeader(leaderCtx);
            } finally {
                updateLeaderFuture(localNode);
            }
        }
    }

    @Override
    public void onLockBusy(LockOwnerInfo owner) {
        if (!terminated) {
            ClusterNode leader = owner.getNode();

            if (log.isInfoEnabled()) {
                log.info("Switching to follower state [group={}, leader={}, candidate={}]", group, leader, candidate);
            }

            disposeLeader();

            followerCtx = new DefaultFollowerContext(leader, localNode);

            try {
                candidate.becomeFollower(followerCtx);
            } finally {
                updateLeaderFuture(leader);
            }
        }
    }

    @Override
    public void onLockOwnerChange(LockOwnerInfo owner) {
        if (!terminated) {
            ClusterNode oldLeader = followerCtx.getLeader();
            ClusterNode newLeader = owner.getNode();

            updateLeaderFuture(newLeader);

            if (log.isInfoEnabled()) {
                log.info("Leader changed [group={}, new={}, old={}, candidate={}]", group, oldLeader, newLeader, candidate);
            }

            followerCtx.onLeaderChange(newLeader);
        }
    }

    public void initialize() {
        lock.lockAsync(worker, this);
    }

    public Waiting terminate() {
        terminated = true;

        CountDownLatch done = new CountDownLatch(1);

        worker.execute(() -> {
            try {
                leaderFuture.cancel(false);

                boolean doTerminate = false;

                if (leaderCtx != null) {
                    doTerminate = true;

                    if (log.isInfoEnabled()) {
                        log.info("Stopping leader [group={}, candidate={}]", group, candidate);
                    }
                } else if (followerCtx != null) {
                    doTerminate = true;

                    if (log.isInfoEnabled()) {
                        log.info("Stopping follower [group={}, candidate={}]", group, candidate);
                    }
                }

                if (doTerminate) {
                    candidate.terminate();
                }
            } catch (RuntimeException | Error e) {
                log.error("Failed to execute election worker thread termination task.", e);
            } finally {
                followerCtx = null;

                disposeLeader();

                if (lock.isHeldByCurrentThread()) {
                    // Important to unlock asynchronously in order to prevent deadlock during services termination.
                    lock.unlockAsync();
                }

                done.countDown();
            }
        });

        return done::await;
    }

    public Waiting shutdown() {
        worker.execute(worker::shutdown);

        return () -> worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    public LeaderFuture getLeaderFuture() {
        return leaderFuture.fork();
    }

    private void updateLeaderFuture(ClusterNode leader) {
        LeaderFuture localFuture = this.leaderFuture;

        if (localFuture.isDone()) {
            localFuture = new LeaderFuture();

            localFuture.complete(leader);

            this.leaderFuture = localFuture;
        } else {
            localFuture.complete(leader);
        }
    }

    private void yieldLeadershipAsync() {
        worker.execute(() -> {
            if (!terminated && lock.isHeldByCurrentThread()) {
                if (log.isInfoEnabled()) {
                    log.info("Yielding leadership [group={}]", group);
                }

                leaderFuture = new LeaderFuture();

                disposeLeader();

                lock.unlock();

                lock.lockAsync(worker, this);
            }
        });
    }

    private void disposeLeader() {
        if (leaderCtx != null) {
            leaderCtx.dispose();

            leaderCtx = null;
        }
    }
}