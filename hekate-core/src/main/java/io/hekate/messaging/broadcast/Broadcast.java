package io.hekate.messaging.broadcast;

import io.hekate.messaging.Message;
import io.hekate.messaging.MessageReceiver;
import io.hekate.messaging.MessagingChannel;
import io.hekate.messaging.MessagingFutureException;
import io.hekate.messaging.unicast.SendAckMode;

/**
 * Broadcast operation.
 *
 * <p>
 * This interface represents a unidirectional broadcast operation. Typical use of this interface is:
 * </p>
 * <ol>
 * <li>Obtain an instance of this interface via the {@link MessagingChannel#newBroadcast(Object)} method call</li>
 * <li>Set options (f.e. {@link #withAckMode(SendAckMode) acknowledgement mode} or {@link #withAffinity(Object) affinity key})</li>
 * <li>Execute this operation via the {@link #submit()} method</li>
 * <li>Await for the execution result, if needed</li>
 * </ol>
 * <h3>Example:</h3>
 * ${source: messaging/MessagingServiceJavadocTest.java#broadcast_operation}
 *
 * @param <T> Message type.
 */
public interface Broadcast<T> {
    /**
     * Affinity key.
     *
     * <p>
     * Specifying an affinity key ensures that all operation with the same key will always be transmitted over the same network
     * connection and will always be processed by the same thread.
     * </p>
     *
     * @param affinity Affinity key.
     *
     * @return This instance.
     */
    Broadcast<T> withAffinity(Object affinity);

    /**
     * Acknowledgement mode.
     *
     * <p>
     * If this option is set to {@link SendAckMode#REQUIRED} then the receiver of this operation will send back an acknowledgement to
     * indicate that this operation was successfully {@link MessageReceiver#receive(Message) received}. In such case the operation's
     * callback/future will be notified only when such acknowledgement is received from all nodes (or if operation fails).
     * </p>
     *
     * <p>
     * If this option is set to {@link SendAckMode#NOT_NEEDED} then operation will be assumed to be successful once the message gets
     * flushed to the network buffer without any additional acknowledgements from receivers.
     * </p>
     *
     * <p>
     * Default value of this option is {@link SendAckMode#NOT_NEEDED}.
     * </p>
     *
     * @param ackMode Acknowledgement mode.
     *
     * @return This instance.
     */
    Broadcast<T> withAckMode(SendAckMode ackMode);

    /**
     * Retry policy.
     *
     * @param retry Retry policy.
     *
     * @return This instance.
     */
    Broadcast<T> withRetry(BroadcastRetryConfigurer retry);

    /**
     * Asynchronously executes this operation.
     *
     * @return Future result of this operation.
     */
    BroadcastFuture<T> submit();

    /**
     * Asynchronously executes this operation and notifies the specified callback upon completion.
     *
     * @param callback Callback.
     */
    default void submit(BroadcastCallback<T> callback) {
        submit().whenComplete((result, error) ->
            callback.onComplete(error, result)
        );
    }

    /**
     * Synchronously executes this operation and returns the result.
     *
     * @return Result.
     *
     * @throws MessagingFutureException If operations fails.
     * @throws InterruptedException If thread got interrupted while awaiting for this operation to complete.
     */
    default BroadcastResult<T> sync() throws MessagingFutureException, InterruptedException {
        return submit().get();
    }

    /**
     * Sets acknowledgement mode to {@link SendAckMode#REQUIRED}.
     *
     * @return This instance.
     *
     * @see #withAckMode(SendAckMode)
     */
    default Broadcast<T> withAck() {
        return withAckMode(SendAckMode.REQUIRED);
    }

    /**
     * Sets acknowledgement mode to {@link SendAckMode#NOT_NEEDED}.
     *
     * @return This instance.
     *
     * @see #withAckMode(SendAckMode)
     */
    default Broadcast<T> withNoAck() {
        return withAckMode(SendAckMode.NOT_NEEDED);
    }
}
