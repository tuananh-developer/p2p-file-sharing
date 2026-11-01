package com.tanh.file_sharing.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.model.ConnectionStatus;
import com.tanh.file_sharing.model.PeerStatus;
import com.tanh.file_sharing.model.RemotePeerSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class PeerServiceTest {
    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void startsAndStopsAndReleasesListeningPort() throws Exception {
        int port = availablePort();
        PeerService peer = new PeerService(mapper, EventPublisher.noop());
        try {
            assertEquals(PeerStatus.RUNNING, peer.startPeer("127.0.0.1", port).status());
            assertEquals(PeerStatus.STOPPED, peer.stopPeer().status());
            try (ServerSocket rebound = new ServerSocket(port)) {
                assertTrue(rebound.isBound());
            }
        } finally {
            peer.close();
        }
    }

    @Test
    void rejectsInvalidPortAndPortAlreadyInUse() throws Exception {
        PeerService peer = new PeerService(mapper, EventPublisher.noop());
        try {
            PeerException invalid = assertThrows(PeerException.class,
                    () -> peer.startPeer("127.0.0.1", 0));
            assertEquals("INVALID_PORT", invalid.getCode());
        } finally {
            peer.close();
        }

        try (ServerSocket occupied = new ServerSocket(0)) {
            PeerService another = new PeerService(mapper, EventPublisher.noop());
            try {
                PeerException inUse = assertThrows(PeerException.class,
                        () -> another.startPeer("127.0.0.1", occupied.getLocalPort()));
                assertEquals("PORT_IN_USE", inUse.getCode());
                assertEquals(PeerStatus.STOPPED, another.getStatus());
            } finally {
                another.close();
            }
        }
    }

    @Test
    void twoPeersHandshakeAndDisconnectOnBothSides() throws Exception {
        int firstPort = availablePort();
        int secondPort = distinctPort(firstPort);
        PeerService first = new PeerService(mapper, EventPublisher.noop());
        PeerService second = new PeerService(mapper, EventPublisher.noop());
        try {
            first.startPeer("127.0.0.1", firstPort);
            second.startPeer("127.0.0.1", secondPort);

            RemotePeerSnapshot connected = first.connectPeer("127.0.0.1", secondPort);
            assertEquals(ConnectionStatus.CONNECTED, connected.status());
            await(() -> second.listPeers().stream().anyMatch(peer ->
                    peer.peerId().equals(first.getPeerStatus().peerId())
                            && peer.status() == ConnectionStatus.CONNECTED));

            first.disconnectPeer(connected.peerId());
            await(() -> second.listPeers().stream().anyMatch(peer ->
                    peer.peerId().equals(first.getPeerStatus().peerId())
                            && peer.status() == ConnectionStatus.DISCONNECTED));

            first.stopPeer();
            second.stopPeer();
            assertEquals(PeerStatus.STOPPED, first.getStatus());
            assertEquals(PeerStatus.STOPPED, second.getStatus());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void simultaneousConnectAttemptsConvergeToOnePeerEntry() throws Exception {
        int firstPort = availablePort();
        int secondPort = distinctPort(firstPort);
        PeerService first = new PeerService(mapper, EventPublisher.noop());
        PeerService second = new PeerService(mapper, EventPublisher.noop());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            first.startPeer("127.0.0.1", firstPort);
            second.startPeer("127.0.0.1", secondPort);
            Future<RemotePeerSnapshot> a = executor.submit(() -> first.connectPeer("127.0.0.1", secondPort));
            Future<RemotePeerSnapshot> b = executor.submit(() -> second.connectPeer("127.0.0.1", firstPort));
            assertEquals(ConnectionStatus.CONNECTED, a.get(7, TimeUnit.SECONDS).status());
            assertEquals(ConnectionStatus.CONNECTED, b.get(7, TimeUnit.SECONDS).status());
            await(() -> first.listPeers().size() == 1 && second.listPeers().size() == 1
                    && first.listPeers().get(0).status() == ConnectionStatus.CONNECTED
                    && second.listPeers().get(0).status() == ConnectionStatus.CONNECTED);
        } finally {
            executor.shutdownNow();
            first.close();
            second.close();
        }
    }

    @Test
    void sharesTopLevelFilesAndSearchesAcrossPeersWithoutLosingOtherResultsOnFailure() throws Exception {
        int ownerPort = availablePort();
        int consumerPort = distinctPort(ownerPort);
        int unavailablePort = distinctPort(ownerPort);
        while (unavailablePort == consumerPort) {
            unavailablePort = distinctPort(ownerPort);
        }

        Path shared = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("provider-share"));
        java.nio.file.Files.writeString(shared.resolve("Report-Q4.pdf"), "quarterly report");
        java.nio.file.Files.writeString(shared.resolve("photo.png"), "image");
        Path nested = java.nio.file.Files.createDirectory(shared.resolve("nested"));
        java.nio.file.Files.writeString(nested.resolve("Report-hidden.pdf"), "not listed");

        PeerService owner = new PeerService(mapper, EventPublisher.noop());
        PeerService consumer = new PeerService(mapper, EventPublisher.noop());
        PeerService notSharing = new PeerService(mapper, EventPublisher.noop());
        try {
            var ownerSnapshot = owner.startPeer("127.0.0.1", ownerPort);
            consumer.startPeer("127.0.0.1", consumerPort);
            var notSharingSnapshot = notSharing.startPeer("127.0.0.1", unavailablePort);
            owner.setSharedDirectory(shared.toString());

            consumer.connectPeer("127.0.0.1", ownerPort);
            consumer.connectPeer("127.0.0.1", unavailablePort);

            var directList = consumer.listRemoteFiles(ownerSnapshot.peerId());
            assertEquals(2, directList.size());
            assertTrue(directList.stream().anyMatch(file -> file.fileName().equals("Report-Q4.pdf")));
            assertFalse(directList.stream().anyMatch(file -> file.fileName().equals("Report-hidden.pdf")));

            var search = consumer.searchFiles("REPORT", null);
            assertEquals(1, search.results().size());
            assertEquals("Report-Q4.pdf", search.results().get(0).fileName());
            assertEquals(ownerSnapshot.peerId(), search.results().get(0).peerId());
            assertEquals("127.0.0.1", search.results().get(0).host());
            assertEquals(16L, search.results().get(0).size());
            assertEquals(1, search.errors().size());
            assertEquals(notSharingSnapshot.peerId(), search.errors().get(0).peerId());
            assertEquals("NOT_SHARING", search.errors().get(0).code());

            var noMatch = consumer.searchFiles("does-not-exist", List.of(ownerSnapshot.peerId()));
            assertTrue(noMatch.results().isEmpty());
            assertTrue(noMatch.errors().isEmpty());
        } finally {
            consumer.close();
            owner.close();
            notSharing.close();
        }
    }

    @Test
    void supportsConcurrentFileListRequestsInBothDirectionsOnOneConnection() throws Exception {
        int firstPort = availablePort();
        int secondPort = distinctPort(firstPort);
        PeerService first = new PeerService(mapper, EventPublisher.noop());
        PeerService second = new PeerService(mapper, EventPublisher.noop());

        Path firstShare = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("first-share"));
        Path secondShare = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("second-share"));
        java.nio.file.Files.writeString(firstShare.resolve("from-first.txt"), "first");
        java.nio.file.Files.writeString(secondShare.resolve("from-second.txt"), "second");

        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            var firstSnapshot = first.startPeer("127.0.0.1", firstPort);
            var secondSnapshot = second.startPeer("127.0.0.1", secondPort);
            first.setSharedDirectory(firstShare.toString());
            second.setSharedDirectory(secondShare.toString());
            first.connectPeer("127.0.0.1", secondPort);

            Future<List<com.tanh.file_sharing.model.RemoteFileSearchResult>> firstToSecond =
                    requests.submit(() -> first.listRemoteFiles(secondSnapshot.peerId()));
            Future<List<com.tanh.file_sharing.model.RemoteFileSearchResult>> secondToFirst =
                    requests.submit(() -> second.listRemoteFiles(firstSnapshot.peerId()));

            assertEquals("from-second.txt", firstToSecond.get(7, TimeUnit.SECONDS).get(0).fileName());
            assertEquals("from-first.txt", secondToFirst.get(7, TimeUnit.SECONDS).get(0).fileName());
        } finally {
            requests.shutdownNow();
            first.close();
            second.close();
        }
    }

    @Test
    void downloadsMultiChunkFileVerifiesHashAndNeverOverwritesExistingDestination() throws Exception {
        int sourcePort = availablePort();
        int receiverPort = distinctPort(sourcePort);
        PeerService source = new PeerService(mapper, EventPublisher.noop());
        PeerService receiver = new PeerService(mapper, EventPublisher.noop());

        Path shared = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("download-source"));
        Path destination = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("download-target"));
        byte[] content = new byte[96 * 1024 + 731];
        new java.util.Random(42).nextBytes(content);
        java.nio.file.Files.write(shared.resolve("payload.bin"), content);
        java.nio.file.Files.writeString(destination.resolve("payload.bin"), "keep-existing-file");

        try {
            var sourceSnapshot = source.startPeer("127.0.0.1", sourcePort);
            receiver.startPeer("127.0.0.1", receiverPort);
            source.setSharedDirectory(shared.toString());
            receiver.connectPeer("127.0.0.1", sourcePort);

            var accepted = receiver.downloadFile(
                    sourceSnapshot.peerId(), "payload.bin", destination.toString());
            assertEquals("payload (1).bin", accepted.targetFileName());

            await(() -> receiver.listTransfers().stream().anyMatch(task ->
                    task.taskId().equals(accepted.taskId())
                            && task.status() == com.tanh.file_sharing.model.TransferStatus.COMPLETED));

            Path downloaded = destination.resolve(accepted.targetFileName());
            assertArrayEquals(content, java.nio.file.Files.readAllBytes(downloaded));
            assertEquals("keep-existing-file",
                    java.nio.file.Files.readString(destination.resolve("payload.bin")));
            assertFalse(java.nio.file.Files.exists(destination.resolve(accepted.targetFileName() + ".part")));
            assertEquals(content.length, receiver.listTransfers().stream()
                    .filter(task -> task.taskId().equals(accepted.taskId()))
                    .findFirst().orElseThrow().receivedBytes());
        } finally {
            receiver.close();
            source.close();
        }
    }

    @Test
    void reportsMissingRemoteFileAndDoesNotLeaveTemporaryFiles() throws Exception {
        int sourcePort = availablePort();
        int receiverPort = distinctPort(sourcePort);
        PeerService source = new PeerService(mapper, EventPublisher.noop());
        PeerService receiver = new PeerService(mapper, EventPublisher.noop());
        Path shared = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("empty-source"));
        Path destination = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("missing-target"));

        try {
            var sourceSnapshot = source.startPeer("127.0.0.1", sourcePort);
            receiver.startPeer("127.0.0.1", receiverPort);
            source.setSharedDirectory(shared.toString());
            receiver.connectPeer("127.0.0.1", sourcePort);

            var accepted = receiver.downloadFile(
                    sourceSnapshot.peerId(), "missing.bin", destination.toString());
            await(() -> receiver.listTransfers().stream().anyMatch(task ->
                    task.taskId().equals(accepted.taskId())
                            && task.status() == com.tanh.file_sharing.model.TransferStatus.FAILED));

            var failed = receiver.listTransfers().stream()
                    .filter(task -> task.taskId().equals(accepted.taskId()))
                    .findFirst().orElseThrow();
            assertTrue(failed.detail().contains("FILE_UNAVAILABLE"));
            try (var files = java.nio.file.Files.list(destination)) {
                assertEquals(0L, files.count());
            }
        } finally {
            receiver.close();
            source.close();
        }
    }

    @Test
    void canCancelAnActiveDownloadAndRemovesThePartialFile() throws Exception {
        int sourcePort = availablePort();
        int receiverPort = distinctPort(sourcePort);
        PeerService source = new PeerService(mapper, EventPublisher.noop());
        PeerService receiver = new PeerService(mapper, EventPublisher.noop());
        Path shared = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("cancel-source"));
        Path destination = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("cancel-target"));
        byte[] content = new byte[32 * 1024 * 1024];
        new java.util.Random(7).nextBytes(content);
        java.nio.file.Files.write(shared.resolve("large.bin"), content);

        try {
            var sourceSnapshot = source.startPeer("127.0.0.1", sourcePort);
            receiver.startPeer("127.0.0.1", receiverPort);
            source.setSharedDirectory(shared.toString());
            receiver.connectPeer("127.0.0.1", sourcePort);

            var accepted = receiver.downloadFile(
                    sourceSnapshot.peerId(), "large.bin", destination.toString());
            receiver.cancelDownload(accepted.taskId());

            await(() -> receiver.listTransfers().stream().anyMatch(task ->
                    task.taskId().equals(accepted.taskId())
                            && task.status() == com.tanh.file_sharing.model.TransferStatus.CANCELLED));
            await(() -> isDirectoryEmpty(destination));
            try (var files = java.nio.file.Files.list(destination)) {
                assertEquals(0L, files.count());
            }
        } finally {
            receiver.close();
            source.close();
        }
    }

    @Test
    void downloadsDifferentRangesFromTwoIdenticalPeersAndReassemblesVerifiedFile() throws Exception {
        int firstPort = availablePort();
        int secondPort = distinctPort(firstPort);
        int receiverPort = distinctPort(secondPort);
        PeerService firstSource = new PeerService(mapper, EventPublisher.noop());
        PeerService secondSource = new PeerService(mapper, EventPublisher.noop());
        PeerService receiver = new PeerService(mapper, EventPublisher.noop());

        Path firstShare = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("multi-source-a"));
        Path secondShare = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("multi-source-b"));
        Path destination = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("multi-target"));
        byte[] content = new byte[10 * 1024 * 1024 + 317];
        new java.util.Random(73).nextBytes(content);
        java.nio.file.Files.write(firstShare.resolve("dataset.bin"), content);
        java.nio.file.Files.write(secondShare.resolve("dataset.bin"), content);
        java.nio.file.Files.writeString(destination.resolve("dataset.bin"), "preserve-existing");

        try {
            var first = firstSource.startPeer("127.0.0.1", firstPort);
            var second = secondSource.startPeer("127.0.0.1", secondPort);
            receiver.startPeer("127.0.0.1", receiverPort);
            firstSource.setSharedDirectory(firstShare.toString());
            secondSource.setSharedDirectory(secondShare.toString());
            receiver.connectPeer("127.0.0.1", firstPort);
            receiver.connectPeer("127.0.0.1", secondPort);

            var accepted = receiver.downloadFileFromPeers(
                    List.of(first.peerId(), second.peerId()), "dataset.bin", destination.toString());
            assertEquals("dataset (1).bin", accepted.targetFileName());

            await(() -> receiver.listTransfers().stream().anyMatch(task ->
                    task.taskId().equals(accepted.taskId())
                            && task.status().isTerminal()));

            var completed = receiver.listTransfers().stream()
                    .filter(task -> task.taskId().equals(accepted.taskId()))
                    .findFirst().orElseThrow();
            if (completed.status() != com.tanh.file_sharing.model.TransferStatus.COMPLETED) {
                System.err.println("MULTI_SOURCE_DIAGNOSTIC status=" + completed.status()
                        + " detail=" + completed.detail()
                        + " receivedBytes=" + completed.receivedBytes()
                        + " totalBytes=" + completed.totalBytes()
                        + " completedChunks=" + completed.completedChunks()
                        + " totalChunks=" + completed.totalChunks()
                        + " sources=" + completed.sourcePeerIds());
            }
            assertEquals(
                    com.tanh.file_sharing.model.TransferStatus.COMPLETED,
                    completed.status(),
                    "Transfer did not complete: status=" + completed.status()
                            + ", detail=" + completed.detail()
                            + ", receivedBytes=" + completed.receivedBytes()
                            + ", totalBytes=" + completed.totalBytes()
                            + ", completedChunks=" + completed.completedChunks()
                            + ", totalChunks=" + completed.totalChunks()
                            + ", sources=" + completed.sourcePeerIds());
            assertArrayEquals(content,
                    java.nio.file.Files.readAllBytes(destination.resolve(accepted.targetFileName())));
            assertEquals("preserve-existing",
                    java.nio.file.Files.readString(destination.resolve("dataset.bin")));
            assertEquals(content.length, completed.receivedBytes());
            assertEquals(3, completed.totalChunks());
            assertEquals(3, completed.completedChunks());
            assertEquals(2, completed.sourcePeerIds().size());
            assertTrue(completed.detail().contains("SHA-256 verified"));
        } finally {
            receiver.close();
            secondSource.close();
            firstSource.close();
        }
    }

    @Test
    void rejectsMultiSourcePeersWhenSameNameAndSizeHaveDifferentHashes() throws Exception {
        int firstPort = availablePort();
        int secondPort = distinctPort(firstPort);
        int receiverPort = distinctPort(secondPort);
        PeerService firstSource = new PeerService(mapper, EventPublisher.noop());
        PeerService secondSource = new PeerService(mapper, EventPublisher.noop());
        PeerService receiver = new PeerService(mapper, EventPublisher.noop());

        Path firstShare = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("mismatch-source-a"));
        Path secondShare = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("mismatch-source-b"));
        Path destination = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("mismatch-target"));
        byte[] firstContent = "version-A-contents".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] secondContent = "version-B-contents".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(firstContent.length, secondContent.length);
        java.nio.file.Files.write(firstShare.resolve("same.bin"), firstContent);
        java.nio.file.Files.write(secondShare.resolve("same.bin"), secondContent);

        try {
            var first = firstSource.startPeer("127.0.0.1", firstPort);
            var second = secondSource.startPeer("127.0.0.1", secondPort);
            receiver.startPeer("127.0.0.1", receiverPort);
            firstSource.setSharedDirectory(firstShare.toString());
            secondSource.setSharedDirectory(secondShare.toString());
            receiver.connectPeer("127.0.0.1", firstPort);
            receiver.connectPeer("127.0.0.1", secondPort);

            var accepted = receiver.downloadFileFromPeers(
                    List.of(first.peerId(), second.peerId()), "same.bin", destination.toString());
            await(() -> receiver.listTransfers().stream().anyMatch(task ->
                    task.taskId().equals(accepted.taskId())
                            && task.status() == com.tanh.file_sharing.model.TransferStatus.FAILED));

            var failed = receiver.listTransfers().stream()
                    .filter(task -> task.taskId().equals(accepted.taskId()))
                    .findFirst().orElseThrow();
            assertTrue(failed.detail().contains("SOURCE_MISMATCH"));
            try (var files = java.nio.file.Files.list(destination)) {
                assertEquals(0L, files.count());
            }
        } finally {
            receiver.close();
            secondSource.close();
            firstSource.close();
        }
    }

    @Test
    void sharedDirectoryConfigurationIsNotPersistedBetweenPeerServiceInstances() throws Exception {
        Path shared = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("session-share"));
        PeerService firstSession = new PeerService(mapper, EventPublisher.noop());
        PeerService nextSession = new PeerService(mapper, EventPublisher.noop());
        try {
            firstSession.setSharedDirectory(shared.toString());

            assertTrue(firstSession.getSharedDirectory().configured());
            assertFalse(nextSession.getSharedDirectory().configured());
            assertThrows(PeerException.class, nextSession::listLocalSharedFiles);
        } finally {
            firstSession.close();
            nextSession.close();
        }
    }

    private static boolean isDirectoryEmpty(Path directory) {
        try (var files = java.nio.file.Files.list(directory)) {
            return files.findAny().isEmpty();
        } catch (IOException exception) {
            return false;
        }
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met before timeout.");
    }
}
