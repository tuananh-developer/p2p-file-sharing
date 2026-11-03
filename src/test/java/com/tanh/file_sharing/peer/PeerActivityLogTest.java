package com.tanh.file_sharing.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PeerActivityLogTest {
    @Test
    void peerLifecyclePublishesStructuredActivityEvents() throws Exception {
        List<Map<String, Object>> activities = new ArrayList<>();
        EventPublisher publisher = (eventName, data) -> {
            if ("activityLogged".equals(eventName)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> event = (Map<String, Object>) data;
                activities.add(event);
            }
        };
        PeerService peer = new PeerService(new ObjectMapper(), publisher);

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        try {
            peer.startPeer("127.0.0.1", port);
            peer.stopPeer();
        } finally {
            peer.close();
        }

        assertEquals(2, activities.size());
        assertActivity(activities.get(0), "Peer started on 127.0.0.1:" + port);
        assertActivity(activities.get(1), "Peer stopped");
    }

    private static void assertActivity(Map<String, Object> activity, String expectedMessage) {
        assertDoesNotThrow(() -> UUID.fromString((String) activity.get("id")));
        assertDoesNotThrow(() -> Instant.parse((String) activity.get("timestamp")));
        assertEquals("PEER", activity.get("category"));
        assertEquals("INFO", activity.get("level"));
        assertEquals(expectedMessage, activity.get("message"));
    }
}
