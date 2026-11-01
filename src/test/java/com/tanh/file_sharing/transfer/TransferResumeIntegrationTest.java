package com.tanh.file_sharing.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.model.TransferStatus;
import com.tanh.file_sharing.peer.PeerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TransferResumeIntegrationTest {
    private static final int RANGE_BYTES = 4 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void resumesMissingRangesFromANewPeerServiceInstance() throws Exception {
        byte[] content = new byte[RANGE_BYTES + 173];
        new Random(2026).nextBytes(content);
        Path sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectory(temporaryDirectory.resolve("destination"));
        Files.write(sourceDirectory.resolve("archive.bin"), content);

        UUID taskId = UUID.randomUUID();
        Path part = destination.resolve("archive.bin.part");
        Files.write(part, Arrays.copyOf(content, RANGE_BYTES));
        Path manifestPath = ResumeManifestStore.manifestPath(part);
        ResumeManifestStore.Manifest manifest = ResumeManifestStore.create(
                taskId, "archive.bin", "archive.bin", content.length, sha256(content), RANGE_BYTES);
        manifest.chunkHashes().set(0, sha256(Arrays.copyOf(content, RANGE_BYTES)));
        ResumeManifestStore.save(manifestPath, manifest);

        PeerService source = new PeerService(new ObjectMapper(), EventPublisher.noop());
        PeerService restartedReceiver = new PeerService(new ObjectMapper(), EventPublisher.noop());
        try {
            int sourcePort = availablePort();
            int receiverPort = distinctPort(sourcePort);
            source.startPeer("127.0.0.1", sourcePort);
            source.setSharedDirectory(sourceDirectory.toString());
            restartedReceiver.startPeer("127.0.0.1", receiverPort);
            var connected = restartedReceiver.connectPeer("127.0.0.1", sourcePort);

            var recoverable = restartedReceiver.listResumableDownloads(destination.toString());
            assertEquals(1, recoverable.size());
            assertEquals(taskId, recoverable.getFirst().taskId());
            assertEquals(1, recoverable.getFirst().completedChunks());
            assertEquals(RANGE_BYTES, recoverable.getFirst().receivedBytes());

            var accepted = restartedReceiver.resumeDownload(
                    taskId, List.of(connected.peerId()), destination.toString());
            assertEquals(taskId, accepted.taskId());

            await(() -> restartedReceiver.listTransfers().stream()
                    .anyMatch(task -> task.taskId().equals(taskId) && task.status().isTerminal()));
            var completed = restartedReceiver.listTransfers().stream()
                    .filter(task -> task.taskId().equals(taskId))
                    .findFirst().orElseThrow();
            assertEquals(TransferStatus.COMPLETED, completed.status(), completed.detail());
            assertEquals(2, completed.completedChunks());
            assertArrayEquals(content, Files.readAllBytes(destination.resolve("archive.bin")));
            assertFalse(Files.exists(part));
            assertFalse(Files.exists(manifestPath));
        } finally {
            restartedReceiver.close();
            source.close();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int distinctPort(int other) throws IOException {
        int port = availablePort();
        while (port == other) {
            port = availablePort();
        }
        return port;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Condition was not met before timeout.");
    }
}
