package com.tanh.file_sharing.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ResumeManifestStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reloadsOnlyRangesWhosePersistedHashesStillMatchThePartialFile() throws Exception {
        byte[] content = "abcdefghijkl".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path part = temporaryDirectory.resolve("download.bin.part");
        Path manifestPath = ResumeManifestStore.manifestPath(part);
        Files.write(part, content);

        ResumeManifestStore.Manifest manifest = ResumeManifestStore.create(
                UUID.randomUUID(), "source.bin", "download.bin",
                content.length, sha256(content), 4);
        manifest.chunkHashes().set(0, sha256(java.util.Arrays.copyOfRange(content, 0, 4)));
        manifest.chunkHashes().set(2, sha256(java.util.Arrays.copyOfRange(content, 8, 12)));
        ResumeManifestStore.save(manifestPath, manifest);

        ResumeManifestStore.Manifest restored = ResumeManifestStore.loadAndVerify(manifestPath);
        assertEquals(2, restored.completedChunks());
        assertEquals(8, restored.completedBytes());

        // Simulate disk corruption after the application was closed.
        try (var channel = java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {'X'}), 0);
            channel.force(true);
        }

        ResumeManifestStore.Manifest repaired = ResumeManifestStore.loadAndVerify(manifestPath);
        assertFalse(repaired.isCompleted(0));
        assertTrue(repaired.isCompleted(2));
        assertEquals(1, repaired.completedChunks());
        assertEquals(4, repaired.completedBytes());
    }

    @Test
    void rejectsSymbolicLinkAsPartialDownload() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.bin");
        Files.writeString(outside, "do not follow");
        Path part = temporaryDirectory.resolve("linked.bin.part");
        try {
            Files.createSymbolicLink(part, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable in this environment.");
        }
        Path manifestPath = ResumeManifestStore.manifestPath(part);
        ResumeManifestStore.Manifest manifest = ResumeManifestStore.create(
                UUID.randomUUID(), "source.bin", "linked.bin", 0,
                sha256(new byte[0]), 4);
        ResumeManifestStore.save(manifestPath, manifest);

        assertThrows(IOException.class, () -> ResumeManifestStore.loadAndVerify(manifestPath));
    }

    @Test
    void rejectsManifestStoredUnderDifferentTargetFilename() throws Exception {
        Path part = temporaryDirectory.resolve("other.bin.part");
        Files.write(part, new byte[0]);
        Path manifestPath = ResumeManifestStore.manifestPath(part);
        ResumeManifestStore.Manifest manifest = ResumeManifestStore.create(
                UUID.randomUUID(), "source.bin", "target.bin", 0,
                sha256(new byte[0]), 4);
        ResumeManifestStore.save(manifestPath, manifest);

        assertThrows(IOException.class, () -> ResumeManifestStore.loadAndVerify(manifestPath));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
