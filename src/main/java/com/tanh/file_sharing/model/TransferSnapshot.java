package com.tanh.file_sharing.model;

import java.util.UUID;

/** Immutable view of a download task for the Electron/UI layer. */
public record TransferSnapshot(
        UUID taskId,
        UUID peerId,
        String fileName,
        String targetFileName,
        TransferStatus status,
        long receivedBytes,
        long totalBytes,
        String detail) {
}
