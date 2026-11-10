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
public final class ExecutableResolver {
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
        directories.addAll(absolutePathDirectories(name, environment));

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

    /**
     * Finds a system tool such as git in the absolute PATH directories only. Unlike {@link #resolve}
     * there is no project to check against: a repository is an argument of each git call, not where
     * the binary is looked up, and relative PATH entries are still skipped so the working directory
     * never decides which binary runs.
     */
    public static Path resolveOnPath(String name, Map<String, String> environment) throws IOException {
        for (Path directory : absolutePathDirectories(name, environment)) {
            for (String fileName : fileNames(name)) {
                Path candidate = directory.resolve(fileName).normalize();
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
            }
        }
        throw new IOException(ConsiliumBundle.message("error.executable.missing.path", name));
    }

    private static List<Path> absolutePathDirectories(String name, Map<String, String> environment) {
        List<Path> directories = new ArrayList<>();
        String pathValue = environment.get("PATH");
        if (pathValue == null) return directories;
        for (String entry : PathEnvironmentVariableUtil.getPathDirs(pathValue)) {
            Path directory = toPath(entry);
            if (directory == null || !directory.isAbsolute()) {
                LOG.info("Skipping relative PATH entry '" + entry + "' while looking for " + name);
                continue;
            }
            directories.add(directory);
        }
        return directories;
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
