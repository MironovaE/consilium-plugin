package dev.katherine.consilium.cli;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Files a CLI reads during one turn, deleted when the turn's processes are gone. Content goes
 * through a file rather than argv because argv is visible to every local user in {@code ps}.
 *
 * <p>The files live in Consilium's own folder under the IDE's system directory, not in
 * {@code $TMPDIR}: a Codex workspace-write turn may write anywhere under {@code $TMPDIR}, so a
 * concurrent Codex turn could replace a settings file between its creation and Claude reading it.
 * The folder is owner-only (700) and each file 600, which keeps other local users out; the location
 * is what keeps the sandboxed agent out, since it runs as the same user.
 */
final class TurnFiles {
    private static final Logger LOG = Logger.getInstance(TurnFiles.class);
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static Path directory; // guarded by TurnFiles.class

    private final List<Path> files = new CopyOnWriteArrayList<>();

    /** A new file readable only by the current user. */
    Path createPrivate(String prefix, String content) throws IOException {
        // On a non-POSIX file system (Windows) the folder is already inside the user's profile.
        Path file = POSIX
                ? Files.createTempFile(directory(), prefix, ".json", PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE))
                : Files.createTempFile(directory(), prefix, ".json");
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

    /**
     * The private folder, checked on every call: it outlives the IDE session, so its mode could have
     * been changed or the path replaced in between. A symlink or someone else's folder is refused
     * and the turn reports why; a loosened mode is tightened back.
     */
    static synchronized Path directory() throws IOException {
        if (directory == null) {
            // Unit tests run without an IntelliJ application, where the IDE's system directory isn't
            // set up; a fresh private temp folder stands in. No agent runs in those tests.
            directory = ApplicationManager.getApplication() != null
                    ? PathManager.getSystemDir().resolve("consilium-tmp")
                    : Files.createTempDirectory("consilium-tmp-", ownerOnlyDirectory());
        }
        Files.createDirectories(directory, ownerOnlyDirectory());
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(directory + " is not a plain folder");
        }
        if (POSIX) {
            String owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).getName();
            if (!owner.equals(System.getProperty("user.name"))) {
                throw new IOException(directory + " belongs to " + owner);
            }
            if (!Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).equals(OWNER_ONLY_DIRECTORY)) {
                Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
            }
        }
        return directory;
    }

    private static FileAttribute<?>[] ownerOnlyDirectory() {
        return POSIX
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY)}
                : new FileAttribute<?>[0];
    }
}
