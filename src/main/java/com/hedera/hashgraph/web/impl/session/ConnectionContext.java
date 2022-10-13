package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.impl.*;
import com.hedera.hashgraph.web.impl.util.HttpInputStream;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Represents the data and logic associated with processing a request. The {@link ConnectionContext} represents
 * either a physical connection, or a logical connection. For example, in HTTP/2.0, a single physical connection
 * produces one or more "streams". Each "stream" can be thought of as a logical connection.
 * <p>
 * A {@link ConnectionContext} can be closed. When it represents a physical connection, closing the context will
 * close the underlying connection as well. When it represents a logical connection, closing the context will
 * not affect the underlying physical connection.
 * <p>
 * To help improve performance and decrease memory pressure, contexts are reusable. The {@link ContextReuseManager}
 * is responsible for pooling these resources.
 */
public abstract class ConnectionContext implements AutoCloseable {
    /**
     * The {@link ContextReuseManager} that manages this instance. This instance will be returned to the
     * {@link ContextReuseManager} when it is closed.
     */
    protected final ContextReuseManager contextReuseManager;

    /**
     * The {@link Dispatcher} to use for dispatching {@link com.hedera.hashgraph.web.WebRequest}s.
     */
    protected final Dispatcher dispatcher;

    /**
     * The buffered input for this connection. This is a single instance for the lifecycle of the context.
     * When this {@link ConnectionContext} is closed, the input stream is reset in anticipation of the next channel
     * to be serviced.
     */
    protected final HttpInputStream in;

    /**
     * Output buffer used for channel level sending of data, for HTTP 1 & 1.1 this can be used for responses as well for
     * HTTP 2 each response stream has its own buffer as well and this is just used for channel communications.
     * TODO Maybe move this to the child classes
     */
    protected final OutputBuffer outputBuffer;

    /**
     * The underlying {@link SocketChannel} that we use for reading and writing data.
     */
    protected SocketChannel channel;

    /**
     * TODO Not sure what this is used for yet
     */
    private Runnable onCloseCallback;

    /**
     * Keeps track of whether the instance is closed. Set to true in {@link #close()} and set to
     * false in {@link #resetWithNewChannel(SocketChannel, Runnable)}.
     */
    private boolean closed = true;

    /**
     * Create a new instance.
     *
     * @param contextReuseManager The {@link ContextReuseManager} that manages this instance. Must not be null.
     * @param dispatcher The {@link Dispatcher} to use for dispatching requests. Cannot be null.
     * @param bufferSize The size of the input buffer. A request, including any framing, <b>CANNOT</b>
     *                   exceed this limit.
     */
    protected ConnectionContext(
            final ContextReuseManager contextReuseManager,
            final Dispatcher dispatcher,
            int bufferSize) {
        this.contextReuseManager = Objects.requireNonNull(contextReuseManager);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.in = new HttpInputStream(bufferSize);
        this.outputBuffer = new OutputBuffer(bufferSize, onCloseCallback, () -> { });
    }

    /**
     * Resets the state of this channel before being reused. Called by the {@link ContextReuseManager} only.
     * Transitions this {@link ConnectionContext} to be open.
     *
     * @param channel The new channel to hold data for.
     */
    public final void resetWithNewChannel(SocketChannel channel, Runnable onCloseCallback) {
        closed = false;
        this.channel = Objects.requireNonNull(channel);
        this.onCloseCallback = Objects.requireNonNull(onCloseCallback);
        reset();
    }

    /**
     * Reset all state for reuse. Subclasses <b>MUST</b> call the super implementation.
     * edu.umd.cs.findbugs.annotations.OverrideMustInvoke
     */
    protected void reset() {
        in.reset();
        outputBuffer.reset();
    }

    @Override
    public void close() {
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
            this.in.reset();
            this.onCloseCallback.run();
        }
    }

    /**
     * Gets the channel associated with this context.
     * TODO Should we throw IllegalStateException if called on a closed context? Or something else?
     * @return The reference to the channel, or null if the context is closed.
     */
    public SocketChannel getChannel() {
        return channel;
    }

    /**
     * Gets the input stream from which data from the channel can be read.
     *
     * @return The stream. Never null.
     */
    public HttpInputStream getIn() {
        return in;
    }

    public OutputBuffer getOutputBuffer() {
        return outputBuffer;
    }

    public void sendOutputData() {
        sendOutputData(outputBuffer);
    }

    public void sendOutputData(OutputBuffer outputBuffer) {
        try {
            outputBuffer.sendContentsToChannel(channel);
        } catch (IOException e) {
            throw new RuntimeException(e); // TODO
        }
    }

    /**
     * Called to process the request as far as it can with the given available data in the input stream.
     *
     * @param onConnectionUpgrade A callback to be invoked if the context needs to be upgraded. For example,
     *                            the HTTP/1.1 context may invoke this to upgrade to HTTP/2.0.
     */
    public abstract void handle(final Consumer<HttpVersion> onConnectionUpgrade);
}
