package com.tanh.file_sharing.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Serializes all bridge responses/events onto stdout as one JSON object per line. */
public final class JsonLineWriter {
    private static final Logger LOGGER = Logger.getLogger(JsonLineWriter.class.getName());

    private final ObjectMapper objectMapper;
    private final BufferedWriter writer;

    public JsonLineWriter(ObjectMapper objectMapper) {
        this(objectMapper, new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8)));
    }

    JsonLineWriter(ObjectMapper objectMapper, BufferedWriter writer) {
        this.objectMapper = objectMapper;
        this.writer = writer;
    }

    public synchronized void write(Map<String, ?> message) {
        try {
            writer.write(objectMapper.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, "Could not write a JSON Lines message to stdout.", exception);
        }
    }
}
