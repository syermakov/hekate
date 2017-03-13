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

package io.hekate.cluster.event;

import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterService;
import io.hekate.cluster.ClusterTopology;
import io.hekate.core.Hekate;
import java.util.Set;

/**
 * Cluster topology change event.
 *
 * <p>
 * This event gets fired by the {@link ClusterService} every time whenever cluster topology changes are detected. This event includes
 * information about all new nodes that joined the cluster and all old nodes that left the cluster.
 * </p>
 *
 * <p>
 * For more details about cluster events handling please see the documentation of {@link ClusterEventListener} interface.
 * </p>
 *
 * @see ClusterEventListener
 */
public class ClusterChangeEvent extends ClusterEventBase {
    private static final long serialVersionUID = 1;

    private final Set<ClusterNode> added;

    private final Set<ClusterNode> removed;

    /**
     * Constructs a new instance.
     *
     * @param hekate {@link Hekate} instance where this event occurred.
     * @param topology Topology.
     * @param added Set of newly joined nodes (see {@link #getAdded()}).
     * @param removed Set of nodes that left the cluster (see {@link #getRemoved()}).
     */
    public ClusterChangeEvent(Hekate hekate, ClusterTopology topology, Set<ClusterNode> added, Set<ClusterNode> removed) {
        super(hekate, topology);

        this.added = added;
        this.removed = removed;
    }

    /**
     * Returns the set of new nodes that joined the cluster.
     *
     * @return Set of new nodes that joined the cluster.
     */
    public Set<ClusterNode> getAdded() {
        return added;
    }

    /**
     * Returns the set of nodes that left the cluster.
     *
     * @return Set of nodes that left the cluster.
     */
    public Set<ClusterNode> getRemoved() {
        return removed;
    }

    /**
     * Returns {@link ClusterEventType#CHANGE}.
     *
     * @return {@link ClusterEventType#CHANGE}.
     */
    @Override
    public ClusterEventType getType() {
        return ClusterEventType.CHANGE;
    }

    /**
     * Returns this instance.
     *
     * @return This instance.
     */
    @Override
    public ClusterChangeEvent asChange() {
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "[added=" + getAdded()
            + ", removed=" + getRemoved()
            + ", topology=" + getTopology()
            + ']';
    }
}