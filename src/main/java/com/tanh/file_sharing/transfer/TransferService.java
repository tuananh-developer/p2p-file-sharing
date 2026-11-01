package com.tanh.file_sharing.transfer;

import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.model.DownloadAccepted;
import com.tanh.file_sharing.model.TransferSnapshot;
import com.tanh.file_sharing.model.TransferStatus;
import com.tanh.file_sharing.peer.PeerConnection;
import com.tanh.file_sharing.peer.PeerException;
import com.tanh.file_sharing.sharing.SharedDirectoryService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates asynchronous downloads, destination-name allocation, integrity
 * verification, cancellation and lifecycle events.
 */
public final class TransferService implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(TransferService.class.getName());
    private static final int MAX_TASKS = 64;
    private static final int MAX_HISTORY = 100;
    private static final int MAX_MULTI_SOURCE_PEERS = 16;
    private static final int MAX_PARALLEL_RANGES = 8;
    private static final int RANGE_CHUNK_BYTES = 4 * 1024 * 1024;

    private final EventPublisher events;
    private final Function<UUID, PeerConnection> connectionLookup;
    private final BooleanSupplier peerRunning;
    private final ExecutorService executor;
    private final ExecutorService rangeExecutor;
    private final Map<UUID, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final Object destinationLock = new Object();
    private final java.util.Set<Path> reservedTargets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public TransferService(
            EventPublisher events,
            Function<UUID, PeerConnection> connectionLookup,
            BooleanSupplier peerRunning) {
        this.events = events == null ? EventPublisher.noop() : events;
        this.connectionLookup = connectionLookup;
        this.peerRunning = peerRunning;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "p2p-transfer-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.rangeExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_RANGES, runnable -> {
            Thread thread = new Thread(runnable, "p2p-range-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public DownloadAccepted download(UUID peerId, String fileName, String destinationDirectory) {
        if (peerId == null) {
            throw new PeerException("INVALID_ARGUMENT", "A valid peerId is required.");
        }
        return queueDownload(List.of(peerId), fileName, destinationDirectory, false);
    }

    /**
     * Queues a ranged download using every currently connected candidate source
     * that advertises identical size and SHA-256 metadata.
     */
    public DownloadAccepted downloadFromPeers(
            List<UUID> candidatePeerIds, String fileName, String destinationDirectory) {
        if (candidatePeerIds == null || candidatePeerIds.isEmpty()
                || candidatePeerIds.size() > MAX_MULTI_SOURCE_PEERS
                || candidatePeerIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new PeerException("INVALID_ARGUMENT",
                    "Between 1 and " + MAX_MULTI_SOURCE_PEERS + " candidate peer IDs are required.");
        }
        List<UUID> uniquePeers = List.copyOf(new LinkedHashSet<>(candidatePeerIds));
        return queueDownload(uniquePeers, fileName, destinationDirectory, true);
    }

    private DownloadAccepted queueDownload(
            List<UUID> peerIds, String fileName, String destinationDirectory, boolean multiSource) {
        if (closed) {
            throw new PeerException("PEER_NOT_RUNNING", "The transfer service is shutting down.");
        }
        if (!peerRunning.getAsBoolean()) {
            throw new PeerException("PEER_NOT_RUNNING", "Start the local peer before downloading a file.");
        }
        if (!SharedDirectoryService.isSafeFileName(fileName) || fileName.length() > 255) {
            throw new PeerException("INVALID_FILE_NAME", "A safe file name is required.");
        }

        List<UUID> availablePeers = peerIds.stream()
                .filter(id -> {
                    PeerConnection connection = connectionLookup.apply(id);
                    return connection != null && !connection.isClosed();
                })
                .toList();
        if (availablePeers.isEmpty()) {
            throw new PeerException("PEER_NOT_CONNECTED", "None of the selected file sources is connected.");
        }
        if (!multiSource && availablePeers.size() != 1) {
            throw new PeerException("PEER_NOT_CONNECTED", "The selected file source is not connected.");
        }

        Path destination = resolveDestination(destinationDirectory);
        if (tasks.values().stream().filter(task -> !task.status.isTerminal()).count() >= MAX_TASKS) {
            throw new PeerException("TRANSFER_LIMIT_REACHED", "Too many active transfer tasks.");
        }

        ReservedTarget reserved = reserveTarget(destination, fileName);
        if (!peerRunning.getAsBoolean() || closed) {
            deletePartQuietly(reserved.partPath);
            throw new PeerException("PEER_NOT_RUNNING", "The local peer stopped before the download was queued.");
        }
        DownloadTask task = new DownloadTask(UUID.randomUUID(), availablePeers, fileName, reserved, multiSource);
        tasks.put(task.taskId, task);
        publishStatus(task, multiSource ? "Multi-source download queued." : "Download queued.");

        try {
            Future<?> future = executor.submit(() -> runDownload(task));
            task.future = future;
        } catch (RuntimeException exception) {
            tasks.remove(task.taskId);
            releaseTarget(task);
            deletePartQuietly(task.partPath);
            throw new PeerException("TRANSFER_QUEUE_FAILED", "Could not queue the download.", exception);
        }
        pruneHistory();
        return new DownloadAccepted(task.taskId, task.targetFileName, task.status);
    }

    public TransferSnapshot cancel(UUID taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) {
            throw new PeerException("UNKNOWN_TASK", "No transfer task exists with id " + taskId + ".");
        }
        synchronized (task.lock) {
            if (task.status.isTerminal()) {
                throw new PeerException("TASK_ALREADY_FINISHED", "The transfer task has already finished.");
            }
            task.cancelled.set(true);
            task.status = TransferStatus.CANCELLED;
            task.detail = "Cancelled by user.";
            Thread worker = task.worker;
            if (worker != null) {
                worker.interrupt();
            }
            publishStatus(task, task.detail);
        }
        return task.snapshot();
    }

    public List<TransferSnapshot> listTransfers() {
        return tasks.values().stream()
                .map(DownloadTask::snapshot)
                .sorted(Comparator.comparing(snapshot -> snapshot.taskId().toString()))
                .toList();
    }

    /** Cancels all active tasks and waits for their workers to release files/sockets. */
    public boolean cancelAllAndAwait(long timeoutMillis) {
        List<DownloadTask> active = tasks.values().stream()
                .filter(task -> task.finished.getCount() > 0)
                .toList();
        for (DownloadTask task : active) {
            synchronized (task.lock) {
                if (!task.status.isTerminal()) {
                    task.cancelled.set(true);
                    task.status = TransferStatus.CANCELLED;
                    task.detail = "Cancelled because the local peer is stopping.";
                    if (task.worker != null) {
                        task.worker.interrupt();
                    }
                    publishStatus(task, task.detail);
                }
            }
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (DownloadTask task : active) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            try {
                if (!task.finished.await(remaining, TimeUnit.NANOSECONDS)) {
                    return false;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        closed = true;
        cancelAllAndAwait(2_000);
        executor.shutdownNow();
        rangeExecutor.shutdownNow();
    }

    private void runDownload(DownloadTask task) {
        synchronized (task.lock) {
            task.worker = Thread.currentThread();
        }
        try {
            if (task.cancelled.get()) {
                return;
            }
            if (!peerRunning.getAsBoolean()) {
                throw new PeerException("PEER_NOT_RUNNING", "The local peer stopped before the download began.");
            }
            if (task.multiSource) {
                runMultiSourceDownload(task);
                return;
            }
            transition(task, TransferStatus.CONNECTING, "Connecting to file source.");

            PeerConnection connection = connectionLookup.apply(task.peerId);
            if (connection == null || connection.isClosed()) {
                throw new PeerException("PEER_NOT_CONNECTED", "The file source disconnected before the download began.");
            }

            MessageDigest digest = sha256Digest();
            try (OutputStream output = Files.newOutputStream(
                    task.partPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                connection.downloadFile(task.fileName, new PeerConnection.DownloadSink() {
                    @Override
                    public void onMetadata(PeerConnection.FileMetadata metadata) {
                        if (!task.fileName.equals(metadata.fileName())) {
                            throw new PeerException("INVALID_PEER_RESPONSE", "The peer returned a different file name.");
                        }
                        task.totalBytes = metadata.size();
                        task.expectedSha256 = metadata.sha256();
                        transition(task, TransferStatus.TRANSFERRING, "Receiving file data.");
                    }

                    @Override
                    public void onChunk(byte[] bytes) throws IOException {
                        if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
                            throw new PeerException("TRANSFER_CANCELLED", "The download was cancelled.");
                        }
                        output.write(bytes);
                        digest.update(bytes);
                        task.receivedBytes += bytes.length;
                        events.publish("transferProgress", Map.of(
                                "taskId", task.taskId.toString(),
                                "receivedBytes", task.receivedBytes,
                                "totalBytes", task.totalBytes,
                                "percent", task.totalBytes == 0 ? 100.0
                                        : Math.min(100.0, task.receivedBytes * 100.0 / task.totalBytes)));
                    }
                }, 5_000, task.cancelled::get);
                output.flush();
            }

            if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new PeerException("TRANSFER_CANCELLED", "The download was cancelled.");
            }
            if (task.receivedBytes != task.totalBytes) {
                throw new PeerException("TRANSFER_SIZE_MISMATCH", "The received byte count does not match the source.");
            }

            transition(task, TransferStatus.VERIFYING, "Verifying SHA-256.");
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(task.expectedSha256)) {
                throw new PeerException("INTEGRITY_CHECK_FAILED", "SHA-256 verification failed.");
            }

            completeDownload(task);
        } catch (PeerException exception) {
            if (task.cancelled.get() || "TRANSFER_CANCELLED".equals(exception.getCode())) {
                markCancelled(task, "Download cancelled.");
            } else {
                String detail = exception.getCode() + ": " + exception.getMessage();
                LOGGER.log(Level.WARNING,
                        "Transfer task " + task.taskId + " for '" + task.fileName + "' failed: " + detail);
                markFailed(task, detail);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markCancelled(task, "Download cancelled.");
        } catch (IOException exception) {
            if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
                markCancelled(task, "Download cancelled.");
            } else {
                LOGGER.log(Level.WARNING, "Download task " + task.taskId + " failed.", exception);
                markFailed(task, "FILE_IO_ERROR: Could not write the downloaded file.");
            }
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Unexpected download failure for task " + task.taskId, exception);
            if (task.cancelled.get()) {
                markCancelled(task, "Download cancelled.");
            } else {
                markFailed(task, "INTERNAL_ERROR: The download could not be completed.");
            }
        } finally {
            if (task.status != TransferStatus.COMPLETED) {
                deletePartQuietly(task.partPath);
            }
            releaseTarget(task);
            synchronized (task.lock) {
                task.worker = null;
            }
            task.finished.countDown();
        }
    }

    private void runMultiSourceDownload(DownloadTask task) throws InterruptedException, IOException {
        transition(task, TransferStatus.CONNECTING, "Discovering identical file copies on connected peers.");

        List<Future<PeerSourceInfo>> metadataRequests = new ArrayList<>();
        for (UUID peerId : task.sourcePeerIds) {
            metadataRequests.add(rangeExecutor.submit(() -> {
                PeerConnection connection = connectionLookup.apply(peerId);
                if (connection == null || connection.isClosed()) {
                    return new PeerSourceInfo(peerId, null, "Peer is no longer connected.");
                }
                try {
                    PeerConnection.FileMetadata metadata = connection.requestFileInfo(task.fileName);
                    if (!task.fileName.equals(metadata.fileName())) {
                        return new PeerSourceInfo(peerId, null, "Peer returned a different file name.");
                    }
                    return new PeerSourceInfo(peerId, metadata, null);
                } catch (PeerException exception) {
                    return new PeerSourceInfo(peerId, null, exception.getCode() + ": " + exception.getMessage());
                } catch (RuntimeException exception) {
                    return new PeerSourceInfo(peerId, null, "Metadata request failed.");
                }
            }));
        }

        Map<String, List<PeerSourceInfo>> identicalCopies = new HashMap<>();
        for (Future<PeerSourceInfo> request : metadataRequests) {
            PeerSourceInfo result;
            try {
                result = request.get();
            } catch (InterruptedException exception) {
                metadataRequests.forEach(future -> future.cancel(true));
                throw exception;
            } catch (ExecutionException exception) {
                continue;
            }
            if (result.metadata() == null) {
                continue;
            }
            String key = result.metadata().size() + ":" + result.metadata().sha256();
            identicalCopies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
        }

        if (identicalCopies.isEmpty()) {
            throw new PeerException("FILE_UNAVAILABLE",
                    "No selected peer could provide metadata for '" + task.fileName + "'.");
        }

        List<PeerSourceInfo> selected = identicalCopies.values().stream()
                .max(Comparator.comparingInt(List::size))
                .orElseThrow();
        long largestGroup = selected.size();
        long matchingGroups = identicalCopies.values().stream()
                .filter(group -> group.size() == largestGroup)
                .count();
        if (matchingGroups > 1) {
            throw new PeerException("SOURCE_MISMATCH",
                    "Connected peers advertise different contents for this file; choose one source explicitly.");
        }

        PeerConnection.FileMetadata metadata = selected.getFirst().metadata();
        List<UUID> selectedPeerIds = selected.stream()
                .map(PeerSourceInfo::peerId)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        task.sourcePeerIds = selectedPeerIds;
        task.totalBytes = metadata.size();
        task.expectedSha256 = metadata.sha256();
        task.totalChunks = metadata.size() == 0 ? 0
                : metadata.size() / RANGE_CHUNK_BYTES
                        + (metadata.size() % RANGE_CHUNK_BYTES == 0 ? 0 : 1);
        transition(task, TransferStatus.TRANSFERRING,
                "Downloading " + task.totalChunks + " ranges from " + selectedPeerIds.size() + " matching peer(s).");

        AtomicLong completedBytes = new AtomicLong();
        AtomicLong completedChunks = new AtomicLong();
        try (FileChannel output = FileChannel.open(
                task.partPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long nextChunk = 0;
            while (nextChunk < task.totalChunks) {
                if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new PeerException("TRANSFER_CANCELLED", "The multi-source download was cancelled.");
                }

                List<Future<?>> batch = new ArrayList<>();
                int batchSize = (int) Math.min(MAX_PARALLEL_RANGES, task.totalChunks - nextChunk);
                for (int index = 0; index < batchSize; index++) {
                    long chunkIndex = nextChunk + index;
                    long offset = chunkIndex * RANGE_CHUNK_BYTES;
                    long length = Math.min(RANGE_CHUNK_BYTES, metadata.size() - offset);
                    batch.add(rangeExecutor.submit(() -> {
                        downloadRangeWithFailover(
                                task, metadata, selectedPeerIds, output, chunkIndex, offset, length,
                                completedBytes, completedChunks);
                        return null;
                    }));
                }

                try {
                    for (Future<?> future : batch) {
                        future.get();
                    }
                } catch (ExecutionException exception) {
                    batch.forEach(future -> future.cancel(true));
                    Throwable cause = exception.getCause();
                    if (cause instanceof PeerException peerException) {
                        throw peerException;
                    }
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new PeerException("TRANSFER_FAILED",
                            "A file range could not be downloaded from any matching peer.", cause);
                } catch (InterruptedException exception) {
                    batch.forEach(future -> future.cancel(true));
                    throw exception;
                }
                nextChunk += batchSize;
            }
            output.force(true);
        }

        if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new PeerException("TRANSFER_CANCELLED", "The multi-source download was cancelled.");
        }
        if (Files.size(task.partPath) != metadata.size()
                || completedBytes.get() != metadata.size()
                || completedChunks.get() != task.totalChunks) {
            throw new PeerException("TRANSFER_SIZE_MISMATCH",
                    "The downloaded ranges do not cover the complete source file.");
        }

        transition(task, TransferStatus.VERIFYING, "Verifying assembled file SHA-256.");
        String actualHash = sha256(task.partPath);
        if (!actualHash.equalsIgnoreCase(metadata.sha256())) {
            throw new PeerException("INTEGRITY_CHECK_FAILED",
                    "The assembled multi-source file does not match the source SHA-256.");
        }
        task.receivedBytes = metadata.size();
        completeDownload(task);
    }

    private void downloadRangeWithFailover(
            DownloadTask task,
            PeerConnection.FileMetadata metadata,
            List<UUID> peerIds,
            FileChannel output,
            long chunkIndex,
            long offset,
            long length,
            AtomicLong completedBytes,
            AtomicLong completedChunks) throws IOException, InterruptedException {
        PeerException lastFailure = null;
        int startIndex = (int) (chunkIndex % peerIds.size());
        for (int attempt = 0; attempt < peerIds.size(); attempt++) {
            if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new PeerException("TRANSFER_CANCELLED", "The multi-source download was cancelled.");
            }

            UUID peerId = peerIds.get((startIndex + attempt) % peerIds.size());
            PeerConnection connection = connectionLookup.apply(peerId);
            if (connection == null || connection.isClosed()) {
                lastFailure = new PeerException("PEER_NOT_CONNECTED",
                        "Peer " + peerId + " disconnected before its range was requested.");
                continue;
            }

            try {
                connection.downloadFileRange(metadata, offset, length, new PeerConnection.DownloadSink() {
                    @Override
                    public void onMetadata(PeerConnection.FileMetadata received) {
                        if (!metadata.equals(received)) {
                            throw new PeerException("SOURCE_MISMATCH",
                                    "The peer returned metadata for a different file version.");
                        }
                    }

                    @Override
                    public void onChunk(byte[] bytes) throws IOException {
                        if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
                            throw new PeerException("TRANSFER_CANCELLED", "The range transfer was cancelled.");
                        }
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        long position = offset;
                        while (buffer.hasRemaining()) {
                            int written = output.write(buffer, position);
                            if (written < 0) {
                                throw new IOException("Could not write the downloaded file range.");
                            }
                            if (written == 0) {
                                Thread.onSpinWait();
                            } else {
                                position += written;
                            }
                        }
                    }
                }, 5_000, task.cancelled::get);

                completedBytes.addAndGet(length);
                completedChunks.incrementAndGet();
                synchronized (task.progressLock) {
                    task.receivedBytes = completedBytes.get();
                    task.completedChunks = completedChunks.get();
                    events.publish("transferProgress", Map.of(
                            "taskId", task.taskId.toString(),
                            "receivedBytes", task.receivedBytes,
                            "totalBytes", task.totalBytes,
                            "completedChunks", task.completedChunks,
                            "totalChunks", task.totalChunks,
                            "percent", task.totalBytes == 0 ? 100.0
                                    : Math.min(100.0, task.receivedBytes * 100.0 / task.totalBytes)));
                }
                return;
            } catch (PeerException exception) {
                if (task.cancelled.get() || "TRANSFER_CANCELLED".equals(exception.getCode())) {
                    throw exception;
                }
                lastFailure = exception;
            }
        }
        throw new PeerException("RANGE_UNAVAILABLE",
                "No matching peer could provide range at offset " + offset + ".",
                lastFailure);
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void completeDownload(DownloadTask task) throws IOException {
        synchronized (task.lock) {
            if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new PeerException("TRANSFER_CANCELLED", "The download was cancelled.");
            }
            moveToNonConflictingTarget(task);
            task.status = TransferStatus.COMPLETED;
            task.detail = "Download completed and SHA-256 verified.";
            publishStatus(task, task.detail);
        }
    }

    private void moveToNonConflictingTarget(DownloadTask task) throws IOException {
        while (true) {
            try {
                // No REPLACE_EXISTING: a file created by another process is never overwritten.
                Files.move(task.partPath, task.targetPath);
                return;
            } catch (FileAlreadyExistsException exception) {
                Path oldTarget = task.targetPath;
                Path oldPart = task.partPath;
                ReservedTarget next = reserveNextTarget(task.destination, task.fileName);
                try {
                    Files.move(oldPart, next.partPath);
                } catch (FileAlreadyExistsException partCollision) {
                    releaseTargetPath(next.targetPath);
                    continue;
                } catch (IOException moveFailure) {
                    releaseTargetPath(next.targetPath);
                    throw moveFailure;
                }
                synchronized (destinationLock) {
                    reservedTargets.remove(oldTarget);
                    task.targetPath = next.targetPath;
                    task.partPath = next.partPath;
                    task.targetFileName = next.fileName;
                }
                publishStatus(task, "Destination name was taken; retrying with " + next.fileName + ".");
            }
        }
    }

    private Path resolveDestination(String rawDirectory) {
        if (rawDirectory == null || rawDirectory.isBlank()) {
            throw new PeerException("INVALID_DESTINATION", "A destination directory is required.");
        }
        try {
            Path path = Path.of(rawDirectory.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new PeerException("INVALID_DESTINATION", "The destination must be an existing directory.");
            }
            Path canonical = path.toRealPath();
            if (!Files.isWritable(canonical)) {
                throw new PeerException("PERMISSION_DENIED", "The destination directory is not writable.");
            }
            return canonical;
        } catch (InvalidPathException exception) {
            throw new PeerException("INVALID_DESTINATION", "The destination path is invalid.", exception);
        } catch (SecurityException exception) {
            throw new PeerException("PERMISSION_DENIED", "The destination directory cannot be accessed.", exception);
        } catch (IOException exception) {
            throw new PeerException("INVALID_DESTINATION", "The destination directory cannot be accessed.", exception);
        }
    }

    private ReservedTarget reserveTarget(Path destination, String fileName) {
        synchronized (destinationLock) {
            for (int index = 0; index < 10_000; index++) {
                String candidate = candidateName(fileName, index);
                Path target = destination.resolve(candidate);
                Path part = destination.resolve(candidate + ".part");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                        || reservedTargets.contains(target)) {
                    continue;
                }
                try {
                    Files.createFile(part);
                    reservedTargets.add(target);
                    return new ReservedTarget(candidate, target, part);
                } catch (FileAlreadyExistsException ignored) {
                    // A stale temporary file belongs to another task or process.
                } catch (IOException exception) {
                    throw new PeerException("INVALID_DESTINATION", "Could not create a temporary download file.", exception);
                }
            }
        }
        throw new PeerException("DESTINATION_NAME_EXHAUSTED", "Could not allocate a unique destination file name.");
    }

    private ReservedTarget reserveNextTarget(Path destination, String fileName) {
        synchronized (destinationLock) {
            for (int index = 1; index < 10_000; index++) {
                String candidate = candidateName(fileName, index);
                Path target = destination.resolve(candidate);
                Path part = destination.resolve(candidate + ".part");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                        || reservedTargets.contains(target)) {
                    continue;
                }
                reservedTargets.add(target);
                return new ReservedTarget(candidate, target, part);
            }
        }
        throw new PeerException("DESTINATION_NAME_EXHAUSTED", "Could not allocate a unique destination file name.");
    }

    private static String candidateName(String original, int suffix) {
        if (suffix == 0) {
            return original;
        }
        int dot = original.lastIndexOf('.');
        if (dot <= 0) {
            return original + " (" + suffix + ")";
        }
        return original.substring(0, dot) + " (" + suffix + ")" + original.substring(dot);
    }

    private void transition(DownloadTask task, TransferStatus next, String detail) {
        synchronized (task.lock) {
            if (task.status.isTerminal()) {
                return;
            }
            task.status = next;
            task.detail = detail;
            publishStatus(task, detail);
        }
    }

    private void markCancelled(DownloadTask task, String detail) {
        synchronized (task.lock) {
            if (task.status.isTerminal()) {
                return;
            }
            task.status = TransferStatus.CANCELLED;
            task.detail = detail;
            publishStatus(task, detail);
        }
    }

    private void markFailed(DownloadTask task, String detail) {
        synchronized (task.lock) {
            if (task.status.isTerminal()) {
                return;
            }
            task.status = TransferStatus.FAILED;
            task.detail = detail;
            publishStatus(task, detail);
        }
    }

    private void publishStatus(DownloadTask task, String detail) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("taskId", task.taskId.toString());
        snapshot.put("peerId", task.peerId.toString());
        snapshot.put("fileName", task.fileName);
        snapshot.put("targetFileName", task.targetFileName);
        snapshot.put("status", task.status.name());
        snapshot.put("receivedBytes", task.receivedBytes);
        snapshot.put("totalBytes", task.totalBytes);
        snapshot.put("detail", detail);
        snapshot.put("sourcePeerIds", task.sourcePeerIds.stream().map(UUID::toString).toList());
        snapshot.put("completedChunks", task.completedChunks);
        snapshot.put("totalChunks", task.totalChunks);
        events.publish("transferStatusChanged", snapshot);
    }

    private void releaseTarget(DownloadTask task) {
        synchronized (destinationLock) {
            reservedTargets.remove(task.targetPath);
        }
    }

    private void releaseTargetPath(Path targetPath) {
        synchronized (destinationLock) {
            reservedTargets.remove(targetPath);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private void deletePartQuietly(Path part) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not delete temporary file " + part, exception);
        }
    }

    private void pruneHistory() {
        if (tasks.size() <= MAX_HISTORY) {
            return;
        }
        List<DownloadTask> completed = tasks.values().stream()
                .filter(task -> task.status.isTerminal())
                .sorted(Comparator.comparingLong(task -> task.createdAt))
                .toList();
        for (DownloadTask task : completed) {
            if (tasks.size() <= MAX_HISTORY) {
                break;
            }
            tasks.remove(task.taskId, task);
        }
    }

    private record PeerSourceInfo(UUID peerId, PeerConnection.FileMetadata metadata, String error) {
    }

    private record ReservedTarget(String fileName, Path targetPath, Path partPath) {
    }

    private static final class DownloadTask {
        private final Object lock = new Object();
        private final Object progressLock = new Object();
        private final UUID taskId;
        private final UUID peerId;
        private volatile List<UUID> sourcePeerIds;
        private final boolean multiSource;
        private final String fileName;
        private final Path destination;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final long createdAt = System.nanoTime();
        private volatile String targetFileName;
        private volatile Path targetPath;
        private volatile Path partPath;
        private volatile TransferStatus status = TransferStatus.QUEUED;
        private volatile long receivedBytes;
        private volatile long totalBytes;
        private volatile long totalChunks;
        private volatile long completedChunks;
        private volatile String expectedSha256;
        private volatile String detail = "Download queued.";
        private volatile Thread worker;
        private volatile Future<?> future;

        private DownloadTask(UUID taskId, List<UUID> peerIds, String fileName,
                ReservedTarget target, boolean multiSource) {
            this.taskId = taskId;
            this.peerId = peerIds.getFirst();
            this.sourcePeerIds = List.copyOf(peerIds);
            this.multiSource = multiSource;
            this.fileName = fileName;
            this.destination = target.targetPath.getParent();
            this.targetFileName = target.fileName;
            this.targetPath = target.targetPath;
            this.partPath = target.partPath;
        }

        private TransferSnapshot snapshot() {
            return new TransferSnapshot(taskId, peerId, fileName, targetFileName, status,
                    receivedBytes, totalBytes, detail, sourcePeerIds, totalChunks, completedChunks);
        }
    }
}
