package http2;

import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;

public class MockByteChannel implements ByteChannel {
    private boolean closed = false;
    private boolean shouldThrowOnRead = false;
    private boolean shouldThrowOnWrite = false;
    private boolean shouldThrowOnClose = false;

    private OutputBuffer dataToSend = new OutputBuffer(1024 * 1024);
    private InputBuffer dataReceived = new InputBuffer(1024 * 1024);

    /**
     * Initializes a new instance of this class.
     */
    protected MockByteChannel() {

    }

    public OutputBuffer getDataToSend() {
        return dataToSend;
    }

    public InputBuffer getDataReceived() {
        return dataReceived;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }

        if (shouldThrowOnRead) {
            throw new IOException("Random failure on read");
        }

        return copyFromBuffers(dataToSend.getBuffer(), dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }

        if (shouldThrowOnWrite) {
            throw new IOException("Random failure on write");
        }

        return copyFromBuffers(src, dataReceived.getBuffer());
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() throws IOException {
        if (shouldThrowOnClose) {
            throw new IOException("Random failure on close");
        }

        closed = true;
    }

    private int copyFromBuffers(ByteBuffer src, ByteBuffer dst) {
        // If there is no room in the target buffer, then don't read anything
        final var dstCurrentWritePosition = dst.limit();
        final var dstCapacity = dst.capacity();
        final var dstBytesAvailable = dstCapacity - dstCurrentWritePosition;
        if (dstBytesAvailable <= 0) {
            return 0;
        }

        // If there is nothing available in the src buffer, then don't read anything
        final var srcCurrentReadPosition = src.position();
        final var srcPastLastByte = src.limit();
        final var srcBytesAvailable = srcPastLastByte - srcCurrentReadPosition;
        if (srcBytesAvailable <= 0) {
            return 0;
        }

        final var bytesToCopy = Math.min(srcBytesAvailable, dstBytesAvailable);
        final var srcArray = src.array();
        final var dstArray = dst.array();
        System.arraycopy(srcArray, srcCurrentReadPosition, dstArray, dstCurrentWritePosition, bytesToCopy);

        src.position(src.position() + bytesToCopy);
        dst.limit(dst.limit() + bytesToCopy);

        return bytesToCopy;
    }
}
