package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.HttpInputStream;

import java.io.IOException;
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

    public HeadersFrame(byte flags, int streamId, byte padLength, boolean exclusive, int streamDep, byte weight) {
        super(FrameTypes.HEADERS, streamId);

        if ((flags & PRIORITY_FLAG) != 0) {
            setFlag(PRIORITY_FLAG);
        }

        if ((flags & PADDED_FLAG) != 0) {
            setFlag(PADDED_FLAG);
        }

        if ((flags & END_HEADERS_FLAG) != 0) {
            setFlag(END_HEADERS_FLAG);
        }

        if ((flags & END_STREAM_FLAG) != 0) {
            setFlag(END_STREAM_FLAG);
        }
    }

    public static HeadersFrame parse(HttpInputStream in) throws IOException {
        final var frameLength = in.read24BitInteger();

        // Read past the type
        in.readByte();

        // TODO, keep track of the number of bytes read, and make sure they match frameLength perfectly

        var flags = in.readByte();
        final var priorityFlag = (flags & PRIORITY_FLAG) != 0;
        final var paddedFlag = (flags & PADDED_FLAG) != 0;

        final var streamId = in.read31BitInteger();

        final var padLength = paddedFlag ? in.readByte() : 0;

        final var data = priorityFlag ? 0 : in.read32BitInteger();
        final var exclusive = priorityFlag ? false : (data & (Integer.MIN_VALUE)) != 0;
        final var streamDependency = priorityFlag ? -1 : data & 0x7FFFFFFF;
        final var weight = priorityFlag ? 0 : in.readByte();

        // TODO Parse off the field block fragment. I think what we should do here is to just read the bytes
        //      and store them in the frame, and let the Http2RequestHandler deal with parsing it and
        //      decoding it. Not only is that better from a multi-threaded perspective, but actually a header's
        //      fields can span a HeadersFrame and multiple ContinuationFrames, interleaved with all kinds of
        //      other frames from other streams, and so only the request handler that gets the frames in
        //      order for a single stream can make sense of the continuation blocks and handle flow control
        //      and combining the bytes for decompression, and so forth. SO I think the game is to just keep
        //      track of how many payload bytes for this frame we've parsed, and then use the frameLength
        //      to know how many subsequent bytes we should read for the fieldBlockFragment. Also, we probably
        //      need to use the padLength to get the very last bytes.


        return new HeadersFrame(flags, streamId, padLength, exclusive, streamDependency, weight);
    }
}
