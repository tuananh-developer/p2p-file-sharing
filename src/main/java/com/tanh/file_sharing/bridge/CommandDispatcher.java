package com.tanh.file_sharing.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tanh.file_sharing.peer.PeerException;
import com.tanh.file_sharing.peer.PeerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Maps JSON Lines command names to Java P2P Core operations. */
public final class CommandDispatcher {
    private final PeerService peerService;

    public CommandDispatcher(PeerService peerService) {
        this.peerService = peerService;
    }

    public Map<String, Object> dispatch(String command, JsonNode payload) {
        JsonNode body = payload == null || payload.isNull() || payload.isMissingNode()
                ? JsonNodeFactory.instance.objectNode() : payload;
        if (!body.isObject()) {
            throw new PeerException("INVALID_ARGUMENT", "payload must be a JSON object.");
        }

        return switch (normalize(command)) {
            case "peer.start" -> Map.of("peer", peerService.startPeer(
                    optionalText(body, "bindAddress", "127.0.0.1"), requiredInt(body, "port")));
            case "peer.stop" -> Map.of("peer", peerService.stopPeer());
            case "peer.connect" -> Map.of("peer",
                    peerService.connectPeer(requiredText(body, "host"), requiredInt(body, "port")));
            case "peer.disconnect" -> Map.of("peer",
                    peerService.disconnectPeer(requiredUuid(body, "peerId")));
            case "peer.status" -> Map.of("peer", peerService.getPeerStatus());
            case "peer.list" -> Map.of("peers", peerService.listPeers());
            case "share.setDirectory" -> Map.of("sharedDirectory",
                    peerService.setSharedDirectory(requiredText(body, "path")));
            case "share.getDirectory" -> Map.of("sharedDirectory", peerService.getSharedDirectory());
            case "files.listLocal" -> Map.of("files", peerService.listLocalSharedFiles());
            case "files.listRemote" -> Map.of("files",
                    peerService.listRemoteFiles(requiredUuid(body, "peerId")));
            case "files.search" -> {
                var result = peerService.searchFiles(optionalQuery(body, "keyword"), optionalPeerIds(body, "peerIds"));
                yield Map.of("results", result.results(), "errors", result.errors());
            }
            case "transfer.download" -> Map.of("download", peerService.downloadFile(
                    requiredUuid(body, "peerId"),
                    requiredText(body, "fileName"),
                    requiredText(body, "destinationDir")));
            case "transfer.downloadMulti" -> Map.of("download", peerService.downloadFileFromPeers(
                    optionalPeerIds(body, "peerIds"),
                    requiredText(body, "fileName"),
                    requiredText(body, "destinationDir")));
            case "transfer.cancel" -> Map.of("transfer",
                    peerService.cancelDownload(requiredUuid(body, "taskId")));
            case "transfer.list" -> Map.of("transfers", peerService.listTransfers());
            case "app.shutdown" -> shutdownApplication();
            default -> throw new PeerException("UNKNOWN_COMMAND", "Unsupported command: " + command);
        };
    }

    private Map<String, Object> shutdownApplication() {
        peerService.close();
        return Map.of("status", "SHUTDOWN");
    }

    private static String normalize(String command) {
        if (command == null) {
            return "";
        }
        return switch (command) {
            case "startPeer" -> "peer.start";
            case "stopPeer" -> "peer.stop";
            case "connectPeer" -> "peer.connect";
            case "disconnectPeer" -> "peer.disconnect";
            case "getPeerStatus" -> "peer.status";
            case "listPeers" -> "peer.list";
            case "setSharedDirectory" -> "share.setDirectory";
            case "getSharedDirectory" -> "share.getDirectory";
            case "listLocalSharedFiles" -> "files.listLocal";
            case "listRemoteFiles" -> "files.listRemote";
            case "searchFiles" -> "files.search";
            case "downloadFile" -> "transfer.download";
            case "cancelDownload" -> "transfer.cancel";
            case "listTransfers" -> "transfer.list";
            case "shutdownApplication" -> "app.shutdown";
            default -> command;
        };
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new PeerException("INVALID_ARGUMENT",
                    "Field '" + field + "' is required and must be a non-empty string.");
        }
        return value.asText().trim();
    }

    private static String optionalText(JsonNode payload, String field, String defaultValue) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new PeerException("INVALID_ARGUMENT", "Field '" + field + "' must be a non-empty string.");
        }
        return value.asText().trim();
    }

    private static int requiredInt(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new PeerException("INVALID_ARGUMENT",
                    "Field '" + field + "' is required and must be an integer.");
        }
        return value.asInt();
    }

    private static String optionalQuery(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw new PeerException("INVALID_ARGUMENT", "Field '" + field + "' must be a string.");
        }
        return value.asText().trim();
    }

    private static List<UUID> optionalPeerIds(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray() || value.size() > 100) {
            throw new PeerException("INVALID_ARGUMENT",
                    "Field '" + field + "' must be an array containing at most 100 peer IDs.");
        }

        List<UUID> peerIds = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new PeerException("INVALID_ARGUMENT", "Every value in '" + field + "' must be a UUID.");
            }
            try {
                peerIds.add(UUID.fromString(item.asText()));
            } catch (IllegalArgumentException exception) {
                throw new PeerException("INVALID_ARGUMENT", "Every value in '" + field + "' must be a UUID.", exception);
            }
        }
        return List.copyOf(peerIds);
    }

    private static UUID requiredUuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(requiredText(payload, field));
        } catch (IllegalArgumentException exception) {
            throw new PeerException("INVALID_ARGUMENT", "Field '" + field + "' must be a UUID.", exception);
        }
    }
}
