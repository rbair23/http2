package com.hedera.hashgraph.web.impl.http2.frames;

import java.nio.ByteBuffer;

/*
HEADERS Frame {
  Length (24),
  Type (8) = 0x01,

  Unused Flags (2),
  PRIORITY Flag (1),
  Unused Flag (1),
  PADDED Flag (1),
  END_HEADERS Flag (1),
  Unused Flag (1),
  END_STREAM Flag (1),

  Reserved (1),
  Stream Identifier (31),

  [Pad Length (8)],
  [Exclusive (1)],
  [Stream Dependency (31)],
  [Weight (8)],
  Field Block Fragment (..),
  Padding (..2040),
}
 */
public final class HeadersFrame extends Frame {
    private static final int PRIORITY_FLAG = FLAG_2;
    private static final int PADDED_FLAG = FLAG_4;
    private static final int END_HEADERS_FLAG = FLAG_5;
    private static final int END_STREAM_FLAG = FLAG_7;

    public HeadersFrame(int streamId) {
        super(FrameTypes.HEADERS, streamId);
        setFlag(END_HEADERS_FLAG);
        setFlag(END_STREAM_FLAG);
    }

    public void serialize(ByteBuffer buffer) {
        // length (24)
        // type (8)
        //
    }
}
