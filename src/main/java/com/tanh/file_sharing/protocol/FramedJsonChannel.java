package com.tanh.file_sharing.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Newline-delimited JSON framing for TCP streams. A frame is one UTF-8 JSON
 * document followed by LF. The frame limit prevents unbounded memory use.
 */
public final class FramedJsonChannel {
    public static final int MAX_FRAME_BYTES = 64 * 1024;

    private final java.io.BufferedInputStream input;
    private final java.io.BufferedOutputStream output;
    private final ObjectMapper objectMapper;

    public FramedJsonChannel(Socket socket, ObjectMapper objectMapper) throws IOException {
        this.input = new java.io.BufferedInputStream(socket.getInputStream());
        this.output = new java.io.BufferedOutputStream(socket.getOutputStream());
        this.objectMapper = objectMapper;
    }

    public JsonNode readJson() throws IOException {
        String frame = readFrame();
        if (frame == null) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(frame);
            if (json == null || !json.isObject()) {
                throw new IOException("A protocol frame must be a JSON object.");
            }
            return json;
        } catch (IOException exception) {
            throw new IOException("Invalid JSON frame.", exception);
        }
    }

    public void writeJson(Object value) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        if (bytes.length > MAX_FRAME_BYTES) {
            throw new IOException("Protocol frame exceeds " + MAX_FRAME_BYTES + " bytes.");
        }
        synchronized (output) {
            output.write(bytes);
            output.write('\n');
            output.flush();
        }
    }

    public void writeJson(Map<String, ?> value) throws IOException {
        writeJson((Object) value);
    }

    private String readFrame() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        boolean sawAnyByte = false;
        while (true) {
            int next = input.read();
            if (next == -1) {
                if (!sawAnyByte) {
                    return null;
                }
                throw new EOFException("Peer closed the stream in the middle of a frame.");
            }
            sawAnyByte = true;
            if (next == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            if (buffer.size() >= MAX_FRAME_BYTES) {
                throw new IOException("Protocol frame exceeds " + MAX_FRAME_BYTES + " bytes.");
            }
            buffer.write(next);
        }
    }
}
