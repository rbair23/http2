package com.hedera.hashgraph.web.impl.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A very simple RingBuffer implementation with a single producer and a single consumer.
 * It is optimized for no garbage allocation.
 */
public final class RingBuffer<T> {
    private final int capacity;
    private final T[] buffer;

    private final AtomicLong writeIndex = new AtomicLong(-1);
    private final AtomicLong readIndex = new AtomicLong(0);

    public RingBuffer(int capacity, Supplier<T> factory) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive");
        }

        if (!isPowerOf2(capacity)) {
            throw new IllegalArgumentException("Capacity must be a power of 2");
        }

        this.capacity = capacity;

        //noinspection unchecked
        this.buffer = (T[]) new Object[capacity];

        // Pre-populate the ring buffer
        for (int i = 0; i < capacity; i++) {
            this.buffer[i] = factory.get();
        }
    }

    public boolean offer(Consumer<T> mutator) {
        if (!isFull()) {
            int nextWritableIndex = index(writeIndex.incrementAndGet());
            mutator.accept(buffer[nextWritableIndex]);
            return true;
        } else {
            return false;
        }
    }

    public T poll() {
        return isEmpty() ? null : buffer[index(readIndex.getAndIncrement())];
    }

    public boolean isFull() {
        return size() == capacity;
    }

    public boolean isEmpty() {
        return writeIndex.get() < readIndex.get();
    }

    public int size() {
        return (int)(writeIndex.get() - readIndex.get()) + 1;
    }

    private int index(long sequence) {
        // If capacity is power of 2, I should be able to do some bit shifting here that is faster
        return (int) mod(sequence, capacity);
    }

    private boolean isPowerOf2(int i) {
        // https://stackoverflow.com/questions/600293/how-to-check-if-a-number-is-a-power-of-2
        return (i != 0) && ((i & (i - 1)) == 0);
    }

    private long mod(long n, long d) {
        // https://www.geeksforgeeks.org/compute-modulus-division-by-a-power-of-2-number/
        return (n & (d - 1));
    }
}
