package com.tanh.file_sharing.model;

/** Session-scoped shared directory configuration. */
public record SharedDirectorySnapshot(boolean configured, String path) {
}
