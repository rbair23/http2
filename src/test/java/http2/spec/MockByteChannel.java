package http2.spec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;

public class MockByteChannel implements ByteChannel {
    private boolean closed = false;
    private boolean shouldThrowOnRead = false;
    private boolean shouldThrowOnWrite = false;
    private boolean shouldThrowOnClose = false;

    private ByteBuffer dataToSend = ByteBuffer.allocate(1024 * 1024);
    private ByteBuffer dataReceived = ByteBuffer.allocate(1024 * 1024);

    /**
     * Initializes a new instance of this class.
     */
    protected MockByteChannel() {

    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }

        if (shouldThrowOnRead) {
            throw new IOException("Random failure on read");
        }

        dataReceived.flip();
        int length = copyFromBuffers(dataReceived, dst);
        dataReceived.clear();
        return length;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }

        if (shouldThrowOnWrite) {
            throw new IOException("Random failure on write");
        }

        return copyFromBuffers(src, dataToSend);
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
        final var dstCurrentWritePosition = dst.position();
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
        dst.position(dst.position() + bytesToCopy);

        return bytesToCopy;
    }

    public void sendTo(MockByteChannel other) {
        dataToSend.flip();
        copyFromBuffers(dataToSend, other.dataReceived);
        dataToSend.clear();
    }
}
