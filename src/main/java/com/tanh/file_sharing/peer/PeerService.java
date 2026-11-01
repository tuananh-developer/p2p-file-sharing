package com.tanh.file_sharing.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.model.ConnectionDirection;
import com.tanh.file_sharing.model.ConnectionStatus;
import com.tanh.file_sharing.model.PeerSnapshot;
import com.tanh.file_sharing.model.PeerStatus;
import com.tanh.file_sharing.model.RemotePeerSnapshot;
import com.tanh.file_sharing.protocol.FramedJsonChannel;
import com.tanh.file_sharing.protocol.PeerProtocol;
import com.tanh.file_sharing.protocol.ProtocolException;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Coordinates the local peer lifecycle and incoming/outgoing TCP connections. */
public final class PeerService implements AutoCloseable {
    public static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    public static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;
    private static final int ACCEPT_BACKLOG = 50;
    private static final Logger LOGGER = Logger.getLogger(PeerService.class.getName());

    private final ObjectMapper objectMapper;
    private final EventPublisher events;
    private final UUID peerId;
    private final Object lifecycleLock = new Object();
    private final Object connectionLock = new Object();
    private final Map<UUID, PeerConnection> activeConnections = new ConcurrentHashMap<>();
    private final Map<UUID, RemotePeerSnapshot> knownPeers = new ConcurrentHashMap<>();
    private final java.util.Set<Socket> pendingSockets = ConcurrentHashMap.newKeySet();

    private volatile PeerStatus status = PeerStatus.STOPPED;
    private volatile String bindAddress = "127.0.0.1";
    private volatile int listeningPort;
    private volatile ServerSocket serverSocket;
    private volatile ExecutorService acceptExecutor;
    private volatile ExecutorService connectionExecutor;

    public PeerService(ObjectMapper objectMapper, EventPublisher events) {
        this(objectMapper, events, UUID.randomUUID());
    }

    public PeerService(ObjectMapper objectMapper, EventPublisher events, UUID peerId) {
        this.objectMapper = objectMapper;
        this.events = events == null ? EventPublisher.noop() : events;
        this.peerId = peerId;
    }

