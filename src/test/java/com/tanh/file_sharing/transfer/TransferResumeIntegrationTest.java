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

    @Test
    void applicationShutdownPausesAfterPersistingARangeAndNewPeerCanResume() throws Exception {
        byte[] content = new byte[10 * RANGE_BYTES + 29];
        new Random(20261102).nextBytes(content);
        Path sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("shutdown-source"));
        Path destination = Files.createDirectory(temporaryDirectory.resolve("shutdown-destination"));
        Files.write(sourceDirectory.resolve("large-archive.bin"), content);

        CountDownLatch firstRangeSaved = new CountDownLatch(1);
        CountDownLatch releaseProgressCallback = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean firstProgress = new java.util.concurrent.atomic.AtomicBoolean();
        EventPublisher blockingEvents = (eventName, data) -> {
            if ("transferProgress".equals(eventName) && firstProgress.compareAndSet(false, true)) {
                firstRangeSaved.countDown();
                try {
                    releaseProgressCallback.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        PeerService firstSource = new PeerService(new ObjectMapper(), EventPublisher.noop());
        PeerService firstReceiver = new PeerService(new ObjectMapper(), blockingEvents);
        PeerService restartedSource = null;
        PeerService restartedReceiver = null;
        UUID taskId = null;
        Path manifestPath = ResumeManifestStore.manifestPath(destination.resolve("large-archive.bin.part"));
        try {
            int sourcePort = availablePort();
            int receiverPort = distinctPort(sourcePort);
            var sourcePeer = firstSource.startPeer("127.0.0.1", sourcePort);
            firstSource.setSharedDirectory(sourceDirectory.toString());
            firstReceiver.startPeer("127.0.0.1", receiverPort);
            firstReceiver.connectPeer("127.0.0.1", sourcePort);

            var accepted = firstReceiver.downloadFileFromPeers(
                    List.of(sourcePeer.peerId()), "large-archive.bin", destination.toString());
            taskId = accepted.taskId();
            assertTrue(firstRangeSaved.await(15, TimeUnit.SECONDS),
                    "The first verified range should be persisted before shutdown.");

            firstReceiver.close();
            releaseProgressCallback.countDown();

            var paused = firstReceiver.listTransfers().stream()
                    .filter(task -> task.taskId().equals(taskId))
                    .findFirst().orElseThrow();
            assertEquals(TransferStatus.PAUSED, paused.status(), paused.detail());
            assertTrue(Files.exists(manifestPath));
            assertTrue(Files.exists(destination.resolve("large-archive.bin.part")));
            assertTrue(paused.completedChunks() >= 1);
            assertTrue(paused.completedChunks() < paused.totalChunks());

            firstSource.close();
            restartedSource = new PeerService(new ObjectMapper(), EventPublisher.noop());
            restartedReceiver = new PeerService(new ObjectMapper(), EventPublisher.noop());
            int restartedSourcePort = availablePort();
            int restartedReceiverPort = distinctPort(restartedSourcePort);
            var newSourcePeer = restartedSource.startPeer("127.0.0.1", restartedSourcePort);
            restartedSource.setSharedDirectory(sourceDirectory.toString());
            restartedReceiver.startPeer("127.0.0.1", restartedReceiverPort);
            var newConnection = restartedReceiver.connectPeer("127.0.0.1", restartedSourcePort);

            var recoverable = restartedReceiver.listResumableDownloads(destination.toString());
            assertEquals(1, recoverable.size());
            assertEquals(taskId, recoverable.getFirst().taskId());
            assertEquals(paused.completedChunks(), recoverable.getFirst().completedChunks());
            assertNotEquals(sourcePeer.peerId(), newSourcePeer.peerId());

            restartedReceiver.resumeDownload(
                    taskId, List.of(newConnection.peerId()), destination.toString());
            UUID resumedTaskId = taskId;
            await(() -> restartedReceiver.listTransfers().stream()
                    .anyMatch(task -> task.taskId().equals(resumedTaskId) && task.status().isTerminal()));

            var completed = restartedReceiver.listTransfers().stream()
                    .filter(task -> task.taskId().equals(resumedTaskId))
                    .findFirst().orElseThrow();
            assertEquals(TransferStatus.COMPLETED, completed.status(), completed.detail());
            assertArrayEquals(content, Files.readAllBytes(destination.resolve("large-archive.bin")));
            assertFalse(Files.exists(manifestPath));
            assertFalse(Files.exists(destination.resolve("large-archive.bin.part")));
        } finally {
            releaseProgressCallback.countDown();
            if (restartedReceiver != null) {
                restartedReceiver.close();
            }
            if (restartedSource != null) {
                restartedSource.close();
            }
            firstReceiver.close();
            firstSource.close();
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
