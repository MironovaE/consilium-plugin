package dev.katherine.consilium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class StateFileTest {
    @TempDir
    Path folder;

    @Test
    void savesAreWrittenBehindAndOnlyTheNewestReachesTheDisk() throws Exception {
        Queue<Runnable> writer = new ArrayDeque<>();
        Path path = folder.resolve("consilium").resolve("project.json");
        StateFile file = new StateFile(path, writer::add);

        file.save("first");
        file.save("second");

        assertFalse(Files.exists(path), "nothing written on the caller's thread");
        assertEquals(1, writer.size(), "one write for both saves");
        assertEquals("second", file.load(), "a pending save is what loads");
        writer.poll().run();
        assertEquals("second", Files.readString(path, StandardCharsets.UTF_8));
    }

    @Test
    void flushWritesAtOnceAndOnlyTheOwnerCanRead() throws Exception {
        Path path = folder.resolve("consilium").resolve("project.json");
        StateFile file = new StateFile(path, ignored -> {
        });

        file.save("{\"messages\":[]}");
        file.flush();

        assertEquals("{\"messages\":[]}", new StateFile(path, Runnable::run).load());
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path.getParent())));
        }
        try (var left = Files.list(path.getParent())) {
            assertEquals(1, left.count(), "no temporary file left behind");
        }
    }

    @Test
    void aMissingFileLoadsAsNothing() {
        assertNull(new StateFile(folder.resolve("absent.json"), Runnable::run).load());
    }
}
