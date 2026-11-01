package com.tanh.file_sharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.bridge.JsonLineBridge;
import com.tanh.file_sharing.bridge.JsonLineWriter;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.peer.PeerService;

/** Entry point for the standalone Java P2P core process. */
public final class FileSharingApplication {
    private FileSharingApplication() {
    }

    public static void main(String[] args) {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonLineWriter output = new JsonLineWriter(objectMapper);
        EventPublisher events = (eventName, data) -> output.write(java.util.Map.of(
                "type", "event",
                "event", eventName,
                "data", data == null ? java.util.Map.of() : data));
        PeerService peerService = new PeerService(objectMapper, events);

        Runtime.getRuntime().addShutdownHook(new Thread(peerService::close, "p2p-shutdown"));
        new JsonLineBridge(objectMapper, output, peerService).run();
    }
}
