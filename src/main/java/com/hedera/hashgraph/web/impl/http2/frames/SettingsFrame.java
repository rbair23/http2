package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;

import java.io.IOException;
import java.util.Objects;

import static com.hedera.hashgraph.web.impl.http2.frames.Settings.INITIAL_FRAME_SIZE;
import static com.hedera.hashgraph.web.impl.http2.frames.Settings.MAX_FRAME_SIZE;
import static com.hedera.hashgraph.web.impl.http2.frames.Settings.MAX_FLOW_CONTROL_WINDOW_SIZE;

/**
 * The settings frame.
 */
public final class SettingsFrame extends Frame {
    private enum Setting {
        SETTINGS_HEADER_TABLE_SIZE,
        SETTINGS_ENABLE_PUSH,
        SETTINGS_MAX_CONCURRENT_STREAMS,
        SETTINGS_INITIAL_WINDOW_SIZE,
        SETTINGS_MAX_FRAME_SIZE,
        SETTINGS_MAX_HEADER_LIST_SIZE;

        private static Setting valueOf(int ordinal) {
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

    /**
     * The settings to send as part of this frame.
     */
    private final Settings settings;

    /**
     * Create a new instance.
     *
     * @param length The length.
     * @param settings The settings to send. Must not be null.
     */
    public SettingsFrame(int length, Settings settings) {
        // The stream for settings is ALWAYS 0
        super(length, FrameType.SETTINGS, (byte)0, 0);
        this.settings = Objects.requireNonNull(settings);
    }

    /**
     * Parses the data for settings from the given input stream, merging the results into the given
     * {@link Settings} instance. All data for the settings frame must have already been buffered up
     * in the input stream.
     *
     * @param in The input stream, cannot be null.
     * @param settings The settings to merge results into. Cannot be null.
     */
    public static void parseAndMerge(InputBuffer in, Settings settings) {
        // SPEC: 6.5
        //   A settings frame with a length other than a multiple of 6 bytes MUST be treated as a connection error
        //   of type FRAME_SIZE_ERROR.
        final var frameLength = in.read24BitInteger();
        if (frameLength % 6 != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, readAheadStreamId(in));
        }

        // Skip over the type
        final var type = in.readByte();
        assert type == FrameType.SETTINGS.ordinal()
                : "Wrong method called, type mismatch " + type + " not for settings";

        // SPEC: 6.5
        //   If the ACK flag is set, the length will be 0. If the ACK flag is set and the length is
        //   anything other than 0, then we MUST treat this as a connection error of kind FRAME_SIZE_ERROR.
        var flags = in.readByte(); // Read the 7 unused bits and the 1 bit ACK flag
        final var ack = (flags & Frame.EIGHTH_FLAG) == 1;
        if (ack && frameLength != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, readAheadStreamId(in));
        }

        // The settings frame we receive *MAY* be an ACK of the one we sent to the
        // client. We DO NOT want to apply that frame here!
        if (ack) {
            return;
        }

