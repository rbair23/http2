package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.impl.Dispatcher;

import java.io.InputStream;
import java.util.Objects;

/**
 * Represents a single request. They double as the implementation of {@link WebRequest}, so we do not
 * have to copy data into the {@link WebRequest}. This means that initially this class is called on the
 * "web server" thread, as it is populated with data, and then switches to one of the "web handler"
 * threads from the thread pool. So multi-threading considerations need to be taken into account.
 */
public abstract class RequestContext {

    /**
     * The {@link Dispatcher} to use for dispatching {@link com.hedera.hashgraph.web.WebRequest}s.
     */
    protected final Dispatcher dispatcher;

    // =================================================================================================================
    // Request Data

    /**
     * This field is set while parsing the HTTP request, and before the request is sent to a handler.
     */
    protected HttpVersion version;

    // =================================================================================================================
    // ConnectionContext Methods

    /**
     * Create a new instance.
     *
     * @param dispatcher The dispatcher to send this request to. Must not be null.
     */
    protected RequestContext(final Dispatcher dispatcher, final HttpVersion version) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.version = version;
    }

    /**
     * Called to init the request context prior to its next use.
     */
    protected void reset() {
    }
}
