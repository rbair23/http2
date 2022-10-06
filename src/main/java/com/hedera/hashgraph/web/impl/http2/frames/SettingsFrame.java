package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.HttpOutputStream;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;

import java.io.IOException;

import static com.hedera.hashgraph.web.impl.http2.frames.Settings.INITIAL_FRAME_SIZE;
import static com.hedera.hashgraph.web.impl.http2.frames.Settings.MAX_FRAME_SIZE;

public final class SettingsFrame extends Frame {
    public enum Setting {
        SETTINGS_HEADER_TABLE_SIZE,
        SETTINGS_ENABLE_PUSH,
        SETTINGS_MAX_CONCURRENT_STREAMS,
        SETTINGS_INITIAL_WINDOW_SIZE,
        SETTINGS_MAX_FRAME_SIZE,
        SETTINGS_MAX_HEADER_LIST_SIZE;

        public static Setting fromOrdinal(int ordinal) {
            return switch (ordinal) {
                case 0 -> SETTINGS_HEADER_TABLE_SIZE;
                case 1 -> SETTINGS_ENABLE_PUSH;
                case 2 -> SETTINGS_MAX_CONCURRENT_STREAMS;
                case 3 -> SETTINGS_INITIAL_WINDOW_SIZE;
                case 4 -> SETTINGS_MAX_FRAME_SIZE;
                case 5 -> SETTINGS_MAX_HEADER_LIST_SIZE;
                default -> null;
            };
        }
    }

    // TODO Settings frame needs fields and the ability to write them to output so the server can send
    //      Settings to the client.

    public SettingsFrame() {
        // The stream for settings is ALWAYS 0
        super(FrameTypes.SETTINGS, 0);
    }

    public void write(HttpOutputStream out) throws IOException {

    }

    public static void parseAndMerge(HttpInputStream in, Settings settings) throws IOException {
        // SPEC: 6.5
        //   A settings frame with a length other than a multiple of 6 bytes MUST be treated as a connection error
        //   of type FRAME_SIZE_ERROR.
        final var frameLength = in.read24BitInteger();
        if (frameLength % 6 != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        }

        // Read past the type
        final var type = in.readByte();
        assert type == FrameTypes.SETTINGS.ordinal() : "Wrong method called, type mismatch " + type + " not for settings";

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
            final var setting = Setting.fromOrdinal(settingOrdinal);
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

                        settings.setMaxFrameSize(value);
                    }
                    case SETTINGS_ENABLE_PUSH -> {
                        // TODO Validate the input (should be a 0 or a 1)
                        settings.setEnablePush(value == 1);
                    }
                    case SETTINGS_HEADER_TABLE_SIZE -> {
                        // TODO Validate the input
                        settings.setHeaderTableSize(value);
                    }
                    case SETTINGS_INITIAL_WINDOW_SIZE -> {
                        // TODO Validate the input
                        settings.setInitialWindowSize(value);
                    }
                    case SETTINGS_MAX_CONCURRENT_STREAMS -> {
                        // TODO Validate the input
                        settings.setMaxConcurrentStreams(value);
                    }
                    case SETTINGS_MAX_HEADER_LIST_SIZE -> {
                        // TODO Validate the input
                        settings.setMaxHeaderListSize(value);
                    }
                }
            }
        }
    }

    public static void writeAck(HttpOutputStream out) throws IOException {
        out.write24BitInteger(0);
        out.writeByte(FrameTypes.SETTINGS.ordinal());
        out.writeByte(0x1); // ACK set
        out.write32BitInteger(0);
    }

}
