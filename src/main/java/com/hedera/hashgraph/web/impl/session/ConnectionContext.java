package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.impl.*;
import com.hedera.hashgraph.web.impl.http.HttpProtocol;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Represents the data needed on a per-channel basis. These objects are meant to be reused for future channels, to cut
 * down on garbage collector pressure. Creation of these objects is controlled
 * by {@link ContextReuseManager}. When the object is closed, it will shut down the channel
 * and then clean itself up a bit and put itself back into the idleChannelData list.
 * <p>
 * Each channel has associated with it two streams -- an input stream from which bytes on the channel are
 * made available, and an output stream into which bytes are to be sent to the client though the channel.
 * Each channel also has a single associated {@link ProtocolBase}. All channels start of with the
 * {@link HttpProtocol}, but may be upgraded to use an {@link Http2ConnectionContext} if the original
 * protocol detects it. When the channel data object is closed, it goes back to using the HTTP protocol handler.
 * <p>
 * There is some ugliness here because we have two ways of using {@link RequestSession} here. For HTTP 1.0 and
 * 1.1, we can reuse a single {@link RequestSession} instance. For HTTP 2.0, we need to use a map of them,
 * because there is one per stream, and they are key'd by stream ID (an integer). Since this single instance
 * of {@link ConnectionContext} is intended to be used for both, we have to provide both the "single stream data"
 * request data instance and a (potentially empty) map of them.
 * <p>
 * When the {@link ConnectionContext} is closed, all {@link RequestSession} instances are closed and returned to the list of
 * idle instances.
 */
public abstract class ConnectionContext implements AutoCloseable {
    /**
     * Null when this instance is in use or when it is the last item in the list, otherwise points to the
     * next idle instance in the idle chain.
     */
    protected ConnectionContext next;

    protected final ContextReuseManager contextReuseManager;

    protected final Dispatcher dispatcher;

    /**
     * A single instance that lives with this instance. When this {@link ConnectionContext} is closed, the
     * input stream is reset in anticipation of the next channel to be serviced.
     */
    protected final HttpInputStream in;

    /**
     * Output buffer used for channel level sending of data, for HTTP 1 & 1.1 this can be used for responses as well for
     * HTTP 2 each response stream has its own buffer as well and this is just used for channel communications.
     */
    protected final OutputBuffer outputBuffer;

    /**
     * The underlying {@link SocketChannel} that we use for reading and writing data.
     */
    protected SocketChannel channel;


    private Runnable onCloseCallback;

    /**
     * Keeps track of whether the instance is closed. Set to true in {@link #close()} and set to
     * false in {@link #init(SocketChannel, HttpProtocol, Runnable)}.
     */
    private boolean closed = true;

    /**
     * Create a new instance.
     */
    protected ConnectionContext(ContextReuseManager contextReuseManager, Dispatcher dispatcher) {
        this.contextReuseManager = contextReuseManager;
        this.dispatcher = dispatcher;
        this.in = new HttpInputStream(16*1024);
        this.outputBuffer = new OutputBuffer(onCloseCallback, onDataFullCallback);
    }

    /**
     * Resets the state of this channel before being reused. initialize
     *
     * @param channel The new channel to hold data for.
     */
    protected final void resetWithNewChannel(SocketChannel channel, Runnable onCloseCallback) {
        closed = false;
        this.channel = Objects.requireNonNull(channel);
        this.onCloseCallback = Objects.requireNonNull(onCloseCallback);
        reset();
    }

    /**
     * Reset all state for reuse
     */
    protected void reset() {
        outputBuffer.reset();
    }

    @Override
    public void close() {
        if (!closed) {
            this.closed = true;

            try {
                this.channel.close();
            } catch (IOException ignored) {
                // TODO Maybe a warning? Or an info? Or maybe just debug logging.
            }

            this.channel = null;
            this.in.reset();


            contextReuseManager.returnChannelSession(this);

            this.onCloseCallback.run();
        }
    }

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

    public abstract void handle(Consumer<HttpVersion> upgradeConnectionCallback);
}
