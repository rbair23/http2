package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.impl.DataHandler;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Represents the data and logic associated with processing a request based on a physical connection.
 *
 * <p>To help improve performance and decrease memory pressure, contexts are reusable. The {@link ContextReuseManager}
 * is responsible for pooling these resources. Instead of creating a context directly, always fetch an instance
 * from the {@link ContextReuseManager} and, when the connection is closed, return the instance to the manager.
 *
 * <p>Closing the context will close the underlying connection.
 *
 * <p>Each context maintains an "input buffer". As the {@link DataHandler} is alerted to bytes on the underlying
 * channel for a connection, it delegates to the {@link ConnectionContext} to pull those bytes and add them to the
 * input buffer. The context has its own state machine (which differs between HTTP/1.1 and HTTP/2) and, based
 * on the bytes in the input buffer, will advance the state machine. For successful connections, this eventually
 * results in a request being created and dispatched.
 *
 * <p>Each connection also has the ability to write bytes to the underlying channel. Each request buffers up
 * the contents to send to the channel, and then sends them as a complete {@link ByteBuffer} of data.
 */
public abstract class ConnectionContext implements AutoCloseable {

    /**
     * The {@link ContextReuseManager} that manages this instance. This instance will be returned to the
     * {@link ContextReuseManager} when it is closed.
     */
    protected final ContextReuseManager contextReuseManager;

    /**
     * The buffered input for this connection. This is a single instance for the lifecycle of the context.
     * When this {@link ConnectionContext} is closed, the input buffer is init in anticipation of the next channel
     * to be serviced.
     */
    protected final InputBuffer inputBuffer;

    /**
     * The queue of buffered output for this connection. TODO from here:: This is a single instance for the lifecycle of the context.
     * When this {@link ConnectionContext} is closed, the output buffer is init in anticipation of the next channel
     * to be serviced. The contents of this buffer are for the communication that belongs to the connection.
     * In HTTP/2.0, these would be connection frames. Stream frames would be in their own buffers.
     */
    private final ConcurrentLinkedQueue<OutputBuffer> waitingForWriteOutputBufferQueue = new ConcurrentLinkedQueue<>();

    /**
     * The underlying {@link ByteChannel} that we use for reading and writing data.
     */
    protected ByteChannel channel;

    /**
     * A callback invoked when the context is closed.
     * TODO Verify whether this is needed.
     */
    private Runnable onClose;

    /**
     * Keeps track of whether the instance is closed. Set to true in {@link #close()} and set to
     * false in {@link #reset(ByteChannel, Runnable)}.
     */
    private boolean closed = true;

    /**
     * Create a new instance.
     *
     * @param contextReuseManager The {@link ContextReuseManager} that manages this instance. Must not be null.
     * @param bufferSize The size of the input and output buffers. A request, including any framing, <b>CANNOT</b>
     *                   exceed this limit.
     */
    protected ConnectionContext(final ContextReuseManager contextReuseManager, final int bufferSize) {
        this.contextReuseManager = Objects.requireNonNull(contextReuseManager);
        this.inputBuffer = new InputBuffer(bufferSize);
    }

    /**
     * Called to process the request as far as it can with the given available data in the input stream.
     *
     * @param onConnectionUpgrade A callback to be invoked if the context needs to be upgraded. For example,
     *                            the HTTP/1.1 context may invoke this to upgrade to HTTP/2.0.
     * @return If handle has read all data, left data still to read or wants the connection to be closed.
     */
    public final HandleResponse handleIncomingData(final Consumer<HttpVersion> onConnectionUpgrade) {
        try {
            // Put the data into the input stream
            final var dataRemains = inputBuffer.addData(channel);
            final var doHandleResponse = doHandle(onConnectionUpgrade);
            if (doHandleResponse == HandleResponse.CLOSE_CONNECTION) {
                close();
                return HandleResponse.CLOSE_CONNECTION;
            } else if (dataRemains || doHandleResponse == HandleResponse.DATA_STILL_TO_HANDLED) {
                return HandleResponse.DATA_STILL_TO_HANDLED;
            } else {
                return HandleResponse.ALL_DATA_HANDLED;
            }
        } catch (IOException e) {
            // The underlying channel is closed, we need to clean things up
            e.printStackTrace(); // LOGGING: Need to log this, maybe as trace or debug
            close();
            return HandleResponse.CLOSE_CONNECTION;
        } catch (FailedFlushException e) {
            // We failed to flush, most likely because the channel is closed. So close the
            // connection and give up.
            close();
            return HandleResponse.CLOSE_CONNECTION;
        }
    }

