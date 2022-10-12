package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http.Http1ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.Http2RequestContext;

import java.nio.channels.SocketChannel;
import java.util.Objects;

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
    private Http1ConnectionContext idleHttp1ChannelSession = null;

    /**
     * Each channel has some associated data that we need to maintain. When in use, the channel data is
     * stored on the {@link java.nio.channels.SelectionKey} of the channel. When no longer in use, we want
     * to keep reference to it, so we can reuse it later. So we have a linked-list of unused {@link ConnectionContext}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     */
    private Http2ConnectionContext idleHttp2ChannelSession = null;

    /**
     * Each {@link ConnectionContext} refers to {@link Http2RequestContext}. The number of such objects is dynamic, but we
     * don't want to generate and allocate a lot of garbage, So we have a linked-list of unused {@link Http2RequestContext}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     */
    private Http2RequestContext idleHttp2RequestContext = null;


    /**
     * Gets a {@link ConnectionContext} instance that can be used for a new channel. If there are
     * no remaining instance in the {@link #idleHttp1ChannelSession}, then a new instance
     * is created. The availableConnections is used to make sure we never create
     * too many of these.
     *
     * @param channel The channel to provide to this instance. Cannot be null.
     * @return A usable and freshly configured {@link ConnectionContext} instance. Never null.
     */
    public synchronized Http1ConnectionContext checkoutHttp1ChannelSession(Dispatcher dispatcher, SocketChannel channel, Runnable onCloseCallback) {
        Objects.requireNonNull(channel);

        Http1ConnectionContext channelSession;
        if (idleHttp1ChannelSession == null) {
            channelSession = new Http1ConnectionContext(this, dispatcher);
        } else {
            channelSession = idleHttp1ChannelSession;
            idleHttp1ChannelSession = (Http1ConnectionContext) channelSession.next;
            channelSession.next = null;
        }

        channelSession.resetWithNewChannel(channel, onCloseCallback);
        return channelSession;
    }

    /**
     * Gets a {@link ConnectionContext} instance that can be used for a new channel. If there are
     * no remaining instance in the {@link #idleHttp1ChannelSession}, then a new instance
     * is created. The availableConnections is used to make sure we never create
     * too many of these.
     *
     * @param channel The channel to provide to this instance. Cannot be null.
     * @return A usable and freshly configured {@link ConnectionContext} instance. Never null.
     */
    public synchronized Http2ConnectionContext checkoutHttp2ChannelSession(Dispatcher dispatcher, SocketChannel channel, Runnable onCloseCallback) {
        Objects.requireNonNull(channel);

        Http2ConnectionContext channelSession;
        if (idleHttp1ChannelSession == null) {
            channelSession = new Http2ConnectionContext(this, dispatcher);
        } else {
            channelSession = idleHttp2ChannelSession;
            idleHttp2ChannelSession = (Http2ConnectionContext) channelSession.next;
            channelSession.next = null;
        }

        channelSession.resetWithNewChannel(channel, onCloseCallback);
        return channelSession;
    }

    public synchronized void returnChannelSession(ConnectionContext channelSession) {
        Objects.requireNonNull(channelSession);
        if (channelSession instanceof Http1ConnectionContext) {
            if (idleHttp1ChannelSession != null) {
                channelSession.next = idleHttp1ChannelSession;
            }
            idleHttp1ChannelSession = (Http1ConnectionContext)channelSession;
        } else {
            if (idleHttp2ChannelSession != null) {
                channelSession.next = idleHttp2ChannelSession;
            }
            idleHttp2ChannelSession = (Http2ConnectionContext)channelSession;
        }
    }


    /**
     * Gets a {@link Http2RequestContext} instance that can be used for a new request. If there are
     * no remaining instance in the {@link #idleHttp2RequestContext}, then a new instance
     * is created. TODO How to make sure we don't get too many requests??
     *
     * @return A usable and freshly configured {@link ConnectionContext} instance. Never null.
     */
    public synchronized Http2RequestContext checkoutHttp2RequestContext(Dispatcher dispatcher) {
        if (idleHttp2RequestContext == null) {
            return new Http2RequestContext(this, dispatcher);
        } else {
            final var data = idleHttp2RequestContext;
            idleHttp2RequestContext = (Http2RequestContext) data.next;
            data.next = null;
            return data;
        }
    }

    /**
     * Returns the given {@link Http2RequestContext} to the idle set.
     *
     * @param http2RequestContext The http2RequestContext to return for reuse, not null.
     */
    public synchronized void returnHttp2RequestContext(Http2RequestContext http2RequestContext) {
        Objects.requireNonNull(http2RequestContext);
        if (idleHttp2RequestContext != null) {
            http2RequestContext.next = idleHttp2RequestContext;
        }
        idleHttp2RequestContext = http2RequestContext;
    }
}
