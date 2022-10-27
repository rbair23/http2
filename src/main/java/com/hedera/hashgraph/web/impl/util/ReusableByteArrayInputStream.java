package com.hedera.hashgraph.web.impl.util;

import java.io.ByteArrayInputStream;

public final class ReusableByteArrayInputStream extends ByteArrayInputStream {
    public ReusableByteArrayInputStream(byte[] buf) {
        super(buf);
    }

    public void reuse(int length) {
        count = length;
        mark = 0;
        pos = 0;
    }

    public void length(int length) {
        count = length;
    }
}
