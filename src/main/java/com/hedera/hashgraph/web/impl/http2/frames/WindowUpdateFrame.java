package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;

import java.io.IOException;
import java.nio.ByteBuffer;

/*
WINDOW_UPDATE Frame {
  Length (24) = 0x04,
  Type (8) = 0x08,

  Unused Flags (8),

  Reserved (1),
  Stream Identifier (31),

  Reserved (1),
  Window Size Increment (31),
}
 */
public final class WindowUpdateFrame extends Frame {

    private final int windowSizeIncrement;

    public WindowUpdateFrame(int streamId, int windowSizeIncrement) {
        super(FrameTypes.HEADERS, streamId);
        this.windowSizeIncrement = windowSizeIncrement;
        assert windowSizeIncrement >= 0; // This should be true, unless we forgot to strip off the leading bit...
    }

    public int getWindowSizeIncrement() {
        return windowSizeIncrement;
    }

    public static WindowUpdateFrame parse(HttpInputStream in) throws IOException {
        // TODO Look at the spec to see what we really should be doing here
        final var frameLength = in.read24BitInteger();
        if (frameLength != 4) {
            // TODO This is probably an error condition. I think it should always be 4!!
        }

        // Read past the type
        final var type = in.readByte();
        assert type == FrameTypes.WINDOW_UPDATE.ordinal()
                : "Wrong method called, type mismatch " + type + " not for window update";

        // These flags are totally unused.
        //noinspection unused
        var flags = in.readByte();

        final var streamId = in.read31BitInteger();
        final var windowSizeIncrement = in.read31BitInteger();
        return new WindowUpdateFrame(streamId, windowSizeIncrement);
    }
}