    public PeerSnapshot startPeer(String requestedBindAddress, int port) {
        synchronized (lifecycleLock) {
            if (status == PeerStatus.RUNNING) {
                return snapshot();
            }
            if (status == PeerStatus.STARTING || status == PeerStatus.STOPPING) {
                throw new PeerException("PEER_BUSY", "Peer is currently " + status + ".");
            }
            if (port < 1 || port > 65535) {
                throw new PeerException("INVALID_PORT", "Port must be between 1 and 65535.");
            }

            String addressText = requestedBindAddress == null || requestedBindAddress.isBlank()
                    ? "127.0.0.1" : requestedBindAddress.trim();
            transition(PeerStatus.STARTING);
            ServerSocket candidate = null;
            try {
                InetAddress address = InetAddress.getByName(addressText);
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(address, port), ACCEPT_BACKLOG);

                serverSocket = candidate;
                bindAddress = addressText;
                listeningPort = candidate.getLocalPort();
                connectionExecutor = Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "p2p-connection-" + listeningPort);
                    thread.setDaemon(true);
                    return thread;
                });
                acceptExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "p2p-accept-" + listeningPort);
                    thread.setDaemon(true);
                    return thread;
                });

                transition(PeerStatus.RUNNING);
                ServerSocket boundListener = candidate;
                acceptExecutor.submit(() -> acceptLoop(boundListener));
                logActivity("Peer started on " + bindAddress + ":" + listeningPort, "INFO");
                return snapshot();
            } catch (BindException exception) {
                closeQuietly(candidate);
                resetAfterStartFailure();
                throw new PeerException("PORT_IN_USE", "Port " + port + " is already in use.", exception);
            } catch (SecurityException exception) {
                closeQuietly(candidate);
                resetAfterStartFailure();
                throw new PeerException("PERMISSION_DENIED", "Permission denied while opening the listening port.", exception);
            } catch (IOException exception) {
                closeQuietly(candidate);
                resetAfterStartFailure();
                throw new PeerException("INVALID_ADDRESS",
                        "Could not bind to address '" + addressText + "': " + exception.getMessage(), exception);
            } catch (RuntimeException exception) {
                closeQuietly(candidate);
                resetAfterStartFailure();
                throw exception;
            }
        }
    }

    public PeerSnapshot stopPeer() {
        synchronized (lifecycleLock) {
            if (status == PeerStatus.STOPPED) {
                return snapshot();
            }
            if (status == PeerStatus.STARTING) {
                throw new PeerException("PEER_BUSY", "Peer is currently STARTING.");
            }
            transition(PeerStatus.STOPPING);

            boolean cleanupFailed = false;
            ServerSocket listener = serverSocket;
            serverSocket = null;
            if (listener != null) {
                try {
                    listener.close();
                } catch (IOException exception) {
                    cleanupFailed = true;
                    LOGGER.log(Level.WARNING, "Failed to close listening socket.", exception);
                }
            }
            for (Socket socket : List.copyOf(pendingSockets)) {
                closeQuietly(socket);
            }
            pendingSockets.clear();

            for (PeerConnection connection : List.copyOf(activeConnections.values())) {
                try {
                    connection.closeFromLocal();
                } catch (RuntimeException exception) {
                    cleanupFailed = true;
                    LOGGER.log(Level.WARNING, "Failed to close peer connection.", exception);
                }
            }
            cleanupFailed |= !shutdownExecutor(acceptExecutor);
            cleanupFailed |= !shutdownExecutor(connectionExecutor);
            acceptExecutor = null;
            connectionExecutor = null;

            if (!activeConnections.isEmpty() || !pendingSockets.isEmpty()) {
                cleanupFailed = true;
            }
            if (cleanupFailed) {
                logActivity("Peer cleanup did not complete; peer remains STOPPING", "ERROR");
                throw new PeerException("CLEANUP_FAILED",
                        "Peer cleanup did not complete. Retry shutdown after inspecting active resources.");
            }

            listeningPort = 0;
            bindAddress = "127.0.0.1";
            transition(PeerStatus.STOPPED);
            logActivity("Peer stopped", "INFO");
            return snapshot();
        }
    }

    public RemotePeerSnapshot connectPeer(String host, int port) {
        ensureRunning();
        if (host == null || host.isBlank() || host.length() > 253) {
            throw new PeerException("INVALID_ADDRESS", "A valid peer host is required.");
        }
        if (port < 1 || port > 65535) {
            throw new PeerException("INVALID_ADDRESS", "Peer port must be between 1 and 65535.");
        }

        String normalizedHost = host.trim();
        for (RemotePeerSnapshot knownPeer : knownPeers.values()) {
            if (knownPeer.status() == ConnectionStatus.CONNECTED
                    && knownPeer.host().equalsIgnoreCase(normalizedHost)
                    && knownPeer.port() == port) {
                return knownPeer;
            }
        }

        Socket socket = new Socket();
        pendingSockets.add(socket);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MILLIS);
        try {
            socket.connect(new InetSocketAddress(normalizedHost, port), CONNECT_TIMEOUT_MILLIS);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException("Connection deadline exceeded before handshake.");
            }
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));

            FramedJsonChannel channel = new FramedJsonChannel(socket, objectMapper);
            channel.writeJson(PeerProtocol.handshake(peerId, listeningPort));
            JsonNode response = channel.readJson();
            if (response == null) {
                throw new PeerException("HANDSHAKE_FAILED", "Remote peer closed the connection during handshake.");
            }
            PeerProtocol.HandshakeInfo remote = PeerProtocol.parseHandshakeAccepted(response);
            if (remote.peerId().equals(peerId)) {
                throw new PeerException("HANDSHAKE_FAILED", "A peer cannot connect to itself.");
            }
            if (status != PeerStatus.RUNNING) {
                throw new PeerException("PEER_NOT_RUNNING", "Local peer stopped while the connection was being established.");
            }

            String remoteHost = socket.getInetAddress().getHostAddress();
            socket.setSoTimeout(0);
            PeerConnection connection = createConnection(
                    socket, channel, remote.peerId(), remoteHost, remote.listeningPort(), ConnectionDirection.OUTGOING);
            if (!registerConnection(connection)) {
                PeerConnection existing = activeConnections.get(remote.peerId());
                connection.closeSilently();
                if (existing != null && !existing.isClosed()) {
                    return existing.snapshot(true);
                }
                throw new PeerException("HANDSHAKE_FAILED", "A connection to this peer already exists.");
            }
            pendingSockets.remove(socket);
            connection.startReading(connectionExecutor);
            return connection.snapshot(true);
        } catch (SocketTimeoutException exception) {
            closeQuietly(socket);
            throw new PeerException("CONNECT_TIMEOUT", "Connection or handshake timed out after 5 seconds.", exception);
        } catch (UnknownHostException exception) {
            closeQuietly(socket);
            throw new PeerException("INVALID_ADDRESS", "Could not resolve peer host '" + normalizedHost + "'.", exception);
        } catch (ConnectException | NoRouteToHostException exception) {
            closeQuietly(socket);
            throw new PeerException("CONNECTION_REFUSED",
                    "Could not connect to " + normalizedHost + ":" + port + ".", exception);
        } catch (ProtocolException exception) {
            RemotePeerSnapshot alreadyConnected = findConnectedPeer(socket.getInetAddress(), port);
            closeQuietly(socket);
            if (alreadyConnected != null) {
                return alreadyConnected;
            }
            throw new PeerException(exception.getCode(), exception.getMessage(), exception);
        } catch (PeerException exception) {
            closeQuietly(socket);
            throw exception;
        } catch (IOException exception) {
            closeQuietly(socket);
            if (status != PeerStatus.RUNNING) {
                throw new PeerException("PEER_NOT_RUNNING", "Local peer stopped while connecting.", exception);
            }
            throw new PeerException("HANDSHAKE_FAILED", "Could not complete peer handshake: " + exception.getMessage(), exception);
        } finally {
            pendingSockets.remove(socket);
        }
    }

    public RemotePeerSnapshot disconnectPeer(UUID remotePeerId) {
        if (remotePeerId == null) {
            throw new PeerException("UNKNOWN_PEER", "A valid peerId is required.");
        }
        RemotePeerSnapshot known = knownPeers.get(remotePeerId);
        if (known == null) {
            throw new PeerException("UNKNOWN_PEER", "No peer is known with id " + remotePeerId + ".");
        }
        PeerConnection connection = activeConnections.get(remotePeerId);
        if (connection != null) {
            connection.closeFromLocal();
        }
        return knownPeers.getOrDefault(remotePeerId, new RemotePeerSnapshot(
                known.peerId(), known.host(), known.port(), known.direction(),
                ConnectionStatus.DISCONNECTED, known.connectedAt()));
    }

    public PeerSnapshot getPeerStatus() {
        return snapshot();
    }

    public List<RemotePeerSnapshot> listPeers() {
        List<RemotePeerSnapshot> peers = new ArrayList<>(knownPeers.values());
        peers.sort(Comparator.comparing(peer -> peer.peerId().toString()));
        return List.copyOf(peers);
    }

    public PeerStatus getStatus() {
        return status;
    }

    @Override
    public void close() {
        if (status == PeerStatus.RUNNING || status == PeerStatus.STOPPING) {
            try {
                stopPeer();
            } catch (PeerException exception) {
                LOGGER.log(Level.WARNING, "Peer did not stop cleanly during application shutdown.", exception);
            }
        }
    }

    private void acceptLoop(ServerSocket listener) {
        while (status == PeerStatus.RUNNING && !listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                if (status != PeerStatus.RUNNING) {
                    closeQuietly(socket);
                    break;
                }
                pendingSockets.add(socket);
                socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
                ExecutorService executor = connectionExecutor;
                if (executor == null || executor.isShutdown()) {
                    pendingSockets.remove(socket);
                    closeQuietly(socket);
                    break;
                }
                executor.submit(() -> handleIncoming(socket));
            } catch (SocketException exception) {
                if (status == PeerStatus.RUNNING) {
                    LOGGER.log(Level.WARNING, "Peer listener stopped unexpectedly.", exception);
                }
                break;
            } catch (IOException exception) {
                if (status == PeerStatus.RUNNING) {
                    LOGGER.log(Level.WARNING, "Failed to accept incoming peer connection.", exception);
                }
            }
        }
    }

    private void handleIncoming(Socket socket) {
        PeerConnection registered = null;
        try {
            FramedJsonChannel channel = new FramedJsonChannel(socket, objectMapper);
            JsonNode request = channel.readJson();
            if (request == null) {
                throw new ProtocolException("HANDSHAKE_FAILED", "Peer disconnected before sending handshake.");
            }
            PeerProtocol.HandshakeInfo remote = PeerProtocol.parseHandshake(request);
            if (remote.peerId().equals(peerId)) {
                channel.writeJson(PeerProtocol.handshakeRejected("SELF_CONNECTION"));
                closeQuietly(socket);
                return;
            }
            if (status != PeerStatus.RUNNING) {
                channel.writeJson(PeerProtocol.handshakeRejected("PEER_STOPPING"));
                return;
            }

            String remoteHost = socket.getInetAddress().getHostAddress();
            socket.setSoTimeout(0);
            PeerConnection candidate = createConnection(socket, channel, remote.peerId(), remoteHost,
                    remote.listeningPort(), ConnectionDirection.INCOMING);
            if (!registerConnection(candidate)) {
                channel.writeJson(PeerProtocol.handshakeRejected("DUPLICATE_OR_NON_PREFERRED_CONNECTION"));
                candidate.closeSilently();
                return;
            }
            registered = candidate;
            channel.writeJson(PeerProtocol.handshakeAccepted(peerId, listeningPort));
            pendingSockets.remove(socket);
            candidate.startReading(connectionExecutor);
        } catch (SocketTimeoutException exception) {
            LOGGER.fine("Rejected incoming peer because the handshake timed out.");
        } catch (ProtocolException exception) {
            LOGGER.fine("Rejected invalid incoming handshake: " + exception.getMessage());
            try {
                new FramedJsonChannel(socket, objectMapper)
                        .writeJson(PeerProtocol.handshakeRejected(exception.getMessage()));
            } catch (IOException ignored) {
                // The malformed peer may already have closed its socket.
            }
        } catch (IOException exception) {
            if (registered != null) {
                registered.closeSilently();
            }
            if (status == PeerStatus.RUNNING) {
                LOGGER.log(Level.FINE, "Incoming peer connection failed.", exception);
            }
        } finally {
            pendingSockets.remove(socket);
            if (!isSocketRegistered(socket)) {
                closeQuietly(socket);
            }
        }
    }

    private PeerConnection createConnection(Socket socket, FramedJsonChannel channel, UUID remotePeerId,
            String remoteHost, int remotePort, ConnectionDirection direction) {
        return new PeerConnection(socket, channel, remotePeerId, remoteHost, remotePort, direction,
                () -> connectionClosed(remotePeerId, socket));
    }

    private boolean registerConnection(PeerConnection candidate) {
        UUID remoteId = candidate.getRemotePeerId();
        ConnectionDirection preferred = peerId.toString().compareTo(remoteId.toString()) < 0
                ? ConnectionDirection.OUTGOING : ConnectionDirection.INCOMING;

        synchronized (connectionLock) {
            if (status != PeerStatus.RUNNING) {
                return false;
            }
            PeerConnection existing = activeConnections.get(remoteId);
            if (existing != null && !existing.isClosed()) {
                if (candidate.getDirection() != preferred || existing.getDirection() == preferred) {
                    return false;
                }
            }
            activeConnections.put(remoteId, candidate);
            RemotePeerSnapshot snapshot = candidate.snapshot(true);
            knownPeers.put(remoteId, snapshot);
            if (existing != null && !existing.isClosed()) {
                existing.closeSilently();
            }
            events.publish("connectionStatusChanged", snapshot);
            logActivity("Connected to peer " + remoteId + " at " + snapshot.host() + ":" + snapshot.port(), "INFO");
            return true;
        }
    }

    private void connectionClosed(UUID remoteId, Socket socket) {
        synchronized (connectionLock) {
            PeerConnection current = activeConnections.get(remoteId);
            if (current == null || !current.ownsSocket(socket)) {
                return;
            }
            activeConnections.remove(remoteId, current);
            RemotePeerSnapshot previous = knownPeers.get(remoteId);
            if (previous != null) {
                RemotePeerSnapshot disconnected = new RemotePeerSnapshot(previous.peerId(), previous.host(),
                        previous.port(), previous.direction(), ConnectionStatus.DISCONNECTED, previous.connectedAt());
                knownPeers.put(remoteId, disconnected);
                events.publish("connectionStatusChanged", disconnected);
            }
            logActivity("Disconnected from peer " + remoteId, "INFO");
        }
    }

    private boolean isSocketRegistered(Socket socket) {
        return activeConnections.values().stream().anyMatch(connection -> connection.ownsSocket(socket));
    }

    private void transition(PeerStatus next) {
        status = next;
        events.publish("peerStatusChanged", Map.of(
                "peerId", peerId.toString(),
                "status", next.name(),
                "bindAddress", bindAddress,
                "port", listeningPort));
    }

    private void logActivity(String message, String level) {
        events.publish("activityLogged", Map.of(
                "timestamp", java.time.Instant.now().toString(),
                "level", level,
                "message", message));
    }

    private PeerSnapshot snapshot() {
        return new PeerSnapshot(peerId, status, bindAddress, listeningPort);
    }

    private RemotePeerSnapshot findConnectedPeer(InetAddress remoteAddress, int remotePort) {
        if (remoteAddress == null) {
            return null;
        }
        String address = remoteAddress.getHostAddress();
        return knownPeers.values().stream()
                .filter(peer -> peer.status() == ConnectionStatus.CONNECTED)
                .filter(peer -> peer.port() == remotePort && peer.host().equals(address))
                .findFirst().orElse(null);
    }

    private void ensureRunning() {
        if (status != PeerStatus.RUNNING) {
            throw new PeerException("PEER_NOT_RUNNING", "Start the local peer before connecting to another peer.");
        }
    }

    private void resetAfterStartFailure() {
        serverSocket = null;
        listeningPort = 0;
        bindAddress = "127.0.0.1";
        transition(PeerStatus.STOPPED);
    }

    private static boolean shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return true;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                return executor.awaitTermination(2, TimeUnit.SECONDS);
            }
            return true;
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort after startup failure.
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket close is idempotent.
            }
        }
    }
}
