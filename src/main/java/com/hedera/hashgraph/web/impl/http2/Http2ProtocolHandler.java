package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.impl.ProtocolHandler;
import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.HttpOutputStream;
import com.hedera.hashgraph.web.impl.http2.frames.FrameTypes;
import com.hedera.hashgraph.web.impl.http2.frames.Settings;

import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// Right now, this is created per-thread. To be reused across threads, we have to do some kind of per-thread state,
// such as for settings and request handlers.
public class Http2ProtocolHandler implements ProtocolHandler {
    private static final byte[] CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes();
    private static final int CONNECTION_PREFACE_LENGTH = CONNECTION_PREFACE.length;
    private static final int INITIAL_FRAME_SIZE = 1 << 14;
    private static final int MAX_FRAME_SIZE = (1 << 24) - 1;
    private static final long FRAME_HEADER_SIZE = 9;

    // Map of request id to request handler. Each frame that we read maps to a specific handler, key'd by id.
//    private final Map<Integer, Http2RequestHandler> requestHandlers = new HashMap();

    // Default sizes come from the spec, section 6.5.2
    private int settingsHeaderTableSize = 1 << 12;
    private boolean settingsEnablePush = false;
    private int settingsMaxConcurrentStreams = Integer.MAX_VALUE;
    private int settingsInitialWindowSize = (1 << 16) - 1;
    private int settingsMaxFrameSize = INITIAL_FRAME_SIZE;
    private int settingsMaxHeaderListSize = Integer.MAX_VALUE;

    @Override
    public void handle(HttpInputStream in, HttpOutputStream out) {

        try {
            // Check the preface to make sure it is good
            handlePreface(in);

            // We will keep doing this until the input stream is closed.
            while (true) {
                handleFrame(in, out);
            }
        } catch (EOFException eof) {
            eof.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Http2Exception e) {
            // TODO write the response frame out for this error.
            e.printStackTrace();
        }
    }

    private void handlePreface(HttpInputStream in) throws IOException {
        // It turns out, the Java HTTP/2 client uses an old deprecated approach, where it sends an HTTP/1.1
        // Connect "upgrade" header. So even though it is deprecated as per the spec for RFC9113, we probably
        // should support it. Sigh. Not supporting that for now.
        if (in.prefixMatch(CONNECTION_PREFACE)) {
            in.skip(CONNECTION_PREFACE_LENGTH);
        } else {
            System.err.println("This is a serious error condition, we need to close the stream and so forth");
            // TODO Figure out how to handle error conditions
        }
    }

    private void handleFrame(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var type = FrameTypes.fromOrdinal(in.pollByte(3));
        switch (type) {
            case SETTINGS -> handleSettings(in, out);
//            case HEADERS -> handleHeaders(in, out);
            // I don't know how to handle this one, so just skip it.
            default -> skipUnknownFrame(in);
        }
    }

    private void skipUnknownFrame(HttpInputStream in) throws IOException {
        final var frameLength = in.poll24BitInteger();
        in.skip(frameLength + FRAME_HEADER_SIZE);
    }

    private void handleSettings(HttpInputStream in, HttpOutputStream out) throws IOException {
        // SPEC: 6.5
        //   A settings frame with a length other than a multiple of 6 bytes MUST be treated as a connection error
        //   of type FRAME_SIZE_ERROR.
        final var frameLength = in.read24BitInteger();
        if (frameLength % 6 != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        }

        // Read past the type
        in.readByte();

        // SPEC: 6.5
        //   If the ACK flag is set, the length will be 0. If the ACK flag is set and the length is
        //   anything other than 0, then we MUST treat this as a connection error of kind FRAME_SIZE_ERROR.
        var flags = in.readByte(); // Read the 7 unused bits and the 1 bit ACK flag
        final var ack = (flags & 1) == 1;
        if (ack && frameLength != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        }

        // SPEC: 6.5
        //   The stream identifier MUST be 0. Otherwise, respond with connection error PROTOCOL_ERROR.
        final var streamId = in.read31BitInteger();
        if (streamId !=0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }

        // Read off all the settings. Each Setting has a 2 byte identifier and a 4 byte value
        final var numSettings = frameLength / 6;
        for (int i = 0; i < numSettings; i++) {
            final var settingOrdinal = in.read16BitInteger();
            final var setting = Settings.fromOrdinal(settingOrdinal);
            final var value = in.read32BitInteger();

            // SPEC: 6.5.2
            //   An endpoint that receives a SETTINGS frame with any unknown or unsupported
            //   identifier MUST ignore that setting
            if (setting != null) {

                // SPEC: 6.5
                //   A badly formed or incomplete settings frame MUST be treated as a PROTOCOL_ERROR
                //
                switch (setting) {
                    // TODO Add support for the other Settings types
                    case SETTINGS_MAX_FRAME_SIZE -> {
                        // SPEC: 6.5.2
                        //   The value advertised by an endpoint MUST be between this initial value and
                        //   the maximum allowed frame size
                        if (value < INITIAL_FRAME_SIZE || value > MAX_FRAME_SIZE) {
                            // TODO Holy Crap, OKHttp has this value wrong! As per the spec!!!
                            // Working around for now, but we need to get a patch to OKHttp so it works correctly.
                            // How many other clients are wrong?
                            if (value > MAX_FRAME_SIZE + 1) {
                                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                            }
                        }

                        settingsMaxFrameSize = value;
                    }
                    case SETTINGS_ENABLE_PUSH -> {
                        // TODO Validate the input (should be a 0 or a 1)
                        settingsEnablePush = value == 1;
                    }
                    case SETTINGS_HEADER_TABLE_SIZE -> {
                        // TODO Validate the input
                        settingsHeaderTableSize = value;
                    }
                    case SETTINGS_INITIAL_WINDOW_SIZE -> {
                        // TODO Validate the input
                        settingsInitialWindowSize = value;
                    }
                    case SETTINGS_MAX_CONCURRENT_STREAMS -> {
                        // TODO Validate the input
                        settingsMaxConcurrentStreams = value;
                    }
                    case SETTINGS_MAX_HEADER_LIST_SIZE -> {
                        // TODO Validate the input
                        settingsMaxHeaderListSize = value;
                    }
                }
            }

            // Write the ACK out to the client to let them know we received this settings information
            out.write24BitInteger(0);
            out.writeByte(FrameTypes.SETTINGS.ordinal());
            out.writeByte(0x1); // ACK set
            out.write32BitInteger(0);
        }
    }

    private void handleHeaders(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var frameLength = in.read24BitInteger();

        // Read past the type
        in.readByte();

        // TODO, keep track of the number of bytes read, and make sure they match frameLength perfectly

        var flags = in.readByte();
        final var priorityFlag = (flags & 0b0010_0000) != 0;
        final var paddedFlag = (flags & 0b0000_1000) != 0;
        final var endHeadersFlag = (flags & 0b0000_0100) != 0;
        final var endStreamFlag = (flags & 0b0000_0001) != 0;

        final var streamId = in.read31BitInteger();

        final var padLength = paddedFlag ? in.readByte() : 0;

        if (priorityFlag) {
            final var data = in.read32BitInteger();
            final var exclusive = (data & (Integer.MIN_VALUE)) != 0;
            final var streamDependency = data & 0x7FFFFFFF;
            final var weight = in.readByte();
        }

        // TODO Parse off the field block fragment

    }
}