    /**
     * Write as much pending data to the channel as the channel will accept. Returning ALL_DATA_HANDLED if all pending
     * data was written to channel or DATA_STILL_TO_HANDLED if channel would not accept all data without blocking.
     *
     * @return if app pending data was written or not or we are done with channel, and it can be closed.
     */
    public final HandleResponse handleOutgoingData() {
        try {
            while (!waitingForWriteOutputBufferQueue.isEmpty()) {
                final OutputBuffer outputBuffer = waitingForWriteOutputBufferQueue.peek();
                final ByteBuffer buffer = outputBuffer.getBuffer();
                // flip the buffer so it is ready to be written out
                buffer.flip();

                channel.write(buffer);
                if (buffer.hasRemaining()) {
                    // not all data was written
                    return HandleResponse.DATA_STILL_TO_HANDLED;
                }
                // all data was written, so we can remove that buffer and return to pool
                contextReuseManager.returnOutputBuffer(waitingForWriteOutputBufferQueue.remove());
            }
        } catch (IOException e) {
            e.printStackTrace();
            return HandleResponse.CLOSE_CONNECTION;
        }
        return HandleResponse.ALL_DATA_HANDLED;
    }

    /**
     * Queue the provided buffer to be sent. The buffer will be fliped and all bytes between the start of buffer and
     * current position will be written to the channel next time the channel is ready to accept bytes.
     * <br>
     * This method can be called form any thead
     *
     * @param buffer The un-flipped buffer to write
     */
    public void sendOutput(OutputBuffer buffer) {
        waitingForWriteOutputBufferQueue.add(buffer);
    }

    /**
     * Called to process the request as far as it can with the given available data in the input stream.
     * The super class has already pushed all that data it could into the buffer.
     *
     * @param onConnectionUpgrade A callback to be invoked if the context needs to be upgraded. For example,
     *                            the HTTP/1.1 context may invoke this to upgrade to HTTP/2.0.
     * @return If handle has read all data, left data still to read or wants the connection to be closed.
     */
    protected abstract HandleResponse doHandle(final Consumer<HttpVersion> onConnectionUpgrade);

    /**
     * Resets the state of this channel before being reused. Called by the {@link ContextReuseManager} only.
     * Transitions this {@link ConnectionContext} to be open.
     *
     * <p>edu.umd.cs.findbugs.annotations.OverrideMustInvoke
     * @param channel The new channel to hold data for.
     */
    public synchronized void reset(ByteChannel channel, Runnable onCloseCallback) {
        closed = false;
        this.channel = Objects.requireNonNull(channel);
        this.onClose = onCloseCallback;
        this.inputBuffer.reset();
        this.waitingForWriteOutputBufferQueue.clear();
    }

    protected boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        System.out.println("ConnectionContext.close");
        if (!closed) {
            this.closed = true;

            try {
                // TODO We do NOT want to do this for Http2RequestContext, but we do want those contexts to
                //      transition the "closed" flag.
                this.channel.close();
            } catch (IOException ignored) {
                // TODO Maybe a warning? Or an info? Or maybe just debug logging.
            }

            this.channel = null;
            this.inputBuffer.reset();
            this.waitingForWriteOutputBufferQueue.clear();

            if (this.onClose != null) {
                this.onClose.run();
            }
        }
    }

    /**
     * Gets the channel associated with this context.
     * @return The reference to the channel, or null if the context is closed.
     * @throws IllegalStateException if the context has already been closed
     */
    protected ByteChannel getChannel() {
        if (closed) {
            throw new IllegalStateException("The context has already been closed");
        }

        return channel;
    }

    protected void upgrade(ConnectionContext prev) {
        this.channel = prev.getChannel();
        this.inputBuffer.init(prev.inputBuffer);
    }
}
