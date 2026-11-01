package com.tanh.file_sharing.model;

import java.util.UUID;

/** A per-peer error; one failed peer must not discard results from other peers. */
public record FileSearchError(UUID peerId, String code, String message) {
}
