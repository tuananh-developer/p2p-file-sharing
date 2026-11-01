package com.tanh.file_sharing.sharing;

import com.tanh.file_sharing.model.SharedDirectorySnapshot;
import com.tanh.file_sharing.model.SharedFileSnapshot;
import com.tanh.file_sharing.peer.PeerException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Manages the user-approved directory for the lifetime of this Java process.
 * Only readable regular files directly inside the directory are exposed.
 */
public final class SharedDirectoryService {
    private volatile Path sharedDirectory;

    public SharedDirectorySnapshot setDirectory(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new PeerException("INVALID_DIRECTORY", "A non-empty shared directory path is required.");
        }

        final Path requested;
        try {
            requested = Path.of(rawPath.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new PeerException("INVALID_DIRECTORY", "The shared directory path is invalid.", exception);
        }

        try {
            if (!Files.isDirectory(requested)) {
                throw new PeerException("INVALID_DIRECTORY", "The selected path is not an existing directory.");
            }

            Path canonical = requested.toRealPath();
            if (!Files.isReadable(canonical) || !Files.isExecutable(canonical)) {
                throw new PeerException("PERMISSION_DENIED", "The selected directory cannot be read.");
            }

            // Open the directory before replacing the previous configuration.
            // This keeps a valid previous selection when a new selection fails.
            try (DirectoryStream<Path> ignored = Files.newDirectoryStream(canonical)) {
                // Opening the stream validates access; listing is done on demand.
            }

            sharedDirectory = canonical;
            return snapshot();
        } catch (AccessDeniedException exception) {
            throw new PeerException("PERMISSION_DENIED", "The selected directory cannot be accessed.", exception);
        } catch (SecurityException exception) {
            throw new PeerException("PERMISSION_DENIED", "The selected directory cannot be accessed.", exception);
        } catch (IOException exception) {
            throw new PeerException("INVALID_DIRECTORY",
                    "Could not access the selected directory: " + exception.getMessage(), exception);
        }
    }

    public SharedDirectorySnapshot snapshot() {
        Path current = sharedDirectory;
        return new SharedDirectorySnapshot(current != null, current == null ? null : current.toString());
    }

    public List<SharedFileSnapshot> listFiles() {
        Path root = sharedDirectory;
        if (root == null) {
            throw new PeerException("NOT_SHARING", "No shared directory has been configured.");
        }

        List<SharedFileSnapshot> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                // Do not follow symbolic links, and do not recurse into child directories.
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(entry)) {
                    continue;
                }

                String fileName = entry.getFileName().toString();
                if (!isSafeFileName(fileName)) {
                    continue;
                }

                try {
                    files.add(new SharedFileSnapshot(fileName, Files.size(entry)));
                } catch (IOException ignored) {
                    // A file can disappear or become inaccessible while the directory is scanned.
                }
            }
        } catch (AccessDeniedException exception) {
            throw new PeerException("PERMISSION_DENIED", "The shared directory is no longer readable.", exception);
        } catch (DirectoryIteratorException exception) {
            throw new PeerException("SHARED_DIRECTORY_UNAVAILABLE",
                    "The shared directory changed or became unavailable while being listed.", exception);
        } catch (SecurityException exception) {
            throw new PeerException("PERMISSION_DENIED", "The shared directory is no longer readable.", exception);
        } catch (IOException exception) {
            throw new PeerException("SHARED_DIRECTORY_UNAVAILABLE",
                    "Could not list the shared directory.", exception);
        }

        files.sort(Comparator.comparing(SharedFileSnapshot::fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SharedFileSnapshot::fileName));
        return List.copyOf(files);
    }

    /**
     * Opens a shared file without following symbolic links. The returned handle
     * pins the already-validated file descriptor for the duration of a transfer.
     */
    public SharedFileSource openFile(String fileName) {
        Path root = sharedDirectory;
        if (root == null) {
            throw new PeerException("NOT_SHARING", "No shared directory has been configured.");
        }
        if (!isSafeFileName(fileName) || fileName.length() > 255) {
            throw new PeerException("FILE_UNAVAILABLE", "The requested file is unavailable.");
        }

        Path candidate = root.resolve(fileName).normalize();
        if (!root.equals(candidate.getParent())) {
            throw new PeerException("FILE_UNAVAILABLE", "The requested file is unavailable.");
        }

        try {
            if (Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(candidate)) {
                throw new PeerException("FILE_UNAVAILABLE", "The requested file is unavailable.");
            }

            Path canonical = candidate.toRealPath();
            if (!root.equals(canonical.getParent())) {
                throw new PeerException("FILE_UNAVAILABLE", "The requested file is unavailable.");
            }

            FileChannel channel = FileChannel.open(
                    candidate, Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            boolean handedOff = false;
            try {
                long expectedSize = channel.size();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                long hashedBytes = 0;
                while (true) {
                    buffer.clear();
                    int read = channel.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    hashedBytes += read;
                    buffer.flip();
                    digest.update(buffer);
                }
                if (hashedBytes != expectedSize || channel.size() != expectedSize) {
                    throw new PeerException("FILE_CHANGED", "The requested file changed while preparing the transfer.");
                }
                channel.position(0);
                SharedFileSource source = new SharedFileSource(
                        fileName, expectedSize, HexFormat.of().formatHex(digest.digest()), channel);
                handedOff = true;
                return source;
            } finally {
                if (!handedOff) {
                    channel.close();
                }
            }
        } catch (PeerException exception) {
            throw exception;
        } catch (SecurityException exception) {
            throw new PeerException("FILE_UNAVAILABLE", "The requested file is unavailable.", exception);
        } catch (IOException exception) {
            throw new PeerException("FILE_UNAVAILABLE", "The requested file is unavailable.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    /**
     * D-02 filename validation shared by local publication and remote metadata
     * validation. Network peers may send names, never filesystem paths.
     */
    public static boolean isSafeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()
                || ".".equals(fileName) || "..".equals(fileName)
                || fileName.contains("..")
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0) {
            return false;
        }

        for (int index = 0; index < fileName.length(); index++) {
            if (Character.isISOControl(fileName.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
