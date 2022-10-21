package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.impl.ChannelManager;
import com.hedera.hashgraph.web.impl.http2.frames.Settings;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
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
 * <p>Each context maintains an "input buffer". As the {@link ChannelManager} is alerted to bytes on the underlying
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
    private ByteChannel channel;

    /**
     * A callback invoked when the context is closed. It is passed a boolean, true indicating context is fully closed or
     * false indicating the context is currently closing but not all pending data has been sent yet.
     */
    private BiConsumer<Boolean, ConnectionContext> onClose;

    /**
     * Keeps track of whether the instance is closed. Set to true in {@link #close()} and set to
     * false in {@link #reset(ByteChannel, BiConsumer)}.
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
            // TODO should we be sending a 500 response?
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
                // flip the buffer, so it is ready to be written out
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

        return closed ? HandleResponse.CLOSE_CONNECTION : HandleResponse.ALL_DATA_HANDLED;
    }

    /**
     * Queue the provided buffer to be sent. The buffer will be fliped and all bytes between the start of buffer and
     * current position will be written to the channel next time the channel is ready to accept bytes.
     * <br>
     * This method can be called form any thead
     *
     * TODO Are we happy just doing nothing if closed or should we throw exception?
     *
     * @param buffer The un-flipped buffer to write
     */
    public final void sendOutput(OutputBuffer buffer) {
        if (!isClosed()) {
            waitingForWriteOutputBufferQueue.add(buffer);
        }
    }

    /**
     * Check if the client is being good sending data and no timeouts have occurred. This method needs to be very fast
     * as it is called from busy loop.
     *
     * @return true if everything is good or false if the connection has been terminated.
     */
    public boolean checkClientIsWellBehaving() {
        return true; // TODO implement common logic here and sub classes can add their own
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
     * @param onCloseCallback   callback invoked when the context is closed. It is passed a boolean, true indicating
     *                          context is fully closed or false indicating the context is currently closing but not
     *                          all pending data has been sent yet.
     */
    public synchronized void reset(ByteChannel channel, BiConsumer<Boolean, ConnectionContext> onCloseCallback) {
        closed = false;
        this.channel = Objects.requireNonNull(channel);
        this.onClose = onCloseCallback;
        this.inputBuffer.reset();
        this.waitingForWriteOutputBufferQueue.clear();
    }

    /**
     * Transfer the state from another ConnectionContext of a different version to this one.
     *
     * @param prev The previous connection context you want to copy state from
     */
    protected void upgrade(ConnectionContext prev) {
        Objects.requireNonNull(prev);
        if (prev.isClosed() || prev.isTerminated()) {
            throw new IllegalStateException(" You can not upgrade a closed or terminated connection context.");
        }
        this.channel = prev.channel;
        this.inputBuffer.init(prev.inputBuffer);
    }

    /**
     * Is this connection context closed, closed means it will no longer be read new data and will not accept new data
     * but will carry on sending buffered data till either it is all sent or the connection has been closed by the
     * client or calling terminate method.
     * @return if this context is closed, will be true if closed or terminated
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Close this context, this will finish writing all buffered data then close channel to client and clean up all
     * resources.
     */
    @Override
    public void close() {
        System.out.println("ConnectionContext.close");
        if (!closed) {
            this.closed = true;
            if (!channel.isOpen()) {
                // the channel is already closed so just close immediately as we can not finish sending data
                terminate();
            }
        } else {
            // notify callback that we are starting closing
            if (this.onClose != null) {
                this.onClose.accept(true, this);
            }
        }
    }

    /**
     * Is this connection context terminated, it is terminated when the channel is closed and no further communication
     * is possible.
     *
     * @return True if terminated
     */
    public boolean isTerminated() {
        return channel == null | !channel.isOpen();
    }

    /**
     * Terminate closes the channel and ends all communication without sending any buffered data or doing any more work
     */
    protected void terminate() {
        if (channel.isOpen()) {
            try {
                this.channel.close();
            } catch (IOException ignored) {
                // TODO Maybe a warning? Or an info? Or maybe just debug logging.
                ignored.printStackTrace();
            }
        }
        this.channel = null;
        this.inputBuffer.reset();
        this.waitingForWriteOutputBufferQueue.clear();
        // notify callback that we are fully closed
        if (this.onClose != null) {
            this.onClose.accept(true, this);
        }
    }
}
