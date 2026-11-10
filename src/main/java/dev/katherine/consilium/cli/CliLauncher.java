package dev.katherine.consilium.cli;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.EnvironmentUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Starts a CLI process the same way for chat turns and for one-shot calls. */
final class CliLauncher {
    private CliLauncher() {
    }

    /** The environment a CLI runs with and its PATH is searched in. */
    static Map<String, String> environment() {
        return EnvironmentUtil.getEnvironmentMap();
    }

    /** {@code fixed} when given (tests point it at a fake CLI), otherwise the safe PATH lookup. */
    static Path binary(String name, @Nullable Path fixed, Path projectRoot, Map<String, String> environment) throws IOException {
        return fixed != null ? fixed : ExecutableResolver.resolve(name, projectRoot, environment);
    }

    /**
     * Starts the CLI through {@link GeneralCommandLine} with the {@code CONSOLE} parent environment,
     * which is the login-shell environment the IDE loads at startup rather than the bare one an IDE
     * launched from the Dock inherits; the executable is looked up in that same PATH.
     *
     * <p>The child keeps the whole environment on purpose. The CLIs need far more than their API
     * keys: OAuth and keychain lookups via HOME, proxies and custom CA bundles, Bedrock/Vertex
     * credentials, and the user's toolchain for the commands they run. No list of "unrelated
     * secrets" is safe to strip, and doing so would buy nothing: the CLI is the user's own program,
     * running as the user, and can read the same secrets from disk anyway.
     */
    static Process start(Path binary, List<String> arguments, Path workingDirectory) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(arguments);
        return new GeneralCommandLine(command)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withWorkingDirectory(workingDirectory)
                .withRedirectErrorStream(true)
                .withCharset(StandardCharsets.UTF_8)
                .createProcess();
    }
}
