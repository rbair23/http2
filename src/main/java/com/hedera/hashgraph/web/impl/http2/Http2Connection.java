package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.impl.util.OutputBuffer;

public interface Http2Connection {
    void close(int streamId);
    Http2HeaderCodec getHeaderCodec();

    /**
     * Queue the provided buffer to be sent. The buffer will be fliped and all bytes between the start of buffer and
     * current position will be written to the channel next time the channel is ready to accept bytes.
     * <br>
     * This method can be called form any thead
     *
     * @param buffer The un-flipped buffer to write
     */
    void sendOutput(OutputBuffer buffer);
}
