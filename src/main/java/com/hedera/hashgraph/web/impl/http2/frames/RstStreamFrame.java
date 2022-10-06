package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;

import java.io.IOException;
import java.util.Objects;

/*
RST_STREAM Frame {
  Length (24) = 0x04,
  Type (8) = 0x03,

  Unused Flags (8),

  Reserved (1),
  Stream Identifier (31),

  Error Code (32),
}
 */
public final class RstStreamFrame extends Frame {

    private final Http2ErrorCode errorCode;

    public RstStreamFrame(int streamId, Http2ErrorCode errorCode) {
        super(FrameTypes.RST_STREAM, streamId);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public Http2ErrorCode getErrorCode() {
        return errorCode;
    }

    public static RstStreamFrame parse(HttpInputStream in) throws IOException {
        final var frameLength = in.read24BitInteger();
        if (frameLength != 4) {
            // TODO This is probably an error condition. I think it should always be 4!!
        }

        // Read past the type
        final var type = in.readByte();
        assert type == FrameTypes.RST_STREAM.ordinal()
                : "Wrong method called, type mismatch " + type + " not for reset stream";

        // These flags are totally unused.
        //noinspection unused
        var flags = in.readByte();

        final var streamId = in.read31BitInteger();
        final var errorCode = Http2ErrorCode.fromOrdinal(in.read32BitInteger());
        return new RstStreamFrame(streamId, errorCode);
    }
}
