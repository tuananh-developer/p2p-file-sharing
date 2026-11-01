package com.tanh.file_sharing.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.tanh.file_sharing.model.ConnectionDirection;
import com.tanh.file_sharing.model.RemotePeerSnapshot;
import com.tanh.file_sharing.protocol.FramedJsonChannel;
import com.tanh.file_sharing.protocol.PeerProtocol;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PeerConnection(
            Socket socket,
            FramedJsonChannel channel,
            UUID remotePeerId,
            String remoteHost,
            int remotePort,
            ConnectionDirection direction,
            Runnable onClosed) {
        this.socket = socket;
        this.channel = channel;
        this.remotePeerId = remotePeerId;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.direction = direction;
        this.connectedAt = java.time.Instant.now().toString();
        this.onClosed = onClosed;
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
        executor.submit(this::readLoop);
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

    private void finishClose(boolean notifyRemote) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
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
}
