package com.tanh.file_sharing.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.model.ConnectionStatus;
import com.tanh.file_sharing.model.PeerStatus;
import com.tanh.file_sharing.model.RemotePeerSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class PeerServiceTest {
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
