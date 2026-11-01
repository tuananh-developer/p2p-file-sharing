package com.tanh.file_sharing.model;

/** Lifecycle of one download task. */
public enum TransferStatus {
    QUEUED,
    CONNECTING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == PAUSED;
    }
}
