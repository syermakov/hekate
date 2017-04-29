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
import java.util.List;

/**
 * Cluster leave event.
 *
 * <p>
 * This event is fired by the {@link ClusterService} every time when local node leaves the cluster. For more details about the cluster
 * events processing please see the documentation of {@link ClusterEventListener} interface.
 * </p>
 *
 * @see ClusterEventListener
 */
public class ClusterLeaveEvent extends ClusterEventBase {
    private static final long serialVersionUID = 1;

    private final List<ClusterNode> added;

    private final List<ClusterNode> removed;

    /**
     * Constructs a new instance.
     *
     * @param topology Topology.
     * @param added List of newly joined nodes (see {@link #added()}).
     * @param removed List of nodes that left the cluster (see {@link #removed()}).
     */
    public ClusterLeaveEvent(ClusterTopology topology, List<ClusterNode> added, List<ClusterNode> removed) {
        super(topology);

        this.added = added;
        this.removed = removed;
    }

    /**
     * Returns the list of new nodes that joined the cluster.
     *
     * @return List of new nodes that joined the cluster.
     */
    public List<ClusterNode> added() {
        return added;
    }

    /**
     * Returns the list of nodes that left the cluster.
     *
     * @return List of nodes that left the cluster.
     */
    public List<ClusterNode> removed() {
        return removed;
    }

    /**
     * Returns {@link ClusterEventType#LEAVE}.
     *
     * @return {@link ClusterEventType#LEAVE}.
     */
    @Override
    public ClusterEventType type() {
        return ClusterEventType.LEAVE;
    }

    /**
     * Returns this instance.
     *
     * @return This instance.
     */
    @Override
    public ClusterLeaveEvent asLeave() {
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "[added=" + added()
            + ", removed=" + removed()
            + ", topology=" + topology()
            + ']';
    }
}
