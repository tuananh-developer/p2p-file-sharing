package com.tanh.file_sharing.model;

import java.util.List;

/** Aggregated file search results and isolated per-peer failures. */
public record FileSearchResponse(
        List<RemoteFileSearchResult> results,
        List<FileSearchError> errors) {
    public FileSearchResponse {
        results = List.copyOf(results);
        errors = List.copyOf(errors);
    }
}
