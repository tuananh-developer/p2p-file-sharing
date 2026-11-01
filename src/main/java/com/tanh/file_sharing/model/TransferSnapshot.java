package com.tanh.file_sharing.model;

import java.util.List;
import java.util.UUID;

/** Immutable view of a single- or multi-source download task for the desktop UI. */
public record TransferSnapshot(
        UUID taskId,
        UUID peerId,
        String fileName,
        String targetFileName,
        TransferStatus status,
        long receivedBytes,
        long totalBytes,
        String detail,
        List<UUID> sourcePeerIds,
        int totalChunks,
        int completedChunks) {
    public TransferSnapshot {
        sourcePeerIds = sourcePeerIds == null ? List.of() : List.copyOf(sourcePeerIds);
    }
}
