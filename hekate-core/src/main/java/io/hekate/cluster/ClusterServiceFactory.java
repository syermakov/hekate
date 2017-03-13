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

package io.hekate.cluster;

import io.hekate.cluster.event.ClusterEvent;
import io.hekate.cluster.event.ClusterEventListener;
import io.hekate.cluster.health.DefaultFailureDetector;
import io.hekate.cluster.health.FailureDetector;
import io.hekate.cluster.internal.DefaultClusterService;
import io.hekate.cluster.internal.gossip.GossipListener;
import io.hekate.cluster.seed.SeedNodeProvider;
import io.hekate.cluster.seed.multicast.MulticastSeedNodeProvider;
import io.hekate.cluster.split.SplitBrainAction;
import io.hekate.cluster.split.SplitBrainDetector;
import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.core.internal.util.ConfigCheck;
import io.hekate.core.service.ServiceFactory;
import io.hekate.util.StateGuard;
import io.hekate.util.format.ToString;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory for {@link ClusterService}.
 *
 * <p>
 * This class represents a configurable factory for {@link ClusterService}. Instances of this class must be {@link
 * HekateBootstrap#withService(ServiceFactory) registered} within the {@link HekateBootstrap} in order to make {@link ClusterService}
 * accessible via {@link Hekate#get(Class)} method.
 * </p>
 *
 * <p>
 * For more details about the {@link ClusterService} and its capabilities please see the documentation of {@link ClusterService} interface.
 * </p>
 */
public class ClusterServiceFactory implements ServiceFactory<ClusterService> {
    /** Default value (={@value}) for {@link #setGossipInterval(long)}. */
    public static final long DEFAULT_GOSSIP_INTERVAL = 1000;

    /** Default value (={@value}) for {@link #setSpeedUpGossipSize(int)}. */
    public static final int DEFAULT_SPEED_UP_SIZE = 100;

    private SplitBrainAction splitBrainAction = SplitBrainAction.TERMINATE;

    private SplitBrainDetector splitBrainDetector;

    private SeedNodeProvider seedNodeProvider;

    private FailureDetector failureDetector = new DefaultFailureDetector();

    private List<ClusterEventListener> clusterListeners;

    private List<ClusterJoinValidator> joinValidators;

    private long gossipInterval = DEFAULT_GOSSIP_INTERVAL;

    private int speedUpGossipSize = DEFAULT_SPEED_UP_SIZE;

    private StateGuard serviceGuard;

    private GossipListener gossipSpy;

    /**
     * Returns seed node provider (see {@link #setSeedNodeProvider(SeedNodeProvider)}).
     *
     * @return Seed node provider.
     */
    public SeedNodeProvider getSeedNodeProvider() {
        return seedNodeProvider;
    }

    /**
     * Sets seed node provider that should be used to discover existing cluster nodes when local node starts joining to a cluster.
     *
     * <p>
     * By default this property is initialized with {@link MulticastSeedNodeProvider} instance. Note that this requires multicasting to be
     * enabled on the target platform.
     * </p>
     *
     * @param seedNodeProvider Seed node provider.
     *
     * @see SeedNodeProvider
     */
    public void setSeedNodeProvider(SeedNodeProvider seedNodeProvider) {
        this.seedNodeProvider = seedNodeProvider;
    }

    /**
     * Fluent-style version of {@link #setSeedNodeProvider(SeedNodeProvider)}.
     *
     * @param seedNodeProvider Seed node provider.
     *
     * @return This instance.
     */
    public ClusterServiceFactory withSeedNodeProvider(SeedNodeProvider seedNodeProvider) {
        setSeedNodeProvider(seedNodeProvider);

        return this;
    }

    /**
     * Returns the failure detector (see {@link #setFailureDetector(FailureDetector)}).
     *
     * @return Failure detector.
     */
    public FailureDetector getFailureDetector() {
        return failureDetector;
    }

    /**
     * Sets the failure detector that should be used for health checking of remote nodes.
     *
     * <p>
     * By default this property is initialized with a {@link DefaultFailureDetector} instance.
     * </p>
     *
     * @param failureDetector Failure detector.
     *
     * @see FailureDetector
     */
    public void setFailureDetector(FailureDetector failureDetector) {
        this.failureDetector = failureDetector;
    }

    /**
     * Fluent-style version of {@link #setSeedNodeProvider(SeedNodeProvider)}.
     *
     * @param failureDetector Failure detector.
     *
     * @return This instance.
     */
    public ClusterServiceFactory withFailureDetector(FailureDetector failureDetector) {
        setFailureDetector(failureDetector);

        return this;
    }

    /**
     * Returns the split-brain action (see {@link #setSplitBrainAction(SplitBrainAction)}).
     *
     * @return Split-brain action.
     */
    public SplitBrainAction getSplitBrainAction() {
        return splitBrainAction;
    }

    /**
     * Sets the split-brain action.
     *
     * <p>
     * This parameter specifies which actions should be performed if cluster
     * <a href="https://en.wikipedia.org/wiki/Split-brain_(computing)" target="_blank">split-brain</a> is detected. Split-brain can happen
     * if other cluster members decided that this node is not reachable (due to some networking problems or long GC pauses).
     * In such case they will remove this node from their topology while this node will think that it is still a member of the cluster.
     * </p>
     *
     * <p>
     * Default value of this parameter is {@link SplitBrainAction#TERMINATE}.
     * </p>
     *
     * <p>
     * <b>Note:</b> Actual split-brain detection is performed by a split-brain detector component
     * (see {@link #setSplitBrainDetector(SplitBrainDetector)}).
     * </p>
     *
     * @param splitBrainAction Action.
     *
     * @see #setSplitBrainDetector(SplitBrainDetector)
     */
    public void setSplitBrainAction(SplitBrainAction splitBrainAction) {
        ConfigCheck.get(getClass()).that(splitBrainAction != null, "Split-brain policy must be not null.");

        this.splitBrainAction = splitBrainAction;
    }

    /**
     * Fluent-style version of {@link #setSplitBrainAction(SplitBrainAction)}.
     *
     * @param splitBrainAction Action.
     *
     * @return This instance.
     */
    public ClusterServiceFactory withSplitBrainAction(SplitBrainAction splitBrainAction) {
        setSplitBrainAction(splitBrainAction);

        return this;
    }

    /**
     * Returns the cluster split-brain detector (see {@link #setSplitBrainDetector(SplitBrainDetector)}).
     *
     * @return Cluster split-brain detector.
     */
    public SplitBrainDetector getSplitBrainDetector() {
        return splitBrainDetector;
    }

    /**
     * Sets the cluster split-brain detector.
     *
     * <p>
     * <a href="https://en.wikipedia.org/wiki/Split-brain_(computing)" target="_blank">Split-brain</a> can happen if other cluster members
     * decided that this node is not reachable (due to some networking problems or long GC pauses). In such case they will remove this node
     * from their topology while this node will think that it is still a member of the cluster. This component is responsible for checking
     * if local node is reachable by other cluster nodes.
     * </p>
     *
     * <p>
     * If this component detects that local node is not reachable then {@link #setSplitBrainAction(SplitBrainAction) split-brain action}
     * will be applied to the local node.
     * </p>
     *
     * @param splitBrainDetector Cluster split-brain detector.
     *
     * @see #setSplitBrainAction(SplitBrainAction)
     */
    public void setSplitBrainDetector(SplitBrainDetector splitBrainDetector) {
        this.splitBrainDetector = splitBrainDetector;
    }

    /**
     * Fluent-style version of {@link #setSplitBrainDetector(SplitBrainDetector)}.
     *
     * @param splitBrainDetector Cluster split-brain detector.
     *
     * @return This instance.
     */
    public ClusterServiceFactory withSplitBrainDetector(SplitBrainDetector splitBrainDetector) {
        setSplitBrainDetector(splitBrainDetector);

        return this;
    }

    /**
     * Returns a list of cluster event listeners (see {@link #setClusterListeners(List)}).
     *
     * @return List of cluster event listeners.
     */
    public List<ClusterEventListener> getClusterListeners() {
        return clusterListeners;
    }

    /**
     * Sets a list of cluster event listeners to be notified upon {@link ClusterEvent}.
     *
     * @param clusterListeners Cluster event listeners.
     */
    public void setClusterListeners(List<ClusterEventListener> clusterListeners) {
        this.clusterListeners = clusterListeners;
    }

    /**
     * Adds the specified listener to a {@link #setClusterListeners(List) cluster event listeners list}.
     *
     * @param listener Cluster event listener.
     *
     * @return This instance.
     */
    public ClusterServiceFactory withClusterListener(ClusterEventListener listener) {
        if (clusterListeners == null) {
            clusterListeners = new ArrayList<>();
        }

        clusterListeners.add(listener);

        return this;
    }

    /**
     * Returns a list of cluster join validators (see {@link #setJoinValidators(List)}).
     *
     * @return List of cluster join validators.
     */
    public List<ClusterJoinValidator> getJoinValidators() {
        return joinValidators;
    }

    /**
     * Sets the list of cluster join validators.
     *
     * <p>
     * Cluster join validators are intended for implementing a custom logic of accepting/rejecting new cluster
     * nodes based on some application-specific criteria (f.e. configuration compatibility, authorization, etc).
     * For more info please see description of the {@link ClusterJoinValidator} interface.
     * </p>
     *
     * @param joinValidators List of cluster join validators.
     *
     * @see ClusterJoinValidator
     */
    public void setJoinValidators(List<ClusterJoinValidator> joinValidators) {
        this.joinValidators = joinValidators;
    }

    /**
     * Adds the specified validator to a {@link #setJoinValidators(List) cluster join validators list}.
     *
     * @param validator Cluster join validator.
     *
     * @return This instance.
     */
    public ClusterServiceFactory withJoinValidator(ClusterJoinValidator validator) {
        if (joinValidators == null) {
            joinValidators = new ArrayList<>();
        }

        joinValidators.add(validator);

        return this;
    }

    /**
     * Returns the time interval in millisecond between gossip rounds (see {@link #setGossipInterval(long)}).
     *
     * @return The time interval in millisecond between gossip rounds.
     */
    public long getGossipInterval() {
        return gossipInterval;
    }

    /**
     * Sets the time interval in milliseconds between gossip rounds.
     *
     * <p>
     * During each round the local node will exchange its topology view with a set of randomly selected remote nodes in order to make
     * sure that topology view is consistent across the whole cluster.
     * </p>
     *
     * <p>
     * Value of this parameter must be greater than zero.
     * </p>
     *
     * <p>
     * Default value of this parameter is {@value #DEFAULT_GOSSIP_INTERVAL}.
     * </p>
     *
     * @param gossipInterval The time interval in milliseconds between gossip rounds.
     */
    public void setGossipInterval(long gossipInterval) {
        this.gossipInterval = gossipInterval;
    }

    /**
     * Fluent-style version of {@link #setGossipInterval(long)}.
     *
     * @param gossipInterval The time interval in milliseconds between gossip rounds.
     *
     * @return This instance.
     */
    public ClusterServiceFactory withGossipInterval(long gossipInterval) {
        setGossipInterval(gossipInterval);

        return this;
    }

    /**
     * Returns the maximum amount of nodes in the cluster when gossip protocol can be speeded up by sending messages at a higher rate so
     * that cluster could converge faster (see {@link #setSpeedUpGossipSize(int)}).
     *
     * @return The maximum amount of nodes in the cluster when gossip protocol can be speeded up.
     */
    public int getSpeedUpGossipSize() {
        return speedUpGossipSize;
    }

    /**
     * Sets the maximum amount of nodes in the cluster when gossip protocol can be speeded up by sending messages at a higher rate so that
     * cluster could converge faster.
     *
     * <p>
     * If this parameter is set to a positive value and the current cluster size is less than the specified value then local node will send
     * gossip messages at higher rate in order to speed-up cluster convergence. However this can highly increase resources utilization and
     * should be used in a cluster of relatively small size.
     * </p>
     *
     * <p>
     * Default value of this parameter is {@value #DEFAULT_SPEED_UP_SIZE}.
     * </p>
     *
     * @param speedUpGossipSize The maximum amount of nodes in the cluster when gossip protocol can be speeded up.
     */
    public void setSpeedUpGossipSize(int speedUpGossipSize) {
        this.speedUpGossipSize = speedUpGossipSize;
    }

    /**
     * Fluent-style version of {@link #setSpeedUpGossipSize(int)}.
     *
     * @param speedUpSize The maximum amount of nodes in the cluster when gossip protocol can be speeded up.
     *
     * @return This instance.
     */
    public ClusterServiceFactory withSpeedUpGossipSize(int speedUpSize) {
        setSpeedUpGossipSize(speedUpSize);

        return this;
    }

    @Override
    public ClusterService createService() {
        return new DefaultClusterService(this, serviceGuard, gossipSpy);
    }

    // Package level for testing purposes.
    GossipListener getGossipSpy() {
        return gossipSpy;
    }

    // Package level for testing purposes.
    void setGossipSpy(GossipListener gossipSpy) {
        this.gossipSpy = gossipSpy;
    }

    // Package level for testing purposes.
    StateGuard getServiceGuard() {
        return serviceGuard;
    }

    // Package level for testing purposes.
    void setServiceGuard(StateGuard serviceGuard) {
        this.serviceGuard = serviceGuard;
    }

    @Override
    public String toString() {
        return ToString.format(this);
    }
}