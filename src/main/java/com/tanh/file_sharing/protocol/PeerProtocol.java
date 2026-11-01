package com.tanh.file_sharing.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Wire contract for the first version of the peer-to-peer TCP protocol. */
public final class PeerProtocol {
    public static final int VERSION = 1;
    public static final String HANDSHAKE = "handshake";
    public static final String HANDSHAKE_ACCEPTED = "handshakeAccepted";
    public static final String HANDSHAKE_REJECTED = "handshakeRejected";
    public static final String CLOSE_CONNECTION = "closeConnection";
    public static final String CLOSE_ACKNOWLEDGED = "closeAcknowledged";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String REQUEST_FILE_LIST = "requestFileList";

    private PeerProtocol() {
    }

    public static Map<String, Object> handshake(UUID peerId, int listeningPort) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", HANDSHAKE);
        frame.put("protocolVersion", VERSION);
        frame.put("peerId", peerId.toString());
        frame.put("listeningPort", listeningPort);
        return frame;
    }

    public static Map<String, Object> handshakeAccepted(UUID peerId, int listeningPort) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", HANDSHAKE_ACCEPTED);
        frame.put("protocolVersion", VERSION);
        frame.put("peerId", peerId.toString());
        frame.put("listeningPort", listeningPort);
        return frame;
    }

    public static Map<String, Object> handshakeRejected(String reason) {
        return Map.of("type", HANDSHAKE_REJECTED, "reason", reason);
    }

    public static Map<String, Object> closeConnection() {
        return Map.of("type", CLOSE_CONNECTION, "protocolVersion", VERSION);
    }

    public static Map<String, Object> closeAcknowledged() {
        return Map.of("type", CLOSE_ACKNOWLEDGED, "protocolVersion", VERSION);
    }

    public static Map<String, Object> request(String requestId, String operation, Object payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", REQUEST);
        frame.put("protocolVersion", VERSION);
        frame.put("requestId", requestId);
        frame.put("operation", operation);
        frame.put("payload", payload == null ? Map.of() : payload);
        return frame;
    }

    public static Map<String, Object> successResponse(String requestId, Object data) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", RESPONSE);
        frame.put("protocolVersion", VERSION);
        frame.put("requestId", requestId);
        frame.put("success", true);
        frame.put("data", data == null ? Map.of() : data);
        return frame;
    }

    public static Map<String, Object> errorResponse(String requestId, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "Peer request failed." : message);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", RESPONSE);
        frame.put("protocolVersion", VERSION);
        frame.put("requestId", requestId);
        frame.put("success", false);
        frame.put("error", error);
        return frame;
    }

    public static HandshakeInfo parseHandshake(JsonNode frame) throws ProtocolException {
        if (!HANDSHAKE.equals(text(frame, "type"))) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Expected a handshake frame.");
        }
        int version = integer(frame, "protocolVersion");
        if (version != VERSION) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Unsupported protocol version: " + version);
        }

        UUID peerId;
        try {
            peerId = UUID.fromString(text(frame, "peerId"));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Handshake contains an invalid peerId.", exception);
        }

        int listeningPort = integer(frame, "listeningPort");
        if (listeningPort < 1 || listeningPort > 65535) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Handshake contains an invalid listeningPort.");
        }
        return new HandshakeInfo(peerId, listeningPort);
    }

    public static HandshakeInfo parseHandshakeAccepted(JsonNode frame) throws ProtocolException {
        if (!HANDSHAKE_ACCEPTED.equals(text(frame, "type"))) {
            String reason = frame.path("reason").asText("unspecified");
            throw new ProtocolException("HANDSHAKE_FAILED",
                    HANDSHAKE_REJECTED.equals(frame.path("type").asText(""))
                            ? "Remote peer rejected the handshake: " + reason
                            : "Remote peer returned an unexpected handshake response.");
        }
        int version = integer(frame, "protocolVersion");
        if (version != VERSION) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Remote peer uses unsupported protocol version: " + version);
        }

        UUID peerId;
        try {
            peerId = UUID.fromString(text(frame, "peerId"));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Remote peer returned an invalid peerId.", exception);
        }

        int listeningPort = integer(frame, "listeningPort");
        if (listeningPort < 1 || listeningPort > 65535) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Remote peer returned an invalid listeningPort.");
        }
        return new HandshakeInfo(peerId, listeningPort);
    }

    private static String text(JsonNode node, String field) throws ProtocolException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Missing or invalid '" + field + "'.");
        }
        return value.asText();
    }

    private static int integer(JsonNode node, String field) throws ProtocolException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new ProtocolException("HANDSHAKE_FAILED", "Missing or invalid '" + field + "'.");
        }
        return value.asInt();
    }

    public record HandshakeInfo(UUID peerId, int listeningPort) {
    }
}
