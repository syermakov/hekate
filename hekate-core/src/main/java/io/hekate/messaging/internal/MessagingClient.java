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

package io.hekate.messaging.internal;

import io.hekate.cluster.ClusterNode;
import io.hekate.messaging.unicast.SendCallback;
import io.hekate.network.NetworkFuture;
import java.util.List;

interface MessagingClient<T> {
    ClusterNode getNode();

    void send(MessageContext<T> ctx, SendCallback callback);

    void request(MessageContext<T> ctx, InternalRequestCallback<T> callback);

    void disconnectIfIdle();

    void touch();

    List<NetworkFuture<MessagingProtocol>> close();

    boolean isConnected();
}