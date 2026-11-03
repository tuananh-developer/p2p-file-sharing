package com.tanh.file_sharing.model;

import java.util.UUID;

/** A validated interrupted download that can be resumed from its destination folder. */
public record ResumableDownloadSnapshot(
        UUID taskId,
        String fileName,
        String targetFileName,
        long totalBytes,
        long receivedBytes,
        long totalChunks,
        long completedChunks) {
}
