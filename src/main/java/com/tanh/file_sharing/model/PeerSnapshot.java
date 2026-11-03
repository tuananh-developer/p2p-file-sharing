package com.tanh.file_sharing.model;

import java.util.List;
import java.util.UUID;

public record PeerSnapshot(
        UUID peerId,
        PeerStatus status,
        String bindAddress,
        int port,
        List<String> addresses) {
}
