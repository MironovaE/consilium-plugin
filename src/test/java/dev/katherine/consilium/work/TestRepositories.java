package dev.katherine.consilium.work;

import dev.katherine.consilium.cli.ExecutableResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real git repositories in temp directories; tests that need one are skipped where git is missing. */
public final class TestRepositories {
    private TestRepositories() {
    }

    public static Git gitOrSkip() {
        try {
            return new Git(ExecutableResolver.resolveOnPath("git", System.getenv()), Duration.ofSeconds(60));
        } catch (IOException exception) {
            assumeTrue(false, "git is not installed");
            throw new AssertionError(exception);
        }
    }

    /**
     * A repository on branch main, isolated from the user's git config: commit signing or a global
     * hooks path would otherwise make a test commit fail or run foreign code.
     */
    public static Path init(Path directory, boolean withCommit) throws IOException {
        git(directory.getParent(), "init", "-q", "-b", "main", directory.toString());
        git(directory, "config", "user.name", "Test");
        git(directory, "config", "user.email", "test@example.com");
        git(directory, "config", "commit.gpgsign", "false");
        git(directory, "config", "core.hooksPath", "/dev/null");
        if (withCommit) git(directory, "commit", "-q", "--allow-empty", "-m", "init");
        return directory.toRealPath();
    }

    /** Runs git through a plain ProcessBuilder, so setup doesn't depend on the code under test. */
    public static String git(Path directory, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException(String.join(" ", command) + " failed: " + output);
            }
            return output.strip();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }
}
