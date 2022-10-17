package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.impl.util.OutputBuffer;

public interface Http2Connection {
    void flush(OutputBuffer buffer);
    void close(int streamId);
    Http2HeaderCodec getHeaderCodec();
}
