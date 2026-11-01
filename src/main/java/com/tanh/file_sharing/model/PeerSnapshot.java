package com.tanh.file_sharing.model;

import java.util.UUID;

public record PeerSnapshot(
        UUID peerId,
        PeerStatus status,
        String bindAddress,
        int port) {
}
