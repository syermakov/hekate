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

package io.hekate.lock.internal;

import io.hekate.cluster.ClusterNodeId;
import io.hekate.util.format.ToString;

class LockMigrationInfo implements LockIdentity {
    private final String name;

    private final long lockId;

    private final ClusterNodeId node;

    private final long threadId;

    public LockMigrationInfo(String name, long lockId, ClusterNodeId node, long threadId) {
        this.name = name;
        this.lockId = lockId;
        this.node = node;
        this.threadId = threadId;
    }

    public String name() {
        return name;
    }

    @Override
    public long lockId() {
        return lockId;
    }

    @Override
    public ClusterNodeId node() {
        return node;
    }

    @Override
    public long threadId() {
        return threadId;
    }

    @Override
    public String toString() {
        return ToString.format(this);
    }
}
