package com.tanh.file_sharing.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.tanh.file_sharing.model.ConnectionDirection;
import com.tanh.file_sharing.model.RemotePeerSnapshot;
import com.tanh.file_sharing.protocol.FramedJsonChannel;
import com.tanh.file_sharing.protocol.PeerProtocol;
import com.tanh.file_sharing.sharing.SharedFileSource;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A fully handshaken TCP connection to one remote peer. */
public final class PeerConnection {
    private static final Logger LOGGER = Logger.getLogger(PeerConnection.class.getName());
    public static final int MAX_FILE_CHUNK_BYTES = 24 * 1024;
    private static final long TRANSFER_IDLE_TIMEOUT_MILLIS = 30_000;

    private final Socket socket;
    private final FramedJsonChannel channel;
    private final UUID remotePeerId;
    private final String remoteHost;
    private final int remotePort;
    private final ConnectionDirection direction;
    private final String connectedAt;
    private final Runnable onClosed;
    private final RequestHandler requestHandler;
    private final FileProvider fileProvider;
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<JsonNode>> incomingTransfers = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> outboundTransfers = new ConcurrentHashMap<>();
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
            RequestHandler requestHandler,
            FileProvider fileProvider) {
        this.socket = socket;
        this.channel = channel;
        this.remotePeerId = remotePeerId;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.direction = direction;
        this.connectedAt = java.time.Instant.now().toString();
        this.onClosed = onClosed;
        this.requestHandler = requestHandler;
        this.fileProvider = fileProvider;
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

    /**
     * Requests one file and consumes its ordered chunk stream. File chunks are
     * delivered on this caller's worker thread, never on the socket reader.
     */
    public FileMetadata downloadFile(
            String fileName,
            DownloadSink sink,
            long metadataTimeoutMillis,
            BooleanSupplier cancelled) throws InterruptedException {
        if (closed.get()) {
            throw new PeerException("PEER_NOT_CONNECTED", "The peer connection is no longer active.");
        }
        if (fileName == null || fileName.isBlank() || sink == null || metadataTimeoutMillis < 1) {
            throw new PeerException("INVALID_ARGUMENT", "A valid file name, sink and timeout are required.");
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> metadataFuture = new CompletableFuture<>();
        BlockingQueue<JsonNode> frames = new ArrayBlockingQueue<>(4);
        pendingRequests.put(requestId, metadataFuture);
        incomingTransfers.put(requestId, frames);

        try {
            channel.writeJson(PeerProtocol.request(
                    requestId, PeerProtocol.REQUEST_FILE, Map.of("fileName", fileName)));

            JsonNode response;
            try {
                response = metadataFuture.get(metadataTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                throw new PeerException("PEER_REQUEST_TIMEOUT", "The peer did not provide file metadata in time.", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof PeerException peerException) {
                    throw peerException;
                }
                throw new PeerException("PEER_NOT_CONNECTED",
                        "The peer connection closed before file metadata arrived.", cause);
            }

            if (!response.path("success").isBoolean()) {
                throw new PeerException("INVALID_PEER_RESPONSE", "The peer returned a malformed file response.");
            }
            if (!response.path("success").asBoolean()) {
                JsonNode error = response.path("error");
                if (!error.path("code").isTextual() || !error.path("message").isTextual()) {
                    throw new PeerException("INVALID_PEER_RESPONSE", "The peer returned a malformed file error.");
                }
                throw new PeerException(error.path("code").asText(), error.path("message").asText());
            }

            JsonNode data = response.path("data");
            JsonNode nameNode = data.get("fileName");
            JsonNode sizeNode = data.get("size");
            JsonNode hashNode = data.get("sha256");
            if (!data.isObject() || nameNode == null || !nameNode.isTextual()
                    || !fileName.equals(nameNode.asText())
                    || sizeNode == null || !sizeNode.isIntegralNumber()
                    || !sizeNode.canConvertToLong() || sizeNode.asLong() < 0
                    || hashNode == null || !hashNode.isTextual()
                    || !hashNode.asText().matches("(?i)[0-9a-f]{64}")) {
                throw new PeerException("INVALID_PEER_RESPONSE", "The peer returned invalid file metadata.");
            }

            FileMetadata metadata = new FileMetadata(
                    nameNode.asText(), sizeNode.asLong(), hashNode.asText().toLowerCase(java.util.Locale.ROOT));
            sink.onMetadata(metadata);

            long receivedBytes = 0;
            long expectedSequence = 0;
            long lastFrameAt = System.nanoTime();
            while (true) {
                if (closed.get()) {
                    throw new PeerException("PEER_NOT_CONNECTED", "The peer disconnected during the file transfer.");
                }
                if ((cancelled != null && cancelled.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
                    sendCancelTransfer(requestId);
                    throw new PeerException("TRANSFER_CANCELLED", "The download was cancelled.");
                }

                JsonNode frame = frames.poll(200, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastFrameAt)
                            > TRANSFER_IDLE_TIMEOUT_MILLIS) {
                        sendCancelTransfer(requestId);
                        throw new PeerException("TRANSFER_TIMEOUT", "No file data arrived for 30 seconds.");
                    }
                    continue;
                }
                lastFrameAt = System.nanoTime();

                if (!frame.path("protocolVersion").isIntegralNumber()
                        || frame.path("protocolVersion").asInt() != PeerProtocol.VERSION) {
                    throw new PeerException("INVALID_PEER_RESPONSE", "The peer sent an unsupported file frame.");
                }

                String type = frame.path("type").asText("");
                if (PeerProtocol.FILE_CHUNK.equals(type)) {
                    JsonNode sequence = frame.get("sequence");
                    JsonNode dataNode = frame.get("data");
                    if (sequence == null || !sequence.isIntegralNumber() || sequence.asLong() != expectedSequence
                            || dataNode == null || !dataNode.isTextual()) {
                        throw new PeerException("INVALID_PEER_RESPONSE", "The peer sent an invalid file chunk.");
                    }

                    byte[] bytes;
                    try {
                        bytes = Base64.getDecoder().decode(dataNode.asText());
                    } catch (IllegalArgumentException exception) {
                        throw new PeerException("INVALID_PEER_RESPONSE", "The peer sent an invalid base64 chunk.", exception);
                    }
                    if (bytes.length < 1 || bytes.length > MAX_FILE_CHUNK_BYTES
                            || receivedBytes + bytes.length > metadata.size()) {
                        throw new PeerException("INVALID_PEER_RESPONSE", "The peer sent an out-of-range file chunk.");
                    }
                    sink.onChunk(bytes);
                    receivedBytes += bytes.length;
                    expectedSequence++;
                    continue;
                }

                if (PeerProtocol.FILE_END.equals(type)) {
                    JsonNode successNode = frame.get("success");
                    JsonNode totalNode = frame.get("totalBytes");
                    if (successNode == null || !successNode.isBoolean()
                            || totalNode == null || !totalNode.isIntegralNumber()
                            || !totalNode.canConvertToLong()) {
                        throw new PeerException("INVALID_PEER_RESPONSE", "The peer sent an invalid file-end frame.");
                    }
                    if (!successNode.asBoolean()) {
                        JsonNode error = frame.path("error");
                        String code = error.path("code").asText("TRANSFER_FAILED");
                        String message = error.path("message").asText("The source could not finish the transfer.");
                        throw new PeerException(code, message);
                    }
                    if (totalNode.asLong() != receivedBytes || receivedBytes != metadata.size()) {
                        throw new PeerException("TRANSFER_SIZE_MISMATCH",
                                "The received file size does not match the source metadata.");
                    }
                    return metadata;
                }

                throw new PeerException("INVALID_PEER_RESPONSE", "The peer sent an unexpected file-transfer frame.");
            }
        } catch (InterruptedException exception) {
            sendCancelTransfer(requestId);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (PeerException exception) {
            sendCancelTransfer(requestId);
            throw exception;
        } catch (IOException exception) {
            sendCancelTransfer(requestId);
            throw new PeerException("PEER_IO_ERROR", "Could not receive file data from the peer.", exception);
        } finally {
            pendingRequests.remove(requestId, metadataFuture);
            incomingTransfers.remove(requestId, frames);
        }
    }

    private void sendCancelTransfer(String requestId) {
        try {
            if (!closed.get()) {
                channel.writeJson(PeerProtocol.cancelTransfer(requestId));
            }
        } catch (IOException exception) {
            LOGGER.log(Level.FINE, "Could not send transfer cancellation to peer " + remotePeerId, exception);
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
                if (PeerProtocol.FILE_CHUNK.equals(type) || PeerProtocol.FILE_END.equals(type)) {
                    dispatchTransferFrame(frame);
                    continue;
                }
                if (PeerProtocol.CANCEL_TRANSFER.equals(type)) {
                    String requestId = frame.path("requestId").asText("");
                    AtomicBoolean cancellation = outboundTransfers.get(requestId);
                    if (cancellation != null) {
                        cancellation.set(true);
                    }
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

    private void dispatchTransferFrame(JsonNode frame) {
        JsonNode requestIdNode = frame.get("requestId");
        if (requestIdNode == null || !requestIdNode.isTextual()) {
            LOGGER.warning("Ignoring file-transfer frame without a requestId from " + remotePeerId);
            return;
        }
        BlockingQueue<JsonNode> queue = incomingTransfers.get(requestIdNode.asText());
        if (queue == null) {
            return;
        }
        while (!closed.get() && incomingTransfers.get(requestIdNode.asText()) == queue) {
            try {
                if (queue.offer(frame, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
        if (PeerProtocol.REQUEST_FILE.equals(operation)) {
            streamRequestedFile(requestId, payload);
            return;
        }
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

    private void streamRequestedFile(String requestId, JsonNode payload) {
        JsonNode nameNode = payload.get("fileName");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()
                || nameNode.asText().length() > 255) {
            writeErrorResponse(requestId, "INVALID_REQUEST", "A safe file name is required.");
            return;
        }

        AtomicBoolean cancellation = new AtomicBoolean(false);
        if (outboundTransfers.putIfAbsent(requestId, cancellation) != null) {
            writeErrorResponse(requestId, "DUPLICATE_REQUEST", "This transfer request is already active.");
            return;
        }

        boolean metadataSent = false;
        long sentBytes = 0;
        long sequence = 0;
        try (SharedFileSource source = fileProvider.openFile(nameNode.asText())) {
            if (cancellation.get()) {
                throw new PeerException("TRANSFER_CANCELLED", "The requester cancelled the transfer.");
            }
            channel.writeJson(PeerProtocol.successResponse(requestId, Map.of(
                    "fileName", source.fileName(),
                    "size", source.size(),
                    "sha256", source.sha256())));
            metadataSent = true;

            while (sentBytes < source.size()) {
                if (closed.get() || cancellation.get()) {
                    throw new PeerException("TRANSFER_CANCELLED", "The requester cancelled the transfer.");
                }

                int chunkLimit = (int) Math.min(MAX_FILE_CHUNK_BYTES, source.size() - sentBytes);
                ByteBuffer buffer = ByteBuffer.allocate(chunkLimit);
                while (buffer.hasRemaining()) {
                    int read = source.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        Thread.yield();
                    }
                }
                int length = buffer.position();
                if (length == 0) {
                    throw new PeerException("FILE_CHANGED", "The source file changed during transfer.");
                }
                if (sentBytes + length > source.size()) {
                    throw new PeerException("FILE_CHANGED", "The source file changed during transfer.");
                }

                byte[] bytes = java.util.Arrays.copyOf(buffer.array(), length);
                channel.writeJson(PeerProtocol.fileChunk(
                        requestId, sequence++, Base64.getEncoder().encodeToString(bytes)));
                sentBytes += length;
            }

            if (sentBytes != source.size() || source.currentSize() != source.size()) {
                throw new PeerException("FILE_CHANGED", "The source file changed during transfer.");
            }
            channel.writeJson(PeerProtocol.fileEnd(requestId, true, sentBytes, null, null));
        } catch (PeerException exception) {
            if (metadataSent) {
                writeFileEndError(requestId, sentBytes, exception.getCode(), exception.getMessage());
            } else {
                writeErrorResponse(requestId, exception.getCode(), exception.getMessage());
            }
        } catch (IOException exception) {
            if (metadataSent) {
                writeFileEndError(requestId, sentBytes, "FILE_IO_ERROR", "The source file could not be read.");
            } else {
                writeErrorResponse(requestId, "FILE_UNAVAILABLE", "The requested file is unavailable.");
            }
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not stream file '" + nameNode.asText() + "' to peer " + remotePeerId, exception);
            if (metadataSent) {
                writeFileEndError(requestId, sentBytes, "INTERNAL_ERROR", "The source could not finish the transfer.");
            } else {
                writeErrorResponse(requestId, "INTERNAL_ERROR", "The source could not prepare the transfer.");
            }
        } finally {
            outboundTransfers.remove(requestId, cancellation);
        }
    }

    private void writeFileEndError(String requestId, long sentBytes, String code, String message) {
        try {
            if (!closed.get()) {
                channel.writeJson(PeerProtocol.fileEnd(requestId, false, sentBytes, code, message));
            }
        } catch (IOException exception) {
            closeSilently();
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
        incomingTransfers.clear();
        outboundTransfers.values().forEach(flag -> flag.set(true));
        outboundTransfers.clear();

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

    public record FileMetadata(String fileName, long size, String sha256) {
    }

    public interface DownloadSink {
        void onMetadata(FileMetadata metadata);

        void onChunk(byte[] bytes) throws IOException;
    }

    @FunctionalInterface
    public interface FileProvider {
        SharedFileSource openFile(String fileName);
    }

    @FunctionalInterface
    public interface RequestHandler {
        Map<String, Object> handle(String operation, JsonNode payload);
    }
}
