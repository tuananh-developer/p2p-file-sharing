package com.tanh.file_sharing.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.tanh.file_sharing.model.ConnectionDirection;
import com.tanh.file_sharing.model.RemotePeerSnapshot;
import com.tanh.file_sharing.protocol.FramedJsonChannel;
import com.tanh.file_sharing.protocol.PeerProtocol;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A fully handshaken TCP connection to one remote peer. */
public final class PeerConnection {
    private static final Logger LOGGER = Logger.getLogger(PeerConnection.class.getName());

    private final Socket socket;
    private final FramedJsonChannel channel;
    private final UUID remotePeerId;
    private final String remoteHost;
    private final int remotePort;
    private final ConnectionDirection direction;
    private final String connectedAt;
    private final Runnable onClosed;
    private final RequestHandler requestHandler;
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final Semaphore requestSlots = new Semaphore(8);
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile ExecutorService requestExecutor;

    public PeerConnection(
            Socket socket,
            FramedJsonChannel channel,
            UUID remotePeerId,
            String remoteHost,
            int remotePort,
            ConnectionDirection direction,
            Runnable onClosed,
            RequestHandler requestHandler) {
        this.socket = socket;
        this.channel = channel;
        this.remotePeerId = remotePeerId;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.direction = direction;
        this.connectedAt = java.time.Instant.now().toString();
        this.onClosed = onClosed;
        this.requestHandler = requestHandler;
    }

    public UUID getRemotePeerId() {
        return remotePeerId;
    }

