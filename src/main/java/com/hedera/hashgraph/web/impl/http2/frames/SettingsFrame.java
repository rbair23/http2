package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import static com.hedera.hashgraph.web.impl.http2.frames.Settings.*;

/**
 * The settings frame.
 *
 * <p>SPEC 6.5<br>
 * The SETTINGS frame (type=0x04) conveys configuration parameters that affect how endpoints communicate, such as
 * preferences and constraints on peer behavior. The SETTINGS frame is also used to acknowledge the receipt of those
 * settings. Individually, a configuration parameter from a SETTINGS frame is referred to as a "setting".
 *
 * <p>Settings are not negotiated; they describe characteristics of the sending peer, which are used by the receiving
 * peer. Different values for the same setting can be advertised by each peer. For example, a client might set a high
 * initial flow-control window, whereas a server might set a lower value to conserve resources.
 *
 * <p>A SETTINGS frame MUST be sent by both endpoints at the start of a connection and MAY be sent at any other time
 * by either endpoint over the lifetime of the connection. Implementations MUST support all of the settings defined by
 * this specification.
 *
 * <p>Each parameter in a SETTINGS frame replaces any existing value for that parameter. Settings are processed in the
 * order in which they appear, and a receiver of a SETTINGS frame does not need to maintain any state other than the
 * current value of each setting. Therefore, the value of a SETTINGS parameter is the last value that is seen by a
 * receiver.
 *
 * <p>SETTINGS frames are acknowledged by the receiving peer. To enable this, the SETTINGS frame defines the ACK flag.
 */
public final class SettingsFrame extends Frame {
    private enum Setting {
        SETTINGS_HEADER_TABLE_SIZE(),
        SETTINGS_ENABLE_PUSH(),
        SETTINGS_MAX_CONCURRENT_STREAMS(),
        SETTINGS_INITIAL_WINDOW_SIZE(),
        SETTINGS_MAX_FRAME_SIZE(),
        SETTINGS_MAX_HEADER_LIST_SIZE();

        public int id() {
            return ordinal() + 1;
        }

        private static Setting valueOf(int id) {
            return switch (id) {
                case 1 -> SETTINGS_HEADER_TABLE_SIZE;
                case 2 -> SETTINGS_ENABLE_PUSH;
                case 3 -> SETTINGS_MAX_CONCURRENT_STREAMS;
                case 4 -> SETTINGS_INITIAL_WINDOW_SIZE;
                case 5 -> SETTINGS_MAX_FRAME_SIZE;
                case 6 -> SETTINGS_MAX_HEADER_LIST_SIZE;
                default -> null;
            };
        }
    }

    /**
     * A bitset specifying which of the settings is defined by this frame. A frame does not
     * have to send all settings, or any settings at all! The bitset tells us which settings
     * have been set.
     */
    private byte definedSettings = 0b00_0000;

    private long headerTableSize = DEFAULT_HEADER_TABLE_SIZE;
    private boolean enablePush = DEFAULT_ENABLE_PUSH;
    private long maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
    private long initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    private long maxHeaderListSize = DEFAULT_MAX_HEADER_LIST_SIZE;

    /**
     * Create a new instance.
     */
    public SettingsFrame() {
        // The stream for settings is ALWAYS 0
        super(FrameType.SETTINGS);
    }

    /**
     * Create a new instance.
     */
    public SettingsFrame(Settings settings) {
        // The stream for settings is ALWAYS 0
        super(FrameType.SETTINGS);
        if (settings != null) {
            if (settings.isEnablePush() != DEFAULT_ENABLE_PUSH) {
                setEnablePush(settings.isEnablePush());
            }

            if (settings.getHeaderTableSize() != DEFAULT_HEADER_TABLE_SIZE) {
                setHeaderTableSize(settings.getHeaderTableSize());
            }

            if (settings.getInitialWindowSize() != DEFAULT_INITIAL_WINDOW_SIZE) {
                setInitialWindowSize(settings.getInitialWindowSize());
            }

            if (settings.getMaxConcurrentStreams() != DEFAULT_MAX_CONCURRENT_STREAMS) {
                setMaxConcurrentStreams(settings.getMaxConcurrentStreams());
            }

            if (settings.getMaxFrameSize() != DEFAULT_MAX_FRAME_SIZE) {
                setMaxFrameSize(settings.getMaxFrameSize());
            }

            if (settings.getMaxHeaderListSize() != DEFAULT_MAX_HEADER_LIST_SIZE) {
                setMaxHeaderListSize(settings.getMaxHeaderListSize());
            }
        }
    }