        // SPEC: 6.5
        //   The stream identifier MUST be 0. Otherwise, respond with connection error PROTOCOL_ERROR.
        final var streamId = in.read31BitInteger();
        if (streamId !=0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // Read off all the settings. Each Setting has a 2 byte identifier and a 4 byte value
        final var numSettings = frameLength / 6;
        for (int i = 0; i < numSettings; i++) {
            final var settingOrdinal = in.read16BitInteger();
            final var setting = Setting.valueOf(settingOrdinal);
            final var value = in.read32BitUnsignedInteger();

            // SPEC: 6.5.2
            //   An endpoint that receives a SETTINGS frame with any unknown or unsupported
            //   identifier MUST ignore that setting
            if (setting != null) {
                // SPEC: 6.5
                //   A badly formed or incomplete settings frame MUST be treated as a PROTOCOL_ERROR
                switch (setting) {
                    case SETTINGS_MAX_FRAME_SIZE -> {
                        // SPEC: 6.5.2
                        //   The value advertised by an endpoint MUST be between this initial value and
                        //   the maximum allowed frame size
                        //
                        // Oof, OKHttp has this value wrong! As per the spec!!!
                        // Working around for now, but we need to get a patch to OKHttp, so it works correctly.
                        // How many other clients are wrong? So I added the "+1", but it shouldn't be there.
                        if (value < INITIAL_FRAME_SIZE || value > MAX_FRAME_SIZE + 1) {
                            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                        }

                        // Valid values are well under Integer.MAX_VALUE, so this is safe.
                        settings.setMaxFrameSize((int) value);
                    }
                    case SETTINGS_ENABLE_PUSH -> {
                        // SPEC: 6.5.2
                        // Any value other than 0 or 1 MUST be treated as a connection error (Section 5.4.1) of type
                        // PROTOCOL_ERROR.
                        if (value != 0 && value != 1) {
                            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                        }

                        settings.setEnablePush(value == 1);
                    }
                    case SETTINGS_HEADER_TABLE_SIZE -> settings.setHeaderTableSize(value);
                    case SETTINGS_INITIAL_WINDOW_SIZE -> {
                        // SPEC: 6.5.2
                        // Values above the maximum flow-control window size of 2^31-1 MUST be treated as a connection
                        // error (Section 5.4.1) of type FLOW_CONTROL_ERROR
                        if (value > MAX_FLOW_CONTROL_WINDOW_SIZE) {
                            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, streamId);
                        }
                        settings.setInitialWindowSize(value);
                    }
                    case SETTINGS_MAX_CONCURRENT_STREAMS -> settings.setMaxConcurrentStreams(value);
                    case SETTINGS_MAX_HEADER_LIST_SIZE -> settings.setMaxHeaderListSize(value);
                }
            }
        }
    }

    /**
     * A special method for writing an ACK frame.
     *
     * @param out The output stream. Must not be null.
     * @throws IOException In case something cannot be written properly.
     */
    public static void writeAck(final OutputBuffer out) {
        Frame.writeHeader(out, 0, FrameType.SETTINGS, (byte) 0x1, 0);
    }

    /**
     * Writes the given settings to the given output stream.
     *
     * @param out Cannot be null
     * @param settings Cannot be null
     * @throws IOException If we cannot write
     */
    public static void write(final OutputBuffer out, final Settings settings) {
        // Write out the header. The payload length is 36 because we will write all 6 fields out
        Frame.writeHeader(out, 36, FrameType.SETTINGS, (byte) 0, 0);

        // Write out all the settings (in the future we could deduce which to send and which not
        // to send and set the initial frame payload length correctly etc... or just send them all.
        out.write16BigInteger(Setting.SETTINGS_HEADER_TABLE_SIZE.ordinal());
        out.write32BitUnsignedInteger(settings.getHeaderTableSize());
        out.write16BigInteger(Setting.SETTINGS_ENABLE_PUSH.ordinal());
        out.write32BitUnsignedInteger(settings.isEnablePush() ? 1 : 0);
        out.write16BigInteger(Setting.SETTINGS_MAX_CONCURRENT_STREAMS.ordinal());
        out.write32BitUnsignedInteger(settings.getMaxConcurrentStreams());
        out.write16BigInteger(Setting.SETTINGS_INITIAL_WINDOW_SIZE.ordinal());
        out.write32BitUnsignedInteger(settings.getInitialWindowSize());
        out.write16BigInteger(Setting.SETTINGS_MAX_FRAME_SIZE.ordinal());
        out.write32BitUnsignedInteger(settings.getMaxFrameSize());
        out.write16BigInteger(Setting.SETTINGS_MAX_HEADER_LIST_SIZE.ordinal());
        out.write32BitUnsignedInteger(settings.getMaxHeaderListSize());
    }

}
