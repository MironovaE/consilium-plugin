package dev.katherine.consilium.work;

import dev.katherine.consilium.ConsiliumBundle;
import dev.katherine.consilium.cli.ExecutableResolver;
import dev.katherine.consilium.process.BoundedProcess;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.EnvironmentUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the {@code git} CLI without a shell. The process gets the login-shell environment the IDE
 * loaded ({@code CONSOLE}), like the agents do, so the user's git config, credentials helpers and
 * hooks behave as in their terminal; on top of it:
 * <ul>
 *   <li>{@code LC_ALL=C}: output and error text are parsed, and translations would break that;</li>
 *   <li>{@code GIT_TERMINAL_PROMPT=0}: there is no terminal, so a prompt would hang until the timeout.</li>
 * </ul>
 * Every call blocks; never call it on the UI thread.
 */
public final class Git {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 16 * 1024;

    private final Path executable;
    private final Duration timeout;

    public Git(Path executable, Duration timeout) {
        this.executable = executable;
        this.timeout = timeout;
    }

    /** The git binary from the login-shell PATH, as an absolute path. */
    public static Git fromLoginShell(Duration timeout) throws IOException {
        return new Git(ExecutableResolver.resolveOnPath("git", EnvironmentUtil.getEnvironmentMap()), timeout);
    }

    /** What a finished git process printed; stdout keeps its trailing newline, stderr is stripped. */
    public record Result(int exitCode, String output, String error) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    /** Runs git and returns its result whatever the exit code; throws only if it couldn't run or finish. */
    public Result run(Path directory, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        Process process;
        try {
            process = new GeneralCommandLine(command)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .withEnvironment("LC_ALL", "C")
                    .withEnvironment("GIT_TERMINAL_PROMPT", "0")
                    .withWorkingDirectory(directory)
                    .withCharset(StandardCharsets.UTF_8)
                    .createProcess();
        } catch (ExecutionException exception) {
            throw new IOException(ConsiliumBundle.message("error.git.failed", describe(arguments), exception.getMessage()), exception);
        }
        try {
            BoundedProcess.Result result = BoundedProcess.await(process, timeout, MAX_OUTPUT_BYTES, MAX_ERROR_BYTES, () -> false);
            return new Result(result.exitCode(), new String(result.output(), StandardCharsets.UTF_8),
                    new String(result.error(), StandardCharsets.UTF_8).strip());
        } catch (BoundedProcess.Failure failure) {
            String message = failure.kind() == BoundedProcess.Failure.Kind.TIMED_OUT
                    ? ConsiliumBundle.message("error.git.timeout", describe(arguments), timeout.toSeconds())
                    : ConsiliumBundle.message("error.git.failed", describe(arguments), failure.getMessage());
            throw new IOException(message, failure);
        }
    }

    /** Runs git and returns its stdout without the trailing newline; a non-zero exit throws with git's stderr. */
    public String output(Path directory, String... arguments) throws IOException {
        Result result = run(directory, arguments);
        if (!result.succeeded()) throw failure(result, arguments);
        return result.output().stripTrailing();
    }

    static IOException failure(Result result, String... arguments) {
        String details = result.error().isEmpty() ? "exit code " + result.exitCode() : result.error();
        return new IOException(ConsiliumBundle.message("error.git.failed", describe(arguments), details));
    }

    private static String describe(String... arguments) {
        return "git " + String.join(" ", arguments);
    }
}
