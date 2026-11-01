package com.tanh.file_sharing.model;

import java.util.UUID;

public record RemotePeerSnapshot(
        UUID peerId,
        String host,
        int port,
        ConnectionDirection direction,
        ConnectionStatus status,
        String connectedAt) {
}
