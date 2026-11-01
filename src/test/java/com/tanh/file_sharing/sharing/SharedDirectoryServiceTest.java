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
