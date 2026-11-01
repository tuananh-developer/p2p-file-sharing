package com.tanh.file_sharing.model;

import java.util.UUID;

/** File metadata returned by a connected peer, annotated with its source. */
public record RemoteFileSearchResult(
        UUID peerId,
        String host,
        int port,
        String fileName,
        long size) {
}
