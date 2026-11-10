package dev.katherine.consilium.cli;

import dev.katherine.consilium.ConsiliumBundle;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds a CLI as an absolute path, so the process never depends on how the OS would look up a bare
 * name. The search uses the PATH of the environment the IDE loaded from the login shell
 * ({@link com.intellij.util.EnvironmentUtil#getEnvironmentMap()}): an IDE started from the Dock
 * gets launchd's minimal PATH, which lacks /opt/homebrew/bin and the npm global bin.
 */
final class ExecutableResolver {
    private static final Logger LOG = Logger.getInstance(ExecutableResolver.class);

    private ExecutableResolver() {
    }

    /**
     * Looks in {@code ~/.local/bin} first (where both CLIs' own installers put them), then in each
     * PATH directory. Relative PATH entries ({@code .}, an empty entry, {@code bin}) are skipped and
     * so is any match inside the project: either would let a cloned repository ship its own
     * "claude" and have it run with the user's credentials.
     */
    static Path resolve(String name, Path projectRoot, Map<String, String> environment) throws IOException {
        Path project = realOrAbsolute(projectRoot);
        List<Path> directories = new ArrayList<>();
        directories.add(Path.of(System.getProperty("user.home"), ".local", "bin"));
        String pathValue = environment.get("PATH");
        if (pathValue != null) {
            for (String entry : PathEnvironmentVariableUtil.getPathDirs(pathValue)) {
                Path directory = toPath(entry);
                if (directory == null || !directory.isAbsolute()) {
                    LOG.info("Skipping relative PATH entry '" + entry + "' while looking for " + name);
                    continue;
                }
                directories.add(directory);
            }
        }

        Path refused = null;
        for (Path directory : directories) {
            for (String fileName : fileNames(name)) {
                Path candidate = directory.resolve(fileName).normalize();
                if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) continue;
                if (candidate.startsWith(project) || realOrAbsolute(candidate).startsWith(project)) {
                    LOG.warn("Refusing to run " + candidate + ": it is inside the project " + project);
                    if (refused == null) refused = candidate;
                    continue;
                }
                return candidate;
            }
        }
        throw new IOException(refused != null
                ? ConsiliumBundle.message("error.executable.in.project", name, refused)
                : ConsiliumBundle.message("error.executable.missing", name));
    }

    private static List<String> fileNames(String name) {
        if (!SystemInfo.isWindows) return List.of(name);
        List<String> names = new ArrayList<>();
        for (String extension : PathEnvironmentVariableUtil.getWindowsExecutableFileExtensions()) {
            names.add(name + extension);
        }
        return names;
    }

    private static Path toPath(String entry) {
        if (entry.isBlank()) return null;
        try {
            return Path.of(entry);
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static Path realOrAbsolute(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path.toAbsolutePath().normalize();
        }
    }
}
