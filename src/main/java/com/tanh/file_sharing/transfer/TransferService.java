package com.tanh.file_sharing.transfer;

import com.tanh.file_sharing.event.EventPublisher;
import com.tanh.file_sharing.model.DownloadAccepted;
import com.tanh.file_sharing.model.TransferSnapshot;
import com.tanh.file_sharing.model.ResumableDownloadSnapshot;
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
        publishActivity("Download queued: " + task.targetFileName, "INFO");

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

    /** Lists valid interrupted downloads in a user-selected destination directory. */
    public List<ResumableDownloadSnapshot> listResumableDownloads(String rawDirectory) {
        Path directory = resolveDestination(rawDirectory);
        List<ResumableDownloadSnapshot> snapshots = new ArrayList<>();
        try {
            for (Path manifestPath : ResumeManifestStore.findManifests(directory)) {
                try {
                    ResumeManifestStore.Manifest manifest = ResumeManifestStore.loadAndVerify(manifestPath);
                    if (manifest.chunkBytes() != RANGE_CHUNK_BYTES
                            || Files.exists(directory.resolve(manifest.targetFileName()), LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    DownloadTask active = tasks.get(manifest.taskId());
                    if (active != null && !active.status.isTerminal()) {
                        continue;
                    }
                    snapshots.add(new ResumableDownloadSnapshot(
                            manifest.taskId(), manifest.fileName(), manifest.targetFileName(),
                            manifest.totalBytes(), manifest.completedBytes(),
                            manifest.totalChunks(), manifest.completedChunks()));
                } catch (IOException | RuntimeException exception) {
                    LOGGER.log(Level.WARNING, "Ignoring invalid resume manifest " + manifestPath, exception);
                }
            }
        } catch (IOException exception) {
            throw new PeerException("RESUME_SCAN_FAILED",
                    "Could not scan the selected destination for interrupted downloads.", exception);
        }
        snapshots.sort(Comparator.comparing(snapshot -> snapshot.taskId().toString()));
        return List.copyOf(snapshots);
    }

    /** Resumes only ranges whose saved per-range hashes still match the partial file. */
    public DownloadAccepted resume(UUID taskId, List<UUID> candidatePeerIds, String rawDirectory) {
        if (taskId == null || candidatePeerIds == null || candidatePeerIds.isEmpty()
                || candidatePeerIds.size() > MAX_MULTI_SOURCE_PEERS
                || candidatePeerIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new PeerException("INVALID_ARGUMENT", "A task ID and between 1 and "
                    + MAX_MULTI_SOURCE_PEERS + " candidate peer IDs are required.");
        }
        if (closed || !peerRunning.getAsBoolean()) {
            throw new PeerException("PEER_NOT_RUNNING", "Start the local peer before resuming a download.");
        }
        DownloadTask existing = tasks.get(taskId);
        if (existing != null && !existing.status.isTerminal()) {
            throw new PeerException("TASK_ALREADY_ACTIVE", "This download is already active.");
        }
        if (tasks.values().stream().filter(task -> !task.status.isTerminal()).count() >= MAX_TASKS) {
            throw new PeerException("TRANSFER_LIMIT_REACHED", "Too many active transfer tasks.");
        }
        List<UUID> peers = List.copyOf(new LinkedHashSet<>(candidatePeerIds));
        List<UUID> availablePeers = peers.stream()
                .filter(id -> {
                    PeerConnection connection = connectionLookup.apply(id);
                    return connection != null && !connection.isClosed();
                }).toList();
        if (availablePeers.isEmpty()) {
            throw new PeerException("PEER_NOT_CONNECTED", "Connect to at least one source peer before resuming.");
        }

        Path directory = resolveDestination(rawDirectory);
        ResumeManifestStore.Manifest manifest = null;
        Path manifestPath = null;
        try {
            for (Path candidate : ResumeManifestStore.findManifests(directory)) {
                try {
                    ResumeManifestStore.Manifest loaded = ResumeManifestStore.loadAndVerify(candidate);
                    if (loaded.taskId().equals(taskId)) {
                        manifest = loaded;
                        manifestPath = candidate;
                        break;
                    }
                } catch (IOException | RuntimeException invalidManifest) {
                    LOGGER.log(Level.WARNING, "Ignoring invalid resume manifest " + candidate, invalidManifest);
                }
            }
        } catch (IOException exception) {
            throw new PeerException("RESUME_SCAN_FAILED",
                    "Could not scan the selected destination for interrupted downloads.", exception);
        }
        if (manifest == null || manifestPath == null) {
            throw new PeerException("RESUME_NOT_FOUND",
                    "No valid interrupted download with this task ID exists in the selected folder.");
        }

        Path partPath = ResumeManifestStore.partPathFor(manifestPath);
        Path targetPath = directory.resolve(manifest.targetFileName());
        synchronized (destinationLock) {
            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) || reservedTargets.contains(targetPath)) {
                throw new PeerException("DESTINATION_CONFLICT",
                        "The original destination filename now exists; refusing to overwrite it.");
            }
            reservedTargets.add(targetPath);
        }

        ReservedTarget target = new ReservedTarget(manifest.targetFileName(), targetPath, partPath);
        DownloadTask task = new DownloadTask(taskId, availablePeers, manifest.fileName(),
                target, true, manifest, manifestPath);
        task.totalBytes = manifest.totalBytes();
        task.totalChunks = manifest.totalChunks();
        task.completedChunks = manifest.completedChunks();
        task.receivedBytes = manifest.completedBytes();
        task.detail = "Resume queued; revalidating source metadata.";
        tasks.put(taskId, task);
        publishStatus(task, task.detail);
        publishActivity("Resuming download: " + task.targetFileName, "INFO");
        try {
            task.future = executor.submit(() -> runDownload(task));
        } catch (RuntimeException exception) {
            tasks.remove(taskId, task);
            releaseTarget(task);
            throw new PeerException("TRANSFER_QUEUE_FAILED", "Could not queue the resumed download.", exception);
        }
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
            publishActivity("Download cancelled: " + task.targetFileName, "WARN");
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
                    publishActivity("Download cancelled because the local peer is stopping: " + task.targetFileName, "WARN");
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
        pauseAllAndAwait(5_000);
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
            if (task.pauseRequested.get()) {
                markPausedOrCancelled(task, "Download paused for application shutdown.");
                return;
            }
            if (!peerRunning.getAsBoolean()) {
                throw new PeerException("PEER_NOT_RUNNING", "The local peer stopped before the download began.");
            }
            // All downloads use the same ranged engine so every transfer can be resumed.
            runMultiSourceDownload(task);
        } catch (PeerException exception) {
            if (task.cancelled.get()) {
                markCancelled(task, "Download cancelled.");
            } else if (task.pauseRequested.get() || isResumableInterruption(task, exception)) {
                markPausedOrCancelled(task, exception.getCode() + ": " + exception.getMessage());
            } else {
                String detail = exception.getCode() + ": " + exception.getMessage();
                LOGGER.log(Level.WARNING,
                        "Transfer task " + task.taskId + " for '" + task.fileName + "' failed: " + detail);
                markFailed(task, detail);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (task.pauseRequested.get()) {
                markPausedOrCancelled(task, "Download paused for application shutdown.");
            } else {
                markCancelled(task, "Download cancelled.");
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Download task " + task.taskId + " failed.", exception);
            markFailed(task, "FILE_IO_ERROR: Could not persist or write the downloaded file.");
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Unexpected download failure for task " + task.taskId, exception);
            if (task.cancelled.get()) {
                markCancelled(task, "Download cancelled.");
            } else if (task.pauseRequested.get() && task.resumeManifest != null) {
                markPausedOrCancelled(task, "Download paused for application shutdown.");
            } else {
                markFailed(task, "INTERNAL_ERROR: The download could not be completed.");
            }
        } finally {
            if (task.status != TransferStatus.COMPLETED && task.status != TransferStatus.PAUSED) {
                deletePartQuietly(task.partPath);
                deletePartQuietly(task.resumeManifestPath);
            }
            releaseTarget(task);
            synchronized (task.lock) {
                task.worker = null;
            }
            task.finished.countDown();
        }
    }

    private static boolean isResumableInterruption(DownloadTask task, PeerException exception) {
        if (task.resumeManifest == null) {
            return false;
        }
        return !java.util.Set.of("INTEGRITY_CHECK_FAILED",
                "FILE_IO_ERROR", "TRANSFER_SIZE_MISMATCH").contains(exception.getCode());
    }

    private void markPausedOrCancelled(DownloadTask task, String detail) {
        if (task.resumeManifest == null) {
            markCancelled(task, "Download stopped before resumable metadata was saved.");
            return;
        }
        synchronized (task.lock) {
            if (task.status.isTerminal() && task.status != TransferStatus.PAUSED) {
                return;
            }
            task.status = TransferStatus.PAUSED;
            task.detail = detail + " Completed ranges are saved and can be resumed.";
            publishStatus(task, task.detail);
            publishActivity("Download paused: " + task.targetFileName, "WARN");
        }
    }

    /** Pauses active downloads without deleting their verified ranges. */
    public boolean pauseAllAndAwait(long timeoutMillis) {
        List<DownloadTask> active = tasks.values().stream()
                .filter(task -> task.finished.getCount() > 0)
                .toList();
        for (DownloadTask task : active) {
            task.pauseRequested.set(true);
            Thread worker = task.worker;
            if (worker != null) {
                worker.interrupt();
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
        if (task.resumeManifest != null
                && (metadata.size() != task.resumeManifest.totalBytes()
                || !metadata.sha256().equalsIgnoreCase(task.resumeManifest.sha256()))) {
            throw new PeerException("SOURCE_MISMATCH",
                    "The connected peers no longer have the exact file version saved for this interrupted download.");
        }
        task.sourcePeerIds = selectedPeerIds;
        task.totalBytes = metadata.size();
        task.expectedSha256 = metadata.sha256();
        task.totalChunks = metadata.size() == 0 ? 0
                : metadata.size() / RANGE_CHUNK_BYTES
                        + (metadata.size() % RANGE_CHUNK_BYTES == 0 ? 0 : 1);
        if (task.totalChunks > 1_000_000) {
            throw new PeerException("FILE_TOO_LARGE",
                    "The file requires too many resumable ranges for the current manifest format.");
        }

        if (task.resumeManifest == null) {
            task.resumeManifest = ResumeManifestStore.create(
                    task.taskId, task.fileName, task.targetFileName,
                    metadata.size(), metadata.sha256(), RANGE_CHUNK_BYTES);
            task.resumeManifestPath = ResumeManifestStore.manifestPath(task.partPath);
            ResumeManifestStore.save(task.resumeManifestPath, task.resumeManifest);
        } else if (task.resumeManifest.chunkBytes() != RANGE_CHUNK_BYTES
                || task.resumeManifest.totalChunks() != task.totalChunks) {
            throw new PeerException("RESUME_METADATA_INVALID",
                    "The saved range layout is incompatible with this application version.");
        }

        try (FileChannel output = FileChannel.open(task.partPath, StandardOpenOption.WRITE)) {
            if (metadata.size() > 0 && output.size() < metadata.size()) {
                output.position(metadata.size() - 1);
                output.write(ByteBuffer.wrap(new byte[] {0}));
                output.force(true);
            }
            task.completedChunks = task.resumeManifest.completedChunks();
            task.receivedBytes = task.resumeManifest.completedBytes();
            transition(task, TransferStatus.TRANSFERRING,
                    "Downloading " + task.totalChunks + " ranges; resuming "
                            + task.completedChunks + " verified range(s).");

            List<Long> pendingChunks = new ArrayList<>();
            for (long index = 0; index < task.totalChunks; index++) {
                if (!task.resumeManifest.isCompleted(Math.toIntExact(index))) {
                    pendingChunks.add(index);
                }
            }

            for (int batchStart = 0; batchStart < pendingChunks.size(); batchStart += MAX_PARALLEL_RANGES) {
                if (task.cancelled.get() || task.pauseRequested.get()
                        || Thread.currentThread().isInterrupted()) {
                    throw new PeerException("TRANSFER_CANCELLED", "The download was cancelled or paused.");
                }
                int batchEnd = Math.min(pendingChunks.size(), batchStart + MAX_PARALLEL_RANGES);
                List<Future<?>> batch = new ArrayList<>();
                for (int pendingIndex = batchStart; pendingIndex < batchEnd; pendingIndex++) {
                    long chunkIndex = pendingChunks.get(pendingIndex);
                    long offset = chunkIndex * RANGE_CHUNK_BYTES;
                    long length = Math.min(RANGE_CHUNK_BYTES, metadata.size() - offset);
                    batch.add(rangeExecutor.submit(() -> {
                        downloadRangeWithFailover(task, metadata, selectedPeerIds, output,
                                chunkIndex, offset, length);
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
            }
            output.force(true);
        }

        if (task.cancelled.get() || task.pauseRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new PeerException("TRANSFER_CANCELLED", "The download was cancelled or paused.");
        }
        if (Files.size(task.partPath) != metadata.size()
                || task.resumeManifest.completedBytes() != metadata.size()
                || task.resumeManifest.completedChunks() != task.totalChunks) {
            throw new PeerException("TRANSFER_SIZE_MISMATCH",
                    "The saved ranges do not cover the complete source file.");
        }

        transition(task, TransferStatus.VERIFYING, "Verifying assembled file SHA-256.");
        String actualHash = sha256(task.partPath);
        if (!actualHash.equalsIgnoreCase(metadata.sha256())) {
            throw new PeerException("INTEGRITY_CHECK_FAILED",
                    "The assembled file does not match the source SHA-256.");
        }
        task.receivedBytes = metadata.size();
        task.completedChunks = task.totalChunks;
        completeDownload(task);
    }

    private void downloadRangeWithFailover(
            DownloadTask task,
            PeerConnection.FileMetadata metadata,
            List<UUID> peerIds,
            FileChannel output,
            long chunkIndex,
            long offset,
            long length) throws IOException, InterruptedException {
        PeerException lastFailure = null;
        int startIndex = (int) (chunkIndex % peerIds.size());
        for (int attempt = 0; attempt < peerIds.size(); attempt++) {
            if (task.cancelled.get() || task.pauseRequested.get() || Thread.currentThread().isInterrupted()) {
                throw new PeerException("TRANSFER_CANCELLED", "The range transfer was cancelled or paused.");
            }

            UUID peerId = peerIds.get((startIndex + attempt) % peerIds.size());
            PeerConnection connection = connectionLookup.apply(peerId);
            if (connection == null || connection.isClosed()) {
                lastFailure = new PeerException("PEER_NOT_CONNECTED",
                        "Peer " + peerId + " disconnected before its range was requested.");
                continue;
            }

            try {
                long[] nextWritePosition = {offset};
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
                        if (task.cancelled.get() || task.pauseRequested.get()
                                || Thread.currentThread().isInterrupted()) {
                            throw new PeerException("TRANSFER_CANCELLED", "The range transfer was cancelled or paused.");
                        }
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        long position = nextWritePosition[0];
                        while (buffer.hasRemaining()) {
                            int written;
                            try {
                                written = output.write(buffer, position);
                            } catch (IOException exception) {
                                throw new PeerException("FILE_IO_ERROR",
                                        "Could not write the downloaded file range.", exception);
                            }
                            if (written < 0) {
                                throw new PeerException("FILE_IO_ERROR",
                                        "Could not write the downloaded file range.");
                            }
                            if (written == 0) {
                                Thread.onSpinWait();
                            } else {
                                position += written;
                            }
                        }
                        nextWritePosition[0] = position;
                    }
                }, 5_000, () -> task.cancelled.get() || task.pauseRequested.get());

                if (nextWritePosition[0] != offset + length) {
                    throw new PeerException("FILE_IO_ERROR",
                            "The receiver did not write the complete range interval.");
                }

                output.force(false);
                String rangeHash = ResumeManifestStore.hashRange(task.partPath, offset, length);
                synchronized (task.resumeLock) {
                    task.resumeManifest.chunkHashes().set(Math.toIntExact(chunkIndex), rangeHash);
                    ResumeManifestStore.save(task.resumeManifestPath, task.resumeManifest);
                    task.receivedBytes = task.resumeManifest.completedBytes();
                    task.completedChunks = task.resumeManifest.completedChunks();
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
                if (task.cancelled.get() || task.pauseRequested.get()
                        || "TRANSFER_CANCELLED".equals(exception.getCode())
                        || "FILE_IO_ERROR".equals(exception.getCode())) {
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
            if (task.cancelled.get() || task.pauseRequested.get() || Thread.currentThread().isInterrupted()) {
                throw new PeerException("TRANSFER_CANCELLED", "The download was cancelled or paused.");
            }
            moveToNonConflictingTarget(task);
            deletePartQuietly(task.resumeManifestPath);
            task.status = TransferStatus.COMPLETED;
            task.detail = "Download completed and SHA-256 verified.";
            publishStatus(task, task.detail);
            publishActivity("Download completed: " + task.targetFileName, "INFO");
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
                Path oldManifest = task.resumeManifestPath;
                Path nextManifest = ResumeManifestStore.manifestPath(next.partPath);
                boolean partMoved = false;
                try {
                    Files.move(oldPart, next.partPath);
                    partMoved = true;
                    if (oldManifest != null && Files.exists(oldManifest, LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(oldManifest, nextManifest);
                        task.resumeManifest = new ResumeManifestStore.Manifest(
                                task.resumeManifest.taskId(), task.resumeManifest.fileName(),
                                next.fileName, task.resumeManifest.totalBytes(),
                                task.resumeManifest.sha256(), task.resumeManifest.chunkBytes(),
                                task.resumeManifest.chunkHashes());
                        ResumeManifestStore.save(nextManifest, task.resumeManifest);
                    }
                } catch (FileAlreadyExistsException partCollision) {
                    if (partMoved) {
                        Files.move(next.partPath, oldPart);
                    }
                    releaseTargetPath(next.targetPath);
                    continue;
                } catch (IOException moveFailure) {
                    if (nextManifest != null && Files.exists(nextManifest, LinkOption.NOFOLLOW_LINKS)
                            && oldManifest != null && !Files.exists(oldManifest, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Files.move(nextManifest, oldManifest);
                        } catch (IOException rollbackFailure) {
                            moveFailure.addSuppressed(rollbackFailure);
                        }
                    }
                    if (partMoved && Files.exists(next.partPath, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Files.move(next.partPath, oldPart);
                        } catch (IOException rollbackFailure) {
                            moveFailure.addSuppressed(rollbackFailure);
                        }
                    }
                    releaseTargetPath(next.targetPath);
                    throw moveFailure;
                }
                synchronized (destinationLock) {
                    reservedTargets.remove(oldTarget);
                    task.targetPath = next.targetPath;
                    task.partPath = next.partPath;
                    task.resumeManifestPath = nextManifest;
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
                        || Files.exists(ResumeManifestStore.manifestPath(part), LinkOption.NOFOLLOW_LINKS)
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
            if (next == TransferStatus.TRANSFERRING) {
                publishActivity("Download started: " + task.targetFileName, "INFO");
            } else if (next == TransferStatus.COMPLETED) {
                publishActivity("Download completed: " + task.targetFileName, "INFO");
            } else if (next == TransferStatus.PAUSED) {
                publishActivity("Download paused: " + task.targetFileName, "WARN");
            }
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
            publishActivity("Download cancelled: " + task.targetFileName, "WARN");
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
            publishActivity("Download failed: " + task.targetFileName, "ERROR");
        }
    }

    private void publishActivity(String message, String level) {
        events.publish("activityLogged", Map.of(
                "id", UUID.randomUUID().toString(),
                "timestamp", java.time.Instant.now().toString(),
                "category", "TRANSFER",
                "level", level,
                "message", message));
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
        private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
        private final Object resumeLock = new Object();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final long createdAt = System.nanoTime();
        private volatile ResumeManifestStore.Manifest resumeManifest;
        private volatile Path resumeManifestPath;
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
            this(taskId, peerIds, fileName, target, multiSource, null,
                    ResumeManifestStore.manifestPath(target.partPath));
        }

        private DownloadTask(UUID taskId, List<UUID> peerIds, String fileName,
                ReservedTarget target, boolean multiSource,
                ResumeManifestStore.Manifest resumeManifest, Path resumeManifestPath) {
            this.taskId = taskId;
            this.peerId = peerIds.getFirst();
            this.sourcePeerIds = List.copyOf(peerIds);
            this.multiSource = multiSource;
            this.fileName = fileName;
            this.destination = target.targetPath.getParent();
            this.targetFileName = target.fileName;
            this.targetPath = target.targetPath;
            this.partPath = target.partPath;
            this.resumeManifest = resumeManifest;
            this.resumeManifestPath = resumeManifestPath;
        }

        private TransferSnapshot snapshot() {
            return new TransferSnapshot(taskId, peerId, fileName, targetFileName, status,
                    receivedBytes, totalBytes, detail, sourcePeerIds, totalChunks, completedChunks);
        }
    }
}
