package com.tanh.file_sharing.transfer;

import com.tanh.file_sharing.peer.PeerException;
import com.tanh.file_sharing.sharing.SharedDirectoryService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Crash-safe metadata for resumable ranged downloads. The manifest is replaced
 * atomically only after the corresponding range has been forced to disk.
 */
final class ResumeManifestStore {
    static final int FORMAT_VERSION = 1;

    record Manifest(
            UUID taskId,
            String fileName,
            String targetFileName,
            long totalBytes,
            String sha256,
            int chunkBytes,
            List<String> chunkHashes) {
        Manifest {
            chunkHashes = new ArrayList<>(chunkHashes);
        }

        long totalChunks() {
            return totalBytes == 0 ? 0 : (totalBytes + chunkBytes - 1) / chunkBytes;
        }

        long completedChunks() {
            return chunkHashes.stream().filter(hash -> hash != null && !hash.isBlank()).count();
        }

        long completedBytes() {
            long bytes = 0;
            for (int index = 0; index < chunkHashes.size(); index++) {
                if (chunkHashes.get(index) != null && !chunkHashes.get(index).isBlank()) {
                    bytes += Math.min(chunkBytes, totalBytes - (long) index * chunkBytes);
                }
            }
            return bytes;
        }

        boolean isCompleted(int index) {
            return index >= 0 && index < chunkHashes.size()
                    && chunkHashes.get(index) != null && !chunkHashes.get(index).isBlank();
        }
    }

    private ResumeManifestStore() {
    }

    static Path manifestPath(Path partPath) {
        return partPath.resolveSibling(partPath.getFileName() + ".resume");
    }

    static Manifest create(UUID taskId, String fileName, String targetFileName,
            long totalBytes, String sha256, int chunkBytes) {
        int chunks = totalBytes == 0 ? 0 : Math.toIntExact((totalBytes + chunkBytes - 1) / chunkBytes);
        return new Manifest(taskId, fileName, targetFileName, totalBytes, sha256,
                chunkBytes, new ArrayList<>(java.util.Collections.nCopies(chunks, "")));
    }

    static Manifest loadAndVerify(Path manifestPath) throws IOException {
        if (Files.isSymbolicLink(manifestPath)
                || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Resume manifest is not a regular file.");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(manifestPath)) {
            properties.load(input);
        }

        if (integer(properties, "version") != FORMAT_VERSION) {
            throw new IOException("Unsupported resume manifest version.");
        }
        UUID taskId;
        try {
            taskId = UUID.fromString(required(properties, "taskId"));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid resume task ID.", exception);
        }
        String fileName = required(properties, "fileName");
        String targetFileName = required(properties, "targetFileName");
        if (!SharedDirectoryService.isSafeFileName(fileName)
                || !SharedDirectoryService.isSafeFileName(targetFileName)
                || fileName.length() > 255 || targetFileName.length() > 255) {
            throw new IOException("Unsafe filename in resume manifest.");
        }
        long totalBytes = longValue(properties, "totalBytes");
        int chunkBytes = integer(properties, "chunkBytes");
        String sha256 = required(properties, "sha256").toLowerCase(java.util.Locale.ROOT);
        if (totalBytes < 0 || chunkBytes < 1 || chunkBytes > 16 * 1024 * 1024
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid file metadata in resume manifest.");
        }
        long chunkCountLong = totalBytes == 0 ? 0 : (totalBytes + chunkBytes - 1) / chunkBytes;
        if (chunkCountLong > 1_000_000) {
            throw new IOException("Resume manifest contains too many chunks.");
        }
        int chunkCount = Math.toIntExact(chunkCountLong);
        List<String> hashes = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            String hash = properties.getProperty("chunk." + index, "");
            if (!hash.isEmpty() && !hash.matches("[0-9a-fA-F]{64}")) {
                hash = "";
            }
            hashes.add(hash.toLowerCase(java.util.Locale.ROOT));
        }

        Path partPath = partPathFor(manifestPath);
        if (Files.isSymbolicLink(partPath)
                || !Files.isRegularFile(partPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Partial download is missing or is not a regular file.");
        }
        long actualSize = Files.size(partPath);
        if (actualSize > totalBytes) {
            throw new IOException("Partial download is larger than the expected source file.");
        }

        Manifest manifest = new Manifest(taskId, fileName, targetFileName,
                totalBytes, sha256, chunkBytes, hashes);
        boolean changed = false;
        for (int index = 0; index < chunkCount; index++) {
            if (!manifest.isCompleted(index)) {
                continue;
            }
            long offset = (long) index * chunkBytes;
            long length = Math.min(chunkBytes, totalBytes - offset);
            if (offset + length > actualSize
                    || !manifest.chunkHashes().get(index).equals(hashRange(partPath, offset, length))) {
                manifest.chunkHashes().set(index, "");
                changed = true;
            }
        }
        if (changed) {
            save(manifestPath, manifest);
        }
        return manifest;
    }

    static void save(Path manifestPath, Manifest manifest) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(FORMAT_VERSION));
        properties.setProperty("taskId", manifest.taskId().toString());
        properties.setProperty("fileName", manifest.fileName());
        properties.setProperty("targetFileName", manifest.targetFileName());
        properties.setProperty("totalBytes", Long.toString(manifest.totalBytes()));
        properties.setProperty("sha256", manifest.sha256());
        properties.setProperty("chunkBytes", Integer.toString(manifest.chunkBytes()));
        properties.setProperty("totalChunks", Long.toString(manifest.totalChunks()));
        for (int index = 0; index < manifest.chunkHashes().size(); index++) {
            String hash = manifest.chunkHashes().get(index);
            if (hash != null && !hash.isBlank()) {
                properties.setProperty("chunk." + index, hash);
            }
        }

        Path temporary = manifestPath.resolveSibling(manifestPath.getFileName() + ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                properties.store(output, "P2P file-sharing resumable download");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, manifestPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String hashRange(Path path, long offset, long length) throws IOException {
        MessageDigest digest = sha256Digest();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long position = offset;
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new IOException("Partial file ended before a completed range.");
                }
                if (read == 0) {
                    Thread.onSpinWait();
                    continue;
                }
                digest.update(buffer.array(), 0, read);
                position += read;
                remaining -= read;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static Path partPathFor(Path manifestPath) {
        String name = manifestPath.getFileName().toString();
        return manifestPath.resolveSibling(name.substring(0, name.length() - ".resume".length()));
    }

    static List<Path> findManifests(Path directory) throws IOException {
        List<Path> manifests = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.part.resume")) {
            for (Path path : stream) {
                manifests.add(path);
            }
        }
        return manifests;
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing resume manifest field: " + key);
        }
        return value;
    }

    private static int integer(Properties properties, String key) throws IOException {
        try {
            return Integer.parseInt(required(properties, key));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid integer in resume manifest: " + key, exception);
        }
    }

    private static long longValue(Properties properties, String key) throws IOException {
        try {
            return Long.parseLong(required(properties, key));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid number in resume manifest: " + key, exception);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }
}
