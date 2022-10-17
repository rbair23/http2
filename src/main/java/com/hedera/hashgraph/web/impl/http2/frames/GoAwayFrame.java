package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;

public final class GoAwayFrame extends Frame {
    private int lastStreamId = -1;
    private Http2ErrorCode errorCode = null;

    /**
     * Create a new instance.
     *
     * @param streamId The stream id must be non-negative.
     * @param lastStreamId The last stream ID that was successful
     * @param errorCode The error code associated with this frame
     */
    public GoAwayFrame(int streamId, int lastStreamId, Http2ErrorCode errorCode) {
        super(0, FrameType.GO_AWAY, (byte) 0, streamId);
        this.lastStreamId = lastStreamId;
        this.errorCode = errorCode;
    }

    /**
     * Write an RST_STREAM to the output.
     *
     * @param out The output stream. Cannot be null.
     * @param code The error code. Cannot be null.
     * @param streamId The stream id. Must not be 0.
     * @throws IOException An exception during writing
     */
    public static void write(OutputBuffer out, Http2ErrorCode code, int streamId, int lastStreamId) {
        // Write out the header.
        Frame.writeHeader(out, lastStreamId > 0 ? 8 : 4, FrameType.GO_AWAY, (byte) 0, streamId);
        if (lastStreamId > 0) {
            out.write32BitInteger(lastStreamId);
        }
        out.write32BitUnsignedInteger(code.ordinal());
    }
}
