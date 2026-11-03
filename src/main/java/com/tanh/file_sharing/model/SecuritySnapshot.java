package com.tanh.file_sharing.model;

import java.util.List;

public record SecuritySnapshot(
        String protocol,
        String localFingerprint,
        List<String> trustedFingerprints) {
}
