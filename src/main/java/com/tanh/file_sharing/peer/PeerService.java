package com.tanh.file_sharing.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.model.ConnectionDirection;
import com.tanh.file_sharing.model.ConnectionStatus;
import com.tanh.file_sharing.model.PeerSnapshot;
import com.tanh.file_sharing.model.PeerStatus;
import com.tanh.file_sharing.model.RemotePeerSnapshot;
import com.tanh.file_sharing.model.SharedDirectorySnapshot;
import com.tanh.file_sharing.model.SharedFileSnapshot;
import com.tanh.file_sharing.model.RemoteFileSearchResult;
import com.tanh.file_sharing.model.FileSearchError;
import com.tanh.file_sharing.model.FileSearchResponse;
import com.tanh.file_sharing.model.DownloadAccepted;
import com.tanh.file_sharing.model.TransferSnapshot;
import com.tanh.file_sharing.model.ResumableDownloadSnapshot;
import com.tanh.file_sharing.protocol.FramedJsonChannel;
import com.tanh.file_sharing.protocol.PeerProtocol;
import com.tanh.file_sharing.protocol.ProtocolException;
import com.tanh.file_sharing.sharing.SharedDirectoryService;
import com.tanh.file_sharing.transfer.TransferService;

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
import java.util.Locale;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
    private final SharedDirectoryService sharedDirectoryService = new SharedDirectoryService();
    private final TransferService transferService;

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
        this.transferService = new TransferService(this.events,
                activeConnections::get, () -> status == PeerStatus.RUNNING);
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
        return stopPeer(true);
    }

    private PeerSnapshot stopPeer(boolean cancelTransfers) {
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

            // D-03 applies to an explicit peer stop. Application shutdown pauses
            // transfers first and closes connections without discarding saved ranges.
            if (cancelTransfers) {
                cleanupFailed |= !transferService.cancelAllAndAwait(2_000);
            }

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

    public SharedDirectorySnapshot setSharedDirectory(String path) {
        SharedDirectorySnapshot snapshot = sharedDirectoryService.setDirectory(path);
        events.publish("sharedDirectoryChanged", snapshot);
        logActivity("Shared directory configured", "INFO");
        return snapshot;
    }

    public SharedDirectorySnapshot getSharedDirectory() {
        return sharedDirectoryService.snapshot();
    }

    public List<SharedFileSnapshot> listLocalSharedFiles() {
        return sharedDirectoryService.listFiles();
    }

    /** Requests fresh file metadata from one connected peer. */
    public List<RemoteFileSearchResult> listRemoteFiles(UUID remotePeerId) {
        if (remotePeerId == null) {
            throw new PeerException("INVALID_ARGUMENT", "A valid peerId is required.");
        }

        PeerConnection connection = activeConnections.get(remotePeerId);
        RemotePeerSnapshot remote = knownPeers.get(remotePeerId);
        if (connection == null || connection.isClosed() || remote == null
                || remote.status() != ConnectionStatus.CONNECTED) {
            throw new PeerException("PEER_NOT_CONNECTED", "The requested peer is not connected.");
        }

        JsonNode response = connection.sendRequest(PeerProtocol.REQUEST_FILE_LIST, Map.of(), CONNECT_TIMEOUT_MILLIS);
        JsonNode filesNode = response.get("files");
        if (filesNode == null || !filesNode.isArray()) {
            throw new PeerException("INVALID_PEER_RESPONSE", "Peer returned an invalid file list.");
        }

        List<RemoteFileSearchResult> files = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (JsonNode file : filesNode) {
            if (!file.isObject()) {
                throw new PeerException("INVALID_PEER_RESPONSE", "Peer returned malformed file metadata.");
            }
            JsonNode nameNode = file.get("fileName");
            JsonNode sizeNode = file.get("size");
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().length() > 255
                    || !SharedDirectoryService.isSafeFileName(nameNode.asText())
                    || sizeNode == null || !sizeNode.isIntegralNumber()
                    || !sizeNode.canConvertToLong() || sizeNode.asLong() < 0) {
                throw new PeerException("INVALID_PEER_RESPONSE",
                        "Peer returned malformed or unsafe file metadata.");
            }

            String fileName = nameNode.asText();
            if (!seenNames.add(fileName)) {
                throw new PeerException("INVALID_PEER_RESPONSE", "Peer returned duplicate file names.");
            }
            files.add(new RemoteFileSearchResult(
                    remotePeerId, remote.host(), remote.port(), fileName, sizeNode.asLong()));
        }
        files.sort(Comparator.comparing(RemoteFileSearchResult::fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RemoteFileSearchResult::fileName));
        return List.copyOf(files);
    }

    /**
     * Refreshes the selected peers (or all connected peers) concurrently, then
     * filters the successful results. A peer failure is isolated in errors.
     */
    public FileSearchResponse searchFiles(String keyword, List<UUID> selectedPeerIds) {
        if (status != PeerStatus.RUNNING) {
            throw new PeerException("PEER_NOT_RUNNING", "Start the local peer before searching remote files.");
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalizedKeyword.length() > 256) {
            throw new PeerException("INVALID_ARGUMENT", "Search keyword must not exceed 256 characters.");
        }

        List<UUID> targets = selectedPeerIds == null
                ? new ArrayList<>(activeConnections.keySet())
                : new ArrayList<>(new LinkedHashSet<>(selectedPeerIds));
        targets.sort(Comparator.comparing(id -> id == null ? "" : id.toString()));

        List<FileSearchError> errors = new ArrayList<>();
        List<CompletableFuture<PeerFetchResult>> requests = new ArrayList<>();
        for (UUID target : targets) {
            if (target == null) {
                errors.add(new FileSearchError(null, "INVALID_ARGUMENT", "A peerId is required."));
                continue;
            }
            PeerConnection connection = activeConnections.get(target);
            if (connection == null || connection.isClosed()) {
                errors.add(new FileSearchError(target, "PEER_NOT_CONNECTED", "The requested peer is not connected."));
                continue;
            }
            requests.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return new PeerFetchResult(target, listRemoteFiles(target), null);
                } catch (PeerException exception) {
                    return new PeerFetchResult(target, List.of(),
                            new FileSearchError(target, exception.getCode(), exception.getMessage()));
                } catch (RuntimeException exception) {
                    return new PeerFetchResult(target, List.of(),
                            new FileSearchError(target, "INTERNAL_ERROR", "Could not refresh this peer's file list."));
                }
            }));
        }

        List<RemoteFileSearchResult> results = new ArrayList<>();
        for (CompletableFuture<PeerFetchResult> request : requests) {
            PeerFetchResult outcome = request.join();
            if (outcome.error() != null) {
                errors.add(outcome.error());
                continue;
            }
            for (RemoteFileSearchResult file : outcome.files()) {
                if (normalizedKeyword.isEmpty()
                        || file.fileName().toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                    results.add(file);
                }
            }
        }

        results.sort(Comparator.comparing(RemoteFileSearchResult::fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RemoteFileSearchResult::host)
                .thenComparingInt(RemoteFileSearchResult::port)
                .thenComparing(file -> file.peerId().toString()));
        errors.sort(Comparator.comparing(error ->
                error.peerId() == null ? "" : error.peerId().toString()));
        return new FileSearchResponse(results, errors);
    }

    public DownloadAccepted downloadFile(UUID remotePeerId, String fileName, String destinationDirectory) {
        return transferService.download(remotePeerId, fileName, destinationDirectory);
    }

    public DownloadAccepted downloadFileFromPeers(
            List<UUID> remotePeerIds, String fileName, String destinationDirectory) {
        return transferService.downloadFromPeers(remotePeerIds, fileName, destinationDirectory);
    }

    public TransferSnapshot cancelDownload(UUID taskId) {
        return transferService.cancel(taskId);
    }

    public List<TransferSnapshot> listTransfers() {
        return transferService.listTransfers();
    }

    public List<ResumableDownloadSnapshot> listResumableDownloads(String destinationDirectory) {
        return transferService.listResumableDownloads(destinationDirectory);
    }

    public DownloadAccepted resumeDownload(UUID taskId, List<UUID> peerIds, String destinationDirectory) {
        return transferService.resume(taskId, peerIds, destinationDirectory);
    }

    public PeerStatus getStatus() {
        return status;
    }

    @Override
    public void close() {
        try {
            // Application shutdown pauses transfers after their completed ranges
            // have been persisted; an explicit peer.stop still cancels them.
            if (!transferService.pauseAllAndAwait(10_000)) {
                LOGGER.warning("Some downloads were still pausing; closing peer sockets to interrupt active ranges.");
            }
            if (status == PeerStatus.RUNNING || status == PeerStatus.STOPPING) {
                try {
                    stopPeer(false);
                } catch (PeerException exception) {
                    LOGGER.log(Level.WARNING, "Peer did not stop cleanly during application shutdown.", exception);
                }
            }
            if (!transferService.pauseAllAndAwait(5_000)) {
                LOGGER.warning("Some downloads did not finish pausing after peer sockets were closed.");
            }
        } finally {
            transferService.close();
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
                () -> connectionClosed(remotePeerId, socket), this::handleRemoteRequest,
                new PeerConnection.FileProvider() {
                    @Override
                    public com.tanh.file_sharing.sharing.SharedFileSource openFile(String fileName) {
                        if (status != PeerStatus.RUNNING) {
                            throw new PeerException("PEER_NOT_RUNNING", "The local peer is not accepting file requests.");
                        }
                        return sharedDirectoryService.openFile(fileName);
                    }

                    @Override
                    public com.tanh.file_sharing.sharing.SharedFileSource openFileRange(
                            String fileName, long expectedSize) {
                        if (status != PeerStatus.RUNNING) {
                            throw new PeerException("PEER_NOT_RUNNING", "The local peer is not accepting file requests.");
                        }
                        return sharedDirectoryService.openFileRange(fileName, expectedSize);
                    }
                });
    }

    private Map<String, Object> handleRemoteRequest(String operation, JsonNode payload) {
        if (status != PeerStatus.RUNNING) {
            throw new PeerException("PEER_NOT_RUNNING", "The local peer is not accepting file requests.");
        }
        if (PeerProtocol.REQUEST_FILE_LIST.equals(operation)) {
            return Map.of("files", sharedDirectoryService.listFiles());
        }
        if (PeerProtocol.REQUEST_FILE_INFO.equals(operation)) {
            JsonNode nameNode = payload.get("fileName");
            if (nameNode == null || !nameNode.isTextual()
                    || !SharedDirectoryService.isSafeFileName(nameNode.asText())
                    || nameNode.asText().length() > 255) {
                throw new PeerException("INVALID_FILE_NAME", "A safe file name is required.");
            }
            try (var source = sharedDirectoryService.openFile(nameNode.asText())) {
                return Map.of(
                        "fileName", source.fileName(),
                        "size", source.size(),
                        "sha256", source.sha256());
            } catch (IOException exception) {
                throw new PeerException("FILE_UNAVAILABLE", "The requested file is unavailable.", exception);
            }
        }
        throw new PeerException("UNKNOWN_OPERATION", "Unsupported peer operation: " + operation);
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

    private record PeerFetchResult(
            UUID peerId,
            List<RemoteFileSearchResult> files,
            FileSearchError error) {
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
                "id", UUID.randomUUID().toString(),
                "timestamp", java.time.Instant.now().toString(),
                "category", "PEER",
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
