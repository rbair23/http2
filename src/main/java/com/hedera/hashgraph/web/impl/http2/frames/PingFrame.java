package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;

import java.io.IOException;
import java.util.Objects;

/*
PING Frame {
  Length (24) = 0x08,
  Type (8) = 0x06,

  Unused Flags (7),
  ACK Flag (1),

  Reserved (1),
  Stream Identifier (31) = 0,

  Opaque Data (64),
}
 */
public final class PingFrame extends Frame {

    // 8 bytes of random data, but we just interpret that as a number because it is more efficient
    private final long data;

    public PingFrame(int streamId, byte flags, long data) {
        super(FrameTypes.PING, streamId, flags);
        this.data = data;
    }

    public long getData() {
        return data;
    }

    public static PingFrame parse(HttpInputStream in) throws IOException {
        final var frameLength = in.read24BitInteger();
        if (frameLength != 8) {
            // TODO This is probably an error condition. I think it should always be 4!!
        }

        // Read past the type
        final var type = in.readByte();
        assert type == FrameTypes.PING.ordinal()
                : "Wrong method called, type mismatch " + type + " not for ping";

        // The flags
        var flags = in.readByte();

        final var streamId = in.read31BitInteger();
        final var data = in.read64BitLong();
        return new PingFrame(streamId, flags, data);
    }
}
