package com.hedera.hashgraph.web.impl.util;

import com.hedera.hashgraph.web.impl.util.RingBuffer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class RingBufferTest {

    @Test
    void smokeTest() {
        final var buffer = new RingBuffer<>(16, AtomicInteger::new);
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());
        assertEquals(0, buffer.size());

        // Add a single item to my 16 element ring buffer
        buffer.offer(slot -> slot.set(1000));
        assertFalse(buffer.isEmpty());
        assertFalse(buffer.isFull());
        assertEquals(1, buffer.size());

        // Read a single item. Makes me empty again.
        var item = buffer.poll();
        assertNotNull(item);
        assertEquals(1000, item.get());
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());
        assertEquals(0, buffer.size());

        // Poll on an empty buffer is null
        assertNull(buffer.poll());

        // Fill up the buffer.
        for (int i = 1; i <= 16; i++) {
            final var value = i;
            final var accepted = buffer.offer(slot -> slot.set(value));
            assertTrue(accepted, "Failed on i = " + i);
        }
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());
        assertEquals(16, buffer.size());

        // Try to add something else to it now that it is full
        final var wasCalled = new AtomicBoolean(false);
        buffer.offer(slot -> wasCalled.set(true));
        assertFalse(wasCalled.get());

        // Now read off all 16 values and see if the values are what we expected.
        for (int i = 1; i <= 16; i++) {
            item = buffer.poll();
            assertNotNull(item);
            assertEquals(i, item.get());
        }
    }
}
