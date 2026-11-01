package com.tanh.file_sharing.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.peer.PeerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonLineBridgeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void processesCommandsAndEchoesRequestIdsInJsonLinesResponses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringWriter captured = new StringWriter();
        JsonLineWriter writer = new JsonLineWriter(mapper, new BufferedWriter(captured));
        EventPublisher events = (name, data) -> writer.write(Map.of(
                "type", "event", "event", name, "data", data));
        PeerService peer = new PeerService(mapper, events);

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String commands = String.join("\n",
                mapper.writeValueAsString(Map.of(
                        "id", "start-1", "command", "peer.start",
                        "payload", Map.of("bindAddress", "127.0.0.1", "port", port))),
                mapper.writeValueAsString(Map.of("id", "status-1", "command", "peer.status", "payload", Map.of())),
                mapper.writeValueAsString(Map.of("id", "shutdown-1", "command", "app.shutdown", "payload", Map.of())),
                "");

        new JsonLineBridge(mapper, writer, peer, new StringReader(commands)).run();

        List<JsonNode> responses = new ArrayList<>();
        for (String line : captured.toString().split("\\R")) {
            if (!line.isBlank()) {
                JsonNode message = mapper.readTree(line);
                if ("response".equals(message.path("type").asText())) {
                    responses.add(message);
                }
            }
        }

        assertEquals(3, responses.size());
        assertEquals("start-1", responses.get(0).path("id").asText());
        assertTrue(responses.get(0).path("success").asBoolean());
        assertEquals("status-1", responses.get(1).path("id").asText());
        assertTrue(responses.get(1).path("success").asBoolean());
        assertEquals("shutdown-1", responses.get(2).path("id").asText());
        assertTrue(responses.get(2).path("success").asBoolean());
    }

    @Test
    void exposesSharedDirectoryAndFileCatalogCommandsThroughJsonLines() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringWriter captured = new StringWriter();
        JsonLineWriter writer = new JsonLineWriter(mapper, new BufferedWriter(captured));
        PeerService peer = new PeerService(mapper, EventPublisher.noop());

        Path shared = Files.createDirectory(temporaryDirectory.resolve("share"));
        Files.writeString(shared.resolve("hello.txt"), "hello");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        String commands = String.join("\n",
                mapper.writeValueAsString(Map.of(
                        "id", "start", "command", "peer.start",
                        "payload", Map.of("bindAddress", "127.0.0.1", "port", port))),
                mapper.writeValueAsString(Map.of(
                        "id", "set-share", "command", "share.setDirectory",
                        "payload", Map.of("path", shared.toString()))),
                mapper.writeValueAsString(Map.of("id", "get-share", "command", "share.getDirectory", "payload", Map.of())),
                mapper.writeValueAsString(Map.of("id", "list-local", "command", "files.listLocal", "payload", Map.of())),
                mapper.writeValueAsString(Map.of(
                        "id", "search", "command", "files.search", "payload", Map.of("keyword", "hello"))),
                mapper.writeValueAsString(Map.of("id", "shutdown", "command", "app.shutdown", "payload", Map.of())),
                "");

        new JsonLineBridge(mapper, writer, peer, new StringReader(commands)).run();

        Map<String, JsonNode> responses = new LinkedHashMap<>();
        for (String line : captured.toString().split("\\R")) {
            if (!line.isBlank()) {
                JsonNode message = mapper.readTree(line);
                if ("response".equals(message.path("type").asText())) {
                    responses.put(message.path("id").asText(), message);
                }
            }
        }

        assertEquals(6, responses.size());
        assertTrue(responses.values().stream().allMatch(response -> response.path("success").asBoolean()));
        assertTrue(responses.get("get-share").path("data").path("sharedDirectory").path("configured").asBoolean());
        assertEquals(1, responses.get("list-local").path("data").path("files").size());
        assertEquals("hello.txt",
                responses.get("list-local").path("data").path("files").get(0).path("fileName").asText());
        assertTrue(responses.get("search").path("data").path("results").isArray());
        assertEquals(0, responses.get("search").path("data").path("results").size());
    }
}
