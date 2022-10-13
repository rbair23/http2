package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http.Http1ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.Http2RequestContext;
import com.hedera.hashgraph.web.impl.http2.frames.HeadersFrame;

import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Responsible for managing reusable contexts
 */
public class ContextReuseManager {

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
    private final IdleList<Http2ConnectionContext> idleHttp2ChannelContexts = new IdleList<>();

    /**
     * Each {@link ConnectionContext} refers to {@link Http2RequestContext}. The number of such objects is dynamic, but we
     * don't want to generate and allocate a lot of garbage, So we have a linked-list of unused {@link Http2RequestContext}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     */
    private final IdleList<Http2RequestContext> idleHttp2RequestContexts = new IdleList<>();

    /**
     * Gets a {@link ConnectionContext} instance that can be used for a new channel. If there are
     * no remaining instance in the {@link #idleHttp1ChannelContexts}, then a new instance
     * is created. The availableConnections is used to make sure we never create
     * too many of these.
     *
     * @param channel The channel to provide to this instance. Cannot be null.
     * @return A usable and freshly configured {@link ConnectionContext} instance. Never null.
     */
    public Http1ConnectionContext checkoutHttp1ChannelSession(
            Dispatcher dispatcher, SocketChannel channel, Runnable onCloseCallback) {
        Objects.requireNonNull(channel);

        final Http1ConnectionContext ctx = idleHttp1ChannelContexts.checkout(
                () -> new Http1ConnectionContext(this, dispatcher));

        ctx.resetWithNewChannel(channel, onCloseCallback);

        return ctx;
    }

    /**
     * Gets a {@link ConnectionContext} instance that can be used for a new channel. If there are
     * no remaining instance in the {@link #idleHttp1ChannelContexts}, then a new instance
     * is created. The availableConnections is used to make sure we never create
     * too many of these.
     *
     * @param channel The channel to provide to this instance. Cannot be null.
     * @return A usable and freshly configured {@link ConnectionContext} instance. Never null.
     */
    public Http2ConnectionContext checkoutHttp2ChannelSession(Dispatcher dispatcher, SocketChannel channel, Runnable onCloseCallback) {
        Objects.requireNonNull(channel);

        final Http2ConnectionContext ctx = idleHttp2ChannelContexts.checkout(
                () -> new Http2ConnectionContext(this, dispatcher));

        ctx.resetWithNewChannel(channel, onCloseCallback);
        return ctx;
    }

    public void returnContext(ConnectionContext ctx) {
        Objects.requireNonNull(ctx);
        if (ctx instanceof Http1ConnectionContext http1Ctx) {
            idleHttp1ChannelContexts.checkin(http1Ctx);
        } else if (ctx instanceof  Http2ConnectionContext http2Ctx){
            idleHttp2ChannelContexts.checkin(http2Ctx);
        }
    }


    /**
     * Gets a {@link Http2RequestContext} instance that can be used for a new request. If there are
     * no remaining instance in the {@link #idleHttp2RequestContexts}, then a new instance
     * is created. TODO How to make sure we don't get too many requests??
     *
     * @return A usable and freshly configured {@link ConnectionContext} instance. Never null.
     */
    public Http2RequestContext checkoutHttp2RequestContext(Dispatcher dispatcher, SocketChannel channel, IntConsumer onClosed) {
        final var ctx = idleHttp2RequestContexts.checkout(
                () -> new Http2RequestContext(this, dispatcher));

        ctx.reset(onClosed, channel);

        return ctx;
    }

    /**
     * Returns the given {@link Http2RequestContext} to the idle set.
     *
     * @param http2RequestContext The http2RequestContext to return for reuse, not null.
     */
    public void returnHttp2RequestContext(Http2RequestContext http2RequestContext) {
        idleHttp2RequestContexts.checkin(http2RequestContext);
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
            slot.next = firstIdle.next;
            firstIdle = slot;
        }
    }
}
