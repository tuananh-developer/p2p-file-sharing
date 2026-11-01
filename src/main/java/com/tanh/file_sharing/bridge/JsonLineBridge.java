package com.tanh.file_sharing.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.peer.PeerException;
import com.tanh.file_sharing.peer.PeerService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Electron-facing JSON Lines bridge. stdin is commands; stdout is responses and
 * asynchronous events. Application logs are deliberately left on stderr.
 */
public final class JsonLineBridge implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(JsonLineBridge.class.getName());
    private static final int MAX_COMMAND_LINE_CHARS = 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final JsonLineWriter output;
    private final PeerService peerService;
    private final CommandDispatcher dispatcher;
    private final Reader input;
    private final ExecutorService commandExecutor;
    private volatile boolean running = true;

    public JsonLineBridge(ObjectMapper objectMapper, JsonLineWriter output, PeerService peerService) {
        this(objectMapper, output, peerService, new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    public JsonLineBridge(ObjectMapper objectMapper, JsonLineWriter output, PeerService peerService, Reader input) {
        this.objectMapper = objectMapper;
        this.output = output;
        this.peerService = peerService;
        this.dispatcher = new CommandDispatcher(peerService);
        this.input = input;
        this.commandExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "p2p-bridge-command");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void run() {
        EventPublisher events = (eventName, data) -> output.write(Map.of(
                "type", "event",
                "event", eventName,
                "data", data == null ? Map.of() : data));

        events.publish("backendStatusChanged", Map.of("status", "READY"));
        try (BufferedReader reader = input instanceof BufferedReader bufferedReader
                ? bufferedReader : new BufferedReader(input)) {
            String line;
            while (running && (line = readBoundedLine(reader, MAX_COMMAND_LINE_CHARS)) != null) {
                if (line.length() > MAX_COMMAND_LINE_CHARS) {
                    writeError(null, "INVALID_ARGUMENT", "Command line exceeds the maximum allowed size.");
                    continue;
                }

                JsonNode request;
                try {
                    request = objectMapper.readTree(line);
                } catch (IOException exception) {
                    writeError(null, "INVALID_JSON", "Input line is not valid JSON.");
                    continue;
                }
                if (request == null || !request.isObject()) {
                    writeError(null, "INVALID_ARGUMENT", "Each command must be a JSON object.");
                    continue;
                }

                String requestId = extractRequestId(request);
                String command = request.path("command").asText("");
                JsonNode payload = request.get("payload");
                if (requestId == null || requestId.isBlank()) {
                    writeError(null, "INVALID_ARGUMENT", "Every command must include a non-empty id or requestId.");
                    continue;
                }
                if (command.isBlank()) {
                    writeError(requestId, "INVALID_ARGUMENT", "Field 'command' is required.");
                    continue;
                }

                if ("app.shutdown".equals(command) || "shutdownApplication".equals(command)) {
                    shutdownBridgeCommand(requestId, payload);
                    break;
                }
                commandExecutor.submit(() -> executeCommand(requestId, command, payload));
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not read commands from stdin.", exception);
        } finally {
            running = false;
            commandExecutor.shutdown();
            awaitCommands();
            peerService.close();
            events.publish("backendStatusChanged", Map.of("status", "STOPPED"));
        }
    }

    @Override
    public void close() {
        running = false;
        commandExecutor.shutdownNow();
        peerService.close();
    }

    private void executeCommand(String requestId, String command, JsonNode payload) {
        try {
            Map<String, Object> data = dispatcher.dispatch(command, payload);
            writeSuccess(requestId, data);
        } catch (PeerException exception) {
            writeError(requestId, exception.getCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unexpected failure while handling command '" + command + "'.", exception);
            writeError(requestId, "INTERNAL_ERROR", "The backend could not complete the command.");
        }
    }

    private void shutdownBridgeCommand(String requestId, JsonNode payload) {
        commandExecutor.shutdown();
        awaitCommands();
        executeCommand(requestId, "app.shutdown", payload);
        running = false;
    }

    private void awaitCommands() {
        try {
            if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                commandExecutor.shutdownNow();
                if (!commandExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.warning("Some bridge command workers did not terminate promptly.");
                }
            }
        } catch (InterruptedException exception) {
            commandExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void writeSuccess(String requestId, Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "response");
        response.put("id", requestId);
        response.put("success", true);
        response.put("data", data);
        output.write(response);
    }

    private void writeError(String requestId, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "Unknown error." : message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "response");
        response.put("id", requestId);
        response.put("success", false);
        response.put("error", error);
        output.write(response);
    }

    private static String readBoundedLine(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') {
                return line.toString();
            }
            if (next != '\r') {
                if (line.length() >= maxChars) {
                    while ((next = reader.read()) != -1 && next != '\n') {
                        // Discard the rest of the oversized command.
                    }
                    return " ".repeat(maxChars + 1);
                }
                line.append((char) next);
            }
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static String extractRequestId(JsonNode request) {
        JsonNode id = request.has("requestId") ? request.get("requestId") : request.get("id");
        return id == null || id.isNull() ? null : id.asText();
    }
}
