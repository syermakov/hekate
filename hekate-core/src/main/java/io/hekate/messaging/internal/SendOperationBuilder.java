package io.hekate.messaging.internal;

import io.hekate.core.internal.util.ArgAssert;
import io.hekate.messaging.retry.RetryCallback;
import io.hekate.messaging.retry.RetryCondition;
import io.hekate.messaging.retry.RetryErrorPolicy;
import io.hekate.messaging.retry.RetryRoutingPolicy;
import io.hekate.messaging.unicast.Send;
import io.hekate.messaging.unicast.SendAckMode;
import io.hekate.messaging.unicast.SendFuture;
import io.hekate.messaging.unicast.SendRetryConfigurer;
import io.hekate.messaging.unicast.SendRetryPolicy;

class SendOperationBuilder<T> extends MessageOperationBuilder<T> implements Send<T>, SendRetryPolicy {
    private Object affinity;

    private SendAckMode ackMode;

    private RetryErrorPolicy retryErr;

    private RetryCondition retryCondition;

    private RetryCallback retryCallback;

    private RetryRoutingPolicy retryRoute = RetryRoutingPolicy.defaultPolicy();

    private int maxAttempts;

    public SendOperationBuilder(T message, MessagingGatewayContext<T> gateway, MessageOperationOpts<T> opts) {
        super(message, gateway, opts);
    }

    @Override
    public Send<T> withAffinity(Object affinity) {
        this.affinity = affinity;

        return this;
    }

    @Override
    public Send<T> withAckMode(SendAckMode ackMode) {
        ArgAssert.notNull(ackMode, "Acknowledgement mode");

        this.ackMode = ackMode;

        return this;
    }

    @Override
    public Send<T> withRetry(SendRetryConfigurer retry) {
        ArgAssert.notNull(retry, "Retry policy");

        // Make sure that by default we retry all errors.
        retryErr = RetryErrorPolicy.alwaysRetry();

        retry.configure(this);

        return this;
    }

    @Override
    public SendFuture submit() {
        SendOperation<T> op = new SendOperation<>(
            message(),
            affinity,
            maxAttempts,
            retryErr,
            retryCondition,
            retryCallback,
            retryRoute,
            gateway(),
            opts(),
            ackMode
        );

        gateway().submit(op);

        return op.future();
    }

    @Override
    public SendRetryPolicy route(RetryRoutingPolicy policy) {
        ArgAssert.notNull(policy, "Routing policy");

        this.retryRoute = policy;

        return this;
    }

    @Override
    public SendRetryPolicy whileTrue(RetryCondition condition) {
        this.retryCondition = condition;

        return this;
    }

    @Override
    public SendRetryPolicy whileError(RetryErrorPolicy policy) {
        this.retryErr = policy;

        return this;
    }

    @Override
    public SendRetryPolicy onRetry(RetryCallback callback) {
        this.retryCallback = callback;

        return this;
    }

    @Override
    public SendRetryPolicy maxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;

        return this;
    }
}
