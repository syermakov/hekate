/*
 * Copyright 2021 The Hekate Project
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

package io.hekate.messaging.intercept;

import io.hekate.messaging.MessageMetaData;

/**
 * Client's send context.
 *
 * @param <T> Message type.
 *
 * @see MessageInterceptor
 */
public interface ClientSendContext<T> extends ClientOutboundContext<T> {
    /**
     * Returns the message's meta-data.
     *
     * @return Message meta-data.
     */
    MessageMetaData metaData();

    /**
     * Overrides the message to be sent with the specified one.
     *
     * @param msg New message that should be sent instead of the original one.
     */
    void overrideMessage(T msg);

    /**
     * Sets an attribute of this context.
     *
     * <p>
     * Attributes are local to this context object and do not get transferred to a remote peer.
     * </p>
     *
     * @param name Name.
     * @param value Value.
     *
     * @return Previous value or {@code null} if attribute didn't have any value.
     *
     * @see #getAttribute(String)
     */
    Object setAttribute(String name, Object value);

    /**
     * Returns {@code true} if message has a non-empty {@link #metaData()}.
     *
     * @return {@code true} if message has a non-empty {@link #metaData()}.
     */
    boolean hasMetaData();

}
