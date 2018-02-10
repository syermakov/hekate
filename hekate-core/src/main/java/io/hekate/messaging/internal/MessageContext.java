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

package io.hekate.messaging.internal;

import io.hekate.util.format.ToString;
import io.hekate.util.format.ToStringIgnore;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static java.util.concurrent.atomic.AtomicIntegerFieldUpdater.newUpdater;
import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;

class MessageContext<T> {
    interface TimeoutListener {
        void onTimeout();
    }

    private static final AtomicIntegerFieldUpdater<MessageContext> COMPLETED = newUpdater(
        MessageContext.class,
        "completed"
    );

    private static final AtomicReferenceFieldUpdater<MessageContext, Future> TIMEOUT_FUTURE = newUpdater(
        MessageContext.class,
        Future.class,
        "timeoutFuture"
    );

    private final int affinity;

    private final Object affinityKey;

    private final boolean stream;

    private final T message;

    @ToStringIgnore
    private final MessagingWorker worker;

    @ToStringIgnore
    private final MessagingOpts<T> opts;

    @ToStringIgnore
    private volatile TimeoutListener timeoutListener;

    @ToStringIgnore
    @SuppressWarnings("unused") // <-- Updated via AtomicReferenceFieldUpdater.
    private volatile Future<?> timeoutFuture;

    @SuppressWarnings("unused") // <-- Updated via AtomicIntegerFieldUpdater.
    private volatile int completed;

    public MessageContext(T message, int affinity, Object affinityKey, MessagingWorker worker, MessagingOpts<T> opts, boolean stream) {
        assert message != null : "Message is null.";
        assert worker != null : "Worker is null.";
        assert opts != null : "Messaging options are null.";

        this.message = message;
        this.worker = worker;
        this.opts = opts;
        this.affinityKey = affinityKey;
        this.affinity = affinity;
        this.stream = stream;
    }

    public boolean hasAffinity() {
        return affinityKey != null;
    }

    public int affinity() {
        return affinity;
    }

    public Object affinityKey() {
        return affinityKey;
    }

    public boolean isStream() {
        return stream;
    }

    public T originalMessage() {
        return message;
    }

    public MessagingWorker worker() {
        return worker;
    }

    public MessagingOpts<T> opts() {
        return opts;
    }

    public boolean isCompleted() {
        return completed == 1;
    }

    public boolean complete() {
        boolean completed = doComplete();

        if (completed) {
            Future<?> localFuture = this.timeoutFuture;

            if (localFuture != null) {
                localFuture.cancel(false);
            }
        }

        return completed;
    }

    public boolean completeOnTimeout() {
        boolean completed = doComplete();

        if (completed) {
            if (timeoutListener != null) {
                timeoutListener.onTimeout();
            }
        }

        return completed;
    }

    public void setTimeoutListener(TimeoutListener timeoutListener) {
        assert timeoutListener != null : "Timeout listener is null.";
        assert opts.hasTimeout() : "Timeout listener can be set only for time-limited contexts.";

        this.timeoutListener = timeoutListener;

        if (isCompleted()) {
            timeoutListener.onTimeout();
        }
    }

    public void setTimeoutFuture(Future<?> timeoutFuture) {
        Future<?> oldFuture = TIMEOUT_FUTURE.getAndSet(this, timeoutFuture);

        if (oldFuture != null) {
            oldFuture.cancel(false);
        }
    }

    private boolean doComplete() {
        return COMPLETED.compareAndSet(this, 0, 1);
    }

    @Override
    public String toString() {
        return ToString.format(this);
    }
}
