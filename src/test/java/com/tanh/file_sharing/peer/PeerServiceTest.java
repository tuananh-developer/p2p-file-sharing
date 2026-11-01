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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met before timeout.");
    }
}
