package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.ChannelManager;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http.Http1ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionImpl;
import com.hedera.hashgraph.web.impl.http2.Http2Stream;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Responsible for managing reusable contexts
 */
public final class ContextReuseManager {

    /**
     * The dispatcher to use for dispatching requests. Each of the contexts need this reference,
     * and it never changes for the life of the {@link ContextReuseManager}, so we just take the
     * reference at start and reuse it.
     */
    private final Dispatcher dispatcher;

    /**
     * The {@link WebServerConfig} for this running server instance.
     */
    private final WebServerConfig config;

    /**
     * Each channel has some associated data that we need to maintain. When in use, the channel data is
     * stored on the {@link java.nio.channels.SelectionKey} of the channel. When no longer in use, we want
     * to keep reference to it, so we can reuse it later. So we have a linked-list of unused {@link ConnectionContext}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     */
    private final IdleList<Http1ConnectionContext> idleHttp1ChannelContexts = new IdleList<>();

    /**
     * Each channel has some associated data that we need to maintain. When in use, the channel data is
     * stored on the {@link java.nio.channels.SelectionKey} of the channel. When no longer in use, we want
     * to keep reference to it, so we can reuse it later. So we have a linked-list of unused {@link ConnectionContext}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     */
    private final IdleList<Http2ConnectionImpl> idleHttp2ChannelContexts = new IdleList<>();

    /**
     * Each {@link ConnectionContext} refers to {@link Http2Stream}. The number of such objects is dynamic, but we
     * don't want to generate and allocate a lot of garbage, So we have a linked-list of unused {@link Http2Stream}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     */
    private final IdleList<Http2Stream> idleHttp2RequestContexts = new IdleList<>();

    /**
     * Each {@link ConnectionContext} has a queue of {@link OutputBuffer} waiting to be written. The number of such
     * objects is dynamic, but wedon't want to generate and allocate a lot of garbage, So we have a linked-list of
     * unused {@link OutputBuffer}from which we can take one (from the main thread) and return one (from any of the
     * thread in the
     */
    private final IdleList<OutputBuffer> outputBuffers = new IdleList<>();

    /**
     * Create a new instance
     *
     * @param dispatcher cannot be null
     */
    public ContextReuseManager(final Dispatcher dispatcher, final WebServerConfig config) {
        this.config = Objects.requireNonNull(config);
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    /**
     * Gets an {@link Http1ConnectionContext} instance that can be used for a new channel.
     * The {@link ChannelManager} is responsible to make
     * sure we don't create too many contexts.
     *
     * @return An {@link Http1ConnectionContext} instance ready to be configured. Never null.
     */
    public Http1ConnectionContext checkoutHttp1ConnectionContext() {
        return idleHttp1ChannelContexts.checkout(
                () -> new Http1ConnectionContext(this, dispatcher));
    }

    /**
     * Returns a previously checked out {@link Http1ConnectionContext}. Once returned, do not
     * use it again until it is re-checked out!
     *
     * @param ctx The context to return. Cannot be null.
     */
    public void returnHttp1ConnectionContext(Http1ConnectionContext ctx) {
        idleHttp1ChannelContexts.checkin(Objects.requireNonNull(ctx));
    }

    /**
     * Gets an {@link Http2ConnectionImpl} instance that can be used for a new channel.
     * The {@link ChannelManager} is responsible to make
     * sure we don't create too many contexts.
     *
     * @return An {@link Http2ConnectionImpl} instance ready to be configured. Never null.
     */
    public Http2ConnectionImpl checkoutHttp2ConnectionContext() {
        return idleHttp2ChannelContexts.checkout(
                () -> new Http2ConnectionImpl(this, config));
    }

    /**
     * Returns a previously checked out {@link Http2ConnectionImpl}. Once returned, do not
     * use it again until it is re-checked out!
     *
     * @param ctx The context to return. Cannot be null.
     */
    public void returnHttp2ConnectionContext(Http2ConnectionImpl ctx) {
        idleHttp2ChannelContexts.checkin(Objects.requireNonNull(ctx));
    }

    /**
     * Gets a {@link Http2Stream} instance that can be used for a new request.
     *
     * @return A usable {@link Http2Stream} instance ready to be configured. Never null.
     */
    public Http2Stream checkoutHttp2RequestContext() {
        return idleHttp2RequestContexts.checkout(
                () -> new Http2Stream(dispatcher));
    }

    /**
     * Returns the given {@link Http2Stream} to the idle set.
     *
     * @param http2Stream The context to return for reuse, not null.
     */
    public void returnHttp2RequestContext(final Http2Stream http2Stream) {
        idleHttp2RequestContexts.checkin(Objects.requireNonNull(http2Stream));
    }

    /**
     * Gets an {@link OutputBuffer} instance that can be used by channel context for writing data.
     * The {@link ConnectionContext} is responsible to make sure we don't create too many contexts.
     *
     * @return An {@link OutputBuffer} instance reset and ready to used. Never null.
     */
    public OutputBuffer checkoutOutputBuffer() {
        return outputBuffers.checkout(
                () -> new OutputBuffer(config.outputBufferSize())).reset();
    }

    /**
     * Returns a previously checked out {@link OutputBuffer}. Once returned, do not use it again until it is
     * re-checked out!
     *
     * @param outputBuffer The buffer to return. Cannot be null.
     */
    public void returnOutputBuffer(OutputBuffer outputBuffer) {
        outputBuffers.checkin(Objects.requireNonNull(outputBuffer));
    }

    /**
     * A node in the linked list of idle instances. Each slot has a pointer to the next unused slot.
     * @param <T>
     */
    private static final class Slot<T> {
        private Slot<T> next;
        private T data;
    }

    private static final class IdleList<T> {
        private Slot<T> firstIdle;
        private Slot<T> firstUsed;

        synchronized T checkout(Supplier<T> creator) {
            if (firstIdle != null) {
                final var slot = firstIdle;
                firstIdle = slot.next;
                slot.next = firstUsed;
                firstUsed = slot;
                final var data = slot.data;
                slot.data = null;
                return data;
            }

            return creator.get();
        }

        synchronized void checkin(T data) {
            Slot<T> slot;
            if (firstUsed != null) {
                slot = firstUsed;
                firstUsed = slot.next;
            } else {
                slot = new Slot<>();
            }

            slot.data = data;
            if (firstIdle != null) slot.next = firstIdle.next;
            firstIdle = slot;
        }
    }
}
