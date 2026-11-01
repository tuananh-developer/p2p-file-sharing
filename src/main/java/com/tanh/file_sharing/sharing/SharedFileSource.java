package com.tanh.file_sharing.sharing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** An already-opened, validated shared file plus its expected transfer metadata. */
public final class SharedFileSource implements AutoCloseable {
    private final String fileName;
    private final long size;
    private final String sha256;
    private final FileChannel channel;

    SharedFileSource(String fileName, long size, String sha256, FileChannel channel) {
        this.fileName = fileName;
        this.size = size;
        this.sha256 = sha256;
        this.channel = channel;
    }

    public String fileName() {
        return fileName;
    }

    public long size() {
        return size;
    }

    public String sha256() {
        return sha256;
    }

    public int read(ByteBuffer buffer) throws IOException {
        return channel.read(buffer);
    }

    public long currentSize() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
