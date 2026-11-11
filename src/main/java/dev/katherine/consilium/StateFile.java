package dev.katherine.consilium;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The room's state in a file of its own under the IDE's system directory, one per project. Not in
 * the project's workspace.xml: that file lies in the project tree, so a cloned repository could
 * ship one with "user" messages the agents would obey, and whatever a person pasted would sit in
 * plain text next to the code.
 *
 * <p>Saves are written behind, on the {@code writer} executor, so the UI thread doesn't wait for
 * the disk on every message; only the newest state is written, and {@link #flush()} writes it at
 * once. Each write replaces the file atomically, so a crash leaves the previous state rather than
 * half of one, and the file and its folder are readable by the owner only where the file system
 * supports POSIX permissions.
 */
final class StateFile implements ConsiliumStateStore.Persistence {
    private static final Logger LOG = Logger.getInstance(StateFile.class);
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FOLDER = PosixFilePermissions.fromString("rwx------");

    private final Path file;
    private final Executor writer;
    private final Object writeLock = new Object();
    // The newest state not yet on disk; null when the file is up to date.
    private final AtomicReference<String> pending = new AtomicReference<>();

    StateFile(Path file, Executor writer) {
        this.file = file;
        this.writer = writer;
    }

    /**
     * Keyed by the project's location hash, which the IDE derives from its name and path: a project
     * moved to another folder starts with an empty room.
     */
    static Path locationOf(Project project) {
        return PathManager.getSystemDir().resolve("consilium").resolve(project.getLocationHash() + ".json");
    }

    @Override
    public String load() {
        synchronized (writeLock) {
            String unwritten = pending.get();
            if (unwritten != null) return unwritten;
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException absent) {
                return null;
            } catch (IOException exception) {
                LOG.warn("Couldn't read " + file, exception);
                return null;
            }
        }
    }

    @Override
    public void save(String value) {
        if (pending.getAndSet(value) == null) writer.execute(this::flush);
    }

    @Override
    public void flush() {
        synchronized (writeLock) {
            String value = pending.getAndSet(null);
            if (value != null) write(value);
        }
    }

    @Override
    public void erase() {
        synchronized (writeLock) {
            pending.set(null);
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                LOG.warn("Couldn't delete " + file, exception);
            }
        }
    }

    private void write(String value) {
        Path folder = file.getParent();
        boolean posix = folder.getFileSystem().supportedFileAttributeViews().contains("posix");
        Path temporary = null;
        try {
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder);
                if (posix) Files.setPosixFilePermissions(folder, OWNER_ONLY_FOLDER);
            }
            FileAttribute<?>[] ownerOnly = posix
                    ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
                    : new FileAttribute<?>[0];
            temporary = Files.createTempFile(folder, file.getFileName().toString(), ".tmp", ownerOnly);
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException exception) {
            LOG.warn("Couldn't write " + file, exception);
        } finally {
            if (temporary != null) deleteQuietly(temporary);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOG.warn("Couldn't delete " + path, exception);
        }
    }
}