    public boolean isAck() {
        return super.isEighthFlagSet();
    }

    private boolean isSet(Setting setting) {
        return (definedSettings & (1 << setting.ordinal())) != 0;
    }

    private void set(Setting setting) {
        setPayloadLength(getPayloadLength() + 6);
        definedSettings |= (1 << setting.ordinal());
    }

    public boolean isHeaderTableSizeSet() {
        return isSet(Setting.SETTINGS_HEADER_TABLE_SIZE);
    }

    public long getHeaderTableSize() {
        return headerTableSize;
    }

    public void setHeaderTableSize(long headerTableSize) {
        set(Setting.SETTINGS_HEADER_TABLE_SIZE);
        this.headerTableSize = headerTableSize;
    }

    public boolean isEnablePushSet() {
        return isSet(Setting.SETTINGS_ENABLE_PUSH);
    }

    public boolean isEnablePush() {
        return enablePush;
    }

    public void setEnablePush(boolean enablePush) {
        set(Setting.SETTINGS_ENABLE_PUSH);
        this.enablePush = enablePush;
    }

    public boolean isMaxConcurrentStreamsSet() {
        return isSet(Setting.SETTINGS_MAX_CONCURRENT_STREAMS);
    }

    public long getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    public void setMaxConcurrentStreams(long maxConcurrentStreams) {
        set(Setting.SETTINGS_MAX_CONCURRENT_STREAMS);
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    public boolean isInitialWindowSizeSet() {
        return isSet(Setting.SETTINGS_INITIAL_WINDOW_SIZE);
    }

    public long getInitialWindowSize() {
        return initialWindowSize;
    }

    public void setInitialWindowSize(long initialWindowSize) {
        set(Setting.SETTINGS_INITIAL_WINDOW_SIZE);
        this.initialWindowSize = initialWindowSize;
    }

    public boolean isMaxFrameSizeSet() {
        return isSet(Setting.SETTINGS_MAX_FRAME_SIZE);
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public void setMaxFrameSize(int maxFrameSize) {
        set(Setting.SETTINGS_MAX_FRAME_SIZE);
        this.maxFrameSize = maxFrameSize;
    }

    public boolean isMaxHeaderListSizeSet() {
        return isSet(Setting.SETTINGS_MAX_HEADER_LIST_SIZE);
    }

    public long getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    public void setMaxHeaderListSize(long maxHeaderListSize) {
        set(Setting.SETTINGS_MAX_HEADER_LIST_SIZE);
        this.maxHeaderListSize = maxHeaderListSize;
    }

    public void mergeInto(Settings settings) {
        if (isHeaderTableSizeSet()) {
            settings.setHeaderTableSize(getHeaderTableSize());
        }

        if (isEnablePushSet()) {
            settings.setEnablePush(isEnablePush());
        }

        if (isMaxConcurrentStreamsSet()) {
            settings.setMaxConcurrentStreams(getMaxConcurrentStreams());
        }

        if (isInitialWindowSizeSet()) {
            settings.setInitialWindowSize(getInitialWindowSize());
        }

        if (isMaxFrameSizeSet()) {
            settings.setMaxFrameSize(getMaxFrameSize());
        }

        if (isMaxHeaderListSizeSet()) {
            settings.setMaxHeaderListSize(getMaxHeaderListSize());
        }
    }

    /**
     * Parses the data for settings from the given input stream. All data for the settings frame must have
     * already been buffered up in the input stream.
     *
     * @param in The input stream, cannot be null.
     */
    @Override
    public void parse2(InputBuffer in) {
        definedSettings = 0; // Reset it

        super.parse2(in);

        // SPEC: 6.5
        //   The stream identifier MUST be 0. Otherwise, respond with connection error PROTOCOL_ERROR.
        final var streamId = getStreamId();
        if (streamId !=0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // SPEC: 6.5
        //   A settings frame with a length other than a multiple of 6 bytes MUST be treated as a connection error
        //   of type FRAME_SIZE_ERROR.
        final var payloadLength = getPayloadLength();
        if (payloadLength % 6 != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // SPEC: 6.5
        //   If the ACK flag is set, the length will be 0. If the ACK flag is set and the length is
        //   anything other than 0, then we MUST treat this as a connection error of kind FRAME_SIZE_ERROR.
        final var ack = isAck();
        if (ack && payloadLength != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // The settings frame we receive *MAY* be an ACK of the one we sent to the
        // client. We DO NOT want to apply that frame here!
        if (ack) {
            // Read off the remaining frameLength bytes
            in.skip(payloadLength);
        }

        // Read off all the settings. Each Setting has a 2 byte identifier and a 4 byte value
        final var numSettings = payloadLength / 6;
        for (int i = 0; i < numSettings; i++) {
            final var settingId = in.read16BitInteger();
            final var setting = Setting.valueOf(settingId);
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
                        if (value < INITIAL_FRAME_SIZE || value > MAX_FRAME_SIZE) {
                            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                        }

                        // Valid values are well under Integer.MAX_VALUE, so this is safe.
                        setMaxFrameSize((int) value);
                    }
                    case SETTINGS_ENABLE_PUSH -> {
                        // SPEC: 6.5.2
                        // Any value other than 0 or 1 MUST be treated as a connection error (Section 5.4.1) of type
                        // PROTOCOL_ERROR.
                        if (value != 0 && value != 1) {
                            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                        }

                        setEnablePush(value == 1);
                    }
                    case SETTINGS_HEADER_TABLE_SIZE -> setHeaderTableSize(value);
                    case SETTINGS_INITIAL_WINDOW_SIZE -> {
                        // SPEC: 6.5.2
                        // Values above the maximum flow-control window size of 2^31-1 MUST be treated as a connection
                        // error (Section 5.4.1) of type FLOW_CONTROL_ERROR
                        if (value > MAX_FLOW_CONTROL_WINDOW_SIZE) {
                            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, streamId);
                        }
                        setInitialWindowSize(value);
                    }
                    case SETTINGS_MAX_CONCURRENT_STREAMS -> setMaxConcurrentStreams(value);
                    case SETTINGS_MAX_HEADER_LIST_SIZE -> setMaxHeaderListSize(value);
                }
            }
        }
    }

    /**
     * A special method for writing an ACK frame.
     *
     * @param out The output stream. Must not be null.
     */
    public void writeAck(final OutputBuffer out) {
        Frame.writeHeader(out, 0, FrameType.SETTINGS, (byte) 0x1, 0);
    }

    /**
     * Writes the given settings to the given output stream.
     *
     * @param out Cannot be null
     */
    @Override
    public void write(final OutputBuffer out) {
        super.write(out);

        if (isHeaderTableSizeSet()) {
            out.write16BigInteger(Setting.SETTINGS_HEADER_TABLE_SIZE.id());
            out.write32BitUnsignedInteger(getHeaderTableSize());
        }

        if (isEnablePushSet()) {
            out.write16BigInteger(Setting.SETTINGS_ENABLE_PUSH.id());
            out.write32BitUnsignedInteger(isEnablePush() ? 1 : 0);
        }

        if (isMaxConcurrentStreamsSet()) {
            out.write16BigInteger(Setting.SETTINGS_MAX_CONCURRENT_STREAMS.id());
            out.write32BitUnsignedInteger(getMaxConcurrentStreams());
        }

        if (isInitialWindowSizeSet()) {
            out.write16BigInteger(Setting.SETTINGS_INITIAL_WINDOW_SIZE.id());
            out.write32BitUnsignedInteger(getInitialWindowSize());
        }

        if (isMaxFrameSizeSet()) {
            out.write16BigInteger(Setting.SETTINGS_MAX_FRAME_SIZE.id());
            out.write32BitUnsignedInteger(getMaxFrameSize());
        }

        if (isMaxHeaderListSizeSet()) {
            out.write16BigInteger(Setting.SETTINGS_MAX_HEADER_LIST_SIZE.id());
            out.write32BitUnsignedInteger(getMaxHeaderListSize());
        }
    }

    @Override
    public String toString() {
        return "SettingsFrame{" +
                "definedSettings=" + definedSettings +
                ", headerTableSize=" + headerTableSize +
                ", enablePush=" + enablePush +
                ", maxConcurrentStreams=" + maxConcurrentStreams +
                ", initialWindowSize=" + initialWindowSize +
                ", maxFrameSize=" + maxFrameSize +
                ", maxHeaderListSize=" + maxHeaderListSize +
                '}';
    }
}
