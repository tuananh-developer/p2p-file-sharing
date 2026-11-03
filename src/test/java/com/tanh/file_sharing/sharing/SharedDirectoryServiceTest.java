package com.tanh.file_sharing.sharing;

import com.tanh.file_sharing.model.SharedFileSnapshot;
import com.tanh.file_sharing.peer.PeerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedDirectoryServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void listsOnlySafeTopLevelRegularFilesAndDoesNotFollowSymbolicLinks() throws Exception {
        Path shared = Files.createDirectory(temporaryDirectory.resolve("shared"));
        Path nested = Files.createDirectory(shared.resolve("nested"));
        Files.writeString(shared.resolve("report.txt"), "hello");
        Files.writeString(shared.resolve("unsafe..txt"), "must not be published");
        Files.writeString(nested.resolve("nested.txt"), "not shared recursively");

        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "outside");
        boolean symbolicLinkCreated = true;
        try {
            Files.createSymbolicLink(shared.resolve("linked.txt"), outside);
        } catch (UnsupportedOperationException | IOException exception) {
            symbolicLinkCreated = false;
        }

        SharedDirectoryService service = new SharedDirectoryService();
        var selected = service.setDirectory(shared.toString());
        List<SharedFileSnapshot> files = service.listFiles();

        assertTrue(selected.configured());
        assertEquals(shared.toRealPath().toString(), selected.path());
        assertEquals(1, files.size());
        assertEquals("report.txt", files.get(0).fileName());
        assertEquals(5L, files.get(0).size());
        assertFalse(files.stream().anyMatch(file -> file.fileName().equals("nested.txt")));
        assertFalse(files.stream().anyMatch(file -> file.fileName().equals("unsafe..txt")));
        if (symbolicLinkCreated) {
            assertFalse(files.stream().anyMatch(file -> file.fileName().equals("linked.txt")));
        }
    }

    @Test
    void rejectsInvalidSelectionAndKeepsPreviousValidDirectory() throws Exception {
        Path shared = Files.createDirectory(temporaryDirectory.resolve("shared"));
        Path regularFile = Files.writeString(temporaryDirectory.resolve("not-a-directory.txt"), "file");
        SharedDirectoryService service = new SharedDirectoryService();
        service.setDirectory(shared.toString());

        PeerException missing = assertThrows(PeerException.class,
                () -> service.setDirectory(temporaryDirectory.resolve("missing").toString()));
        assertEquals("INVALID_DIRECTORY", missing.getCode());

        PeerException notDirectory = assertThrows(PeerException.class,
                () -> service.setDirectory(regularFile.toString()));
        assertEquals("INVALID_DIRECTORY", notDirectory.getCode());

        assertEquals(shared.toRealPath().toString(), service.snapshot().path());
    }

    @Test
    void opensOnlyAValidatedDirectChildAndProvidesSha256Metadata() throws Exception {
        Path shared = Files.createDirectory(temporaryDirectory.resolve("source-open"));
        byte[] content = "verified-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(shared.resolve("verified.txt"), content);
        SharedDirectoryService service = new SharedDirectoryService();
        service.setDirectory(shared.toString());

        try (SharedFileSource source = service.openFile("verified.txt")) {
            assertEquals("verified.txt", source.fileName());
            assertEquals(content.length, source.size());
            assertEquals("fbcca5abece3437d2a17bda55c13f39e0fdc7039596282d0ebf5ec277577b3bb", source.sha256());
        }

        PeerException traversal = assertThrows(PeerException.class,
                () -> service.openFile("../outside.txt"));
        assertEquals("FILE_UNAVAILABLE", traversal.getCode());
    }

    @Test
    void opensValidatedRangesWithoutRehashingAndRejectsChangedFileSize() throws Exception {
        Path shared = Files.createDirectory(temporaryDirectory.resolve("source-range"));
        byte[] content = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(shared.resolve("range.txt"), content);
        SharedDirectoryService service = new SharedDirectoryService();
        service.setDirectory(shared.toString());

        try (SharedFileSource source = service.openFileRange("range.txt", content.length)) {
            assertEquals(content.length, source.size());
            source.position(5);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4);
            assertEquals(4, source.read(buffer));
            assertArrayEquals("5678".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.util.Arrays.copyOf(buffer.array(), buffer.position()));
        }

        PeerException changed = assertThrows(PeerException.class,
                () -> service.openFileRange("range.txt", content.length + 1));
        assertEquals("FILE_CHANGED", changed.getCode());

        PeerException traversal = assertThrows(PeerException.class,
                () -> service.openFileRange("../outside.txt", content.length));
        assertEquals("FILE_UNAVAILABLE", traversal.getCode());
    }

    @Test
    void requiresAConfiguredDirectoryBeforeListing() {
        SharedDirectoryService service = new SharedDirectoryService();

        PeerException exception = assertThrows(PeerException.class, service::listFiles);

        assertEquals("NOT_SHARING", exception.getCode());
        assertFalse(service.snapshot().configured());
        assertNull(service.snapshot().path());
    }

    @Test
    void rejectsUnsafeFileNames() {
        assertTrue(SharedDirectoryService.isSafeFileName("report-final.txt"));
        assertFalse(SharedDirectoryService.isSafeFileName("../secret.txt"));
        assertFalse(SharedDirectoryService.isSafeFileName("folder\\secret.txt"));
        assertFalse(SharedDirectoryService.isSafeFileName("report..txt"));
        assertFalse(SharedDirectoryService.isSafeFileName(""));
    }
}
