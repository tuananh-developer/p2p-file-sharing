package com.tanh.file_sharing.model;

import java.util.List;
import java.util.UUID;

public record PeerSnapshot(
        UUID peerId,
        PeerStatus status,
        String bindAddress,
        int port,
        List<String> addresses,
        List<NetworkAddressSnapshot> networkAddresses) {

    /**
     * Backward-compatible constructor for callers that only have plain
     * address strings.
     */
    public PeerSnapshot(UUID peerId, PeerStatus status, String bindAddress, int port, List<String> addresses) {
        this(peerId, status, bindAddress, port, addresses, List.of());
    }
}
