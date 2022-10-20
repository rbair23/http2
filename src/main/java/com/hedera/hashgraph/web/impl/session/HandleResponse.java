package com.hedera.hashgraph.web.impl.session;

/**
 * Enum of responses to handle
 */
public enum HandleResponse {
    /**
     * Returned when I don't need any more read callbacks until new data arrives in the channel.
     */
    ALL_DATA_HANDLED,
    /**
     * Indicates that I need my read handler called again, even if new data doesn't arrive
     */
    DATA_STILL_TO_HANDLED,
    /**
     * Close the connection.
     */
    CLOSE_CONNECTION
}
