package com.tanh.file_sharing.model;

/** Public metadata for one shareable file. File contents are never included. */
public record SharedFileSnapshot(String fileName, long size) {
}