    public ConnectionDirection getDirection() {
        return direction;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean ownsSocket(Socket candidate) {
        return this.socket == candidate;
    }

    public RemotePeerSnapshot snapshot(boolean connected) {
        return new RemotePeerSnapshot(
                remotePeerId,
                remoteHost,
                remotePort,
                direction,
                connected ? com.tanh.file_sharing.model.ConnectionStatus.CONNECTED
                        : com.tanh.file_sharing.model.ConnectionStatus.DISCONNECTED,
                connectedAt);
    }

    public void startReading(ExecutorService executor) {
        requestExecutor = executor;
        executor.submit(this::readLoop);
    }

    /**
     * Sends one correlated request and waits only for its response. The socket's
     * single reader continues dispatching responses and remote requests.
     */
    public JsonNode sendRequest(String operation, Object payload, long timeoutMillis) {
        if (closed.get()) {
            throw new PeerException("PEER_NOT_CONNECTED", "The peer connection is no longer active.");
        }
        if (operation == null || operation.isBlank() || timeoutMillis < 1) {
            throw new PeerException("INVALID_ARGUMENT", "A valid operation and timeout are required.");
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
        pendingRequests.put(requestId, responseFuture);

        try {
            channel.writeJson(PeerProtocol.request(requestId, operation, payload));
            JsonNode response = responseFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!response.path("protocolVersion").isIntegralNumber()
                    || response.path("protocolVersion").asInt() != PeerProtocol.VERSION) {
                throw new PeerException("INVALID_PEER_RESPONSE", "Peer returned an unsupported response version.");
            }
            if (!response.path("success").isBoolean()) {
                throw new PeerException("INVALID_PEER_RESPONSE", "Peer returned a malformed response.");
            }
            if (!response.path("success").asBoolean()) {
                JsonNode error = response.get("error");
                if (error == null || !error.isObject()
                        || !error.path("code").isTextual()
                        || !error.path("message").isTextual()
                        || error.path("code").asText().isBlank()
                        || error.path("code").asText().length() > 64
                        || error.path("message").asText().isBlank()
                        || error.path("message").asText().length() > 512) {
                    throw new PeerException("INVALID_PEER_RESPONSE", "Peer returned a malformed error response.");
                }
                throw new PeerException(error.path("code").asText(), error.path("message").asText());
            }

            JsonNode data = response.get("data");
            if (data == null || !data.isObject()) {
                throw new PeerException("INVALID_PEER_RESPONSE", "Peer response data must be a JSON object.");
            }
            return data;
        } catch (TimeoutException exception) {
            throw new PeerException("PEER_REQUEST_TIMEOUT",
                    "Peer did not answer operation '" + operation + "' within " + timeoutMillis + " ms.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PeerException("PEER_REQUEST_INTERRUPTED", "Peer request was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof PeerException peerException) {
                throw peerException;
            }
            throw new PeerException("PEER_NOT_CONNECTED", "The peer connection closed before a response arrived.", cause);
        } catch (IOException exception) {
            throw new PeerException("PEER_IO_ERROR", "Could not send a request to the peer.", exception);
        } finally {
            pendingRequests.remove(requestId, responseFuture);
        }
    }

    /** Initiates a graceful close and then releases the local socket. */
    public void closeFromLocal() {
        finishClose(true);
    }

    /** Closes without sending a close frame (used for rejected/replaced sockets). */
    public void closeSilently() {
        finishClose(false);
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                JsonNode frame = channel.readJson();
                if (frame == null) {
                    break;
                }

                String type = frame.path("type").asText("");
                if (PeerProtocol.CLOSE_CONNECTION.equals(type)) {
                    try {
                        channel.writeJson(PeerProtocol.closeAcknowledged());
                    } catch (IOException exception) {
                        LOGGER.log(Level.FINE, "Could not acknowledge remote close.", exception);
                    }
                    break;
                }
                if (PeerProtocol.CLOSE_ACKNOWLEDGED.equals(type)) {
                    continue;
                }
                if (PeerProtocol.RESPONSE.equals(type)) {
                    completePendingRequest(frame);
                    continue;
                }
                if (PeerProtocol.REQUEST.equals(type)) {
                    if (!dispatchRemoteRequest(frame)) {
                        break;
                    }
                    continue;
                }

                LOGGER.warning(() -> "Unsupported frame '" + type + "' from peer " + remotePeerId);
                break;
            }
        } catch (IOException exception) {
            if (!closed.get()) {
                LOGGER.log(Level.INFO, "Connection to peer " + remotePeerId + " ended with an I/O error.", exception);
            }
        } finally {
            finishClose(false);
        }
    }

    private void completePendingRequest(JsonNode frame) {
        JsonNode requestIdNode = frame.get("requestId");
        if (requestIdNode == null || !requestIdNode.isTextual()
                || requestIdNode.asText().isBlank() || requestIdNode.asText().length() > 128) {
            LOGGER.warning("Ignoring peer response without a valid requestId from " + remotePeerId);
            return;
        }

        CompletableFuture<JsonNode> pending = pendingRequests.get(requestIdNode.asText());
        if (pending == null) {
            return;
        }
        if (!frame.path("protocolVersion").isIntegralNumber()
                || frame.path("protocolVersion").asInt() != PeerProtocol.VERSION) {
            pending.completeExceptionally(new PeerException(
                    "INVALID_PEER_RESPONSE", "Peer returned an unsupported response version."));
            return;
        }
        pending.complete(frame);
    }

    private boolean dispatchRemoteRequest(JsonNode frame) {
        JsonNode requestIdNode = frame.get("requestId");
        if (requestIdNode == null || !requestIdNode.isTextual()
                || requestIdNode.asText().isBlank() || requestIdNode.asText().length() > 128) {
            LOGGER.warning("Closing peer connection after a request without a valid requestId.");
            return false;
        }
        String requestId = requestIdNode.asText();

        JsonNode versionNode = frame.get("protocolVersion");
        if (versionNode == null || !versionNode.isIntegralNumber()
                || versionNode.asInt() != PeerProtocol.VERSION) {
            writeErrorResponse(requestId, "UNSUPPORTED_PROTOCOL_VERSION", "Unsupported request protocol version.");
            return true;
        }

        JsonNode operationNode = frame.get("operation");
        JsonNode payload = frame.get("payload");
        if (operationNode == null || !operationNode.isTextual()
                || operationNode.asText().isBlank() || operationNode.asText().length() > 64
                || payload == null || !payload.isObject()) {
            writeErrorResponse(requestId, "INVALID_REQUEST", "Request operation and object payload are required.");
            return true;
        }
        String operation = operationNode.asText();

        ExecutorService executor = requestExecutor;
        if (executor == null || executor.isShutdown() || !requestSlots.tryAcquire()) {
            writeErrorResponse(requestId, "PEER_BUSY", "The local peer cannot accept another request.");
            return true;
        }
        try {
            executor.submit(() -> {
                try {
                    processRemoteRequest(requestId, operation, payload);
                } finally {
                    requestSlots.release();
                }
            });
        } catch (RejectedExecutionException exception) {
            requestSlots.release();
            writeErrorResponse(requestId, "PEER_BUSY", "The local peer cannot accept another request.");
        }
        return true;
    }

    private void processRemoteRequest(String requestId, String operation, JsonNode payload) {
        try {
            Map<String, Object> data = requestHandler.handle(operation, payload);
            channel.writeJson(PeerProtocol.successResponse(requestId, data));
        } catch (PeerException exception) {
            writeErrorResponse(requestId, exception.getCode(), exception.getMessage());
        } catch (IOException exception) {
            String message = exception.getMessage();
            if (message != null && message.startsWith("Protocol frame exceeds")) {
                // A large response cannot be split in protocol v1; return an explicit error.
                writeErrorResponse(requestId, "RESPONSE_TOO_LARGE", "The peer response exceeded the frame limit.");
            } else if (!closed.get()) {
                LOGGER.log(Level.FINE, "Could not write a response to peer " + remotePeerId, exception);
                closeSilently();
            }
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not process remote request '" + operation + "'.", exception);
            writeErrorResponse(requestId, "INTERNAL_ERROR", "The peer could not complete the request.");
        }
    }

    private void writeErrorResponse(String requestId, String code, String message) {
        try {
            channel.writeJson(PeerProtocol.errorResponse(requestId, code, message));
        } catch (IOException exception) {
            if (!closed.get()) {
                LOGGER.log(Level.FINE, "Could not return an error response to peer " + remotePeerId, exception);
            }
            closeSilently();
        }
    }

    private void finishClose(boolean notifyRemote) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        PeerException closedException = new PeerException(
                "PEER_NOT_CONNECTED", "The peer connection closed before the request completed.");
        pendingRequests.values().forEach(future -> future.completeExceptionally(closedException));
        pendingRequests.clear();

        if (notifyRemote && !socket.isClosed()) {
            try {
                channel.writeJson(PeerProtocol.closeConnection());
            } catch (IOException exception) {
                LOGGER.log(Level.FINE, "Could not send close frame to peer " + remotePeerId, exception);
            }
        }
        try {
            socket.close();
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not close socket for peer " + remotePeerId, exception);
        } finally {
            onClosed.run();
        }
    }

    @FunctionalInterface
    public interface RequestHandler {
        Map<String, Object> handle(String operation, JsonNode payload);
    }
}
