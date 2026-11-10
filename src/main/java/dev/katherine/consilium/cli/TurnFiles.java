package dev.katherine.consilium.cli;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Files a CLI reads during one turn, deleted when the turn's processes are gone. Content goes
 * through a file rather than argv because argv is visible to every local user in {@code ps}.
 */
final class TurnFiles {
    private static final Logger LOG = Logger.getInstance(TurnFiles.class);
    private final List<Path> files = new CopyOnWriteArrayList<>();

    /** A new file readable only by the current user. */
    Path createPrivate(String prefix, String content) throws IOException {
        Path file = Files.createTempFile(prefix, ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        files.add(file);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    void deleteAll() {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                LOG.warn("Couldn't delete " + file, exception);
            }
        }
        files.clear();
    }
}
