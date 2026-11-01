package com.tanh.file_sharing.model;

import java.util.UUID;

/** Immediate acknowledgement returned after a download has been queued. */
public record DownloadAccepted(UUID taskId, String targetFileName, TransferStatus status) {
}
