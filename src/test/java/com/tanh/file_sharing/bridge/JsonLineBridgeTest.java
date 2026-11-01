package com.tanh.file_sharing.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.peer.PeerService;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonLineBridgeTest {
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
                "{\"id\":\"start-1\",\"command\":\"peer.start\",\"payload\":{\"bindAddress\":\"127.0.0.1\",\"port\":" + port + "}}",
                "{\"id\":\"status-1\",\"command\":\"peer.status\",\"payload\":{}}",
                "{\"id\":\"shutdown-1\",\"command\":\"app.shutdown\",\"payload\":{}}",
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
}
