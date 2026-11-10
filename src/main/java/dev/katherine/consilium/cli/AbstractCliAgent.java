package dev.katherine.consilium.cli;

import dev.katherine.consilium.ConsiliumBundle;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

abstract class AbstractCliAgent implements CliAgent {
    private final String executableName;
    private final @Nullable Path executable;

    /**
     * @param executable a fixed executable instead of a PATH lookup; tests point it at a fake CLI
     */
    AbstractCliAgent(String executableName, @Nullable Path executable) {
        this.executableName = executableName;
        this.executable = executable;
    }

    @Override
    public final RunningRequest start(Path projectRoot, String prompt, String sessionId, AgentAccess access, AgentListener listener) {
        CliRun run = new CliRun(speaker().label(), listener, newParser());
        TurnFiles files = new TurnFiles();
        run.terminated().whenComplete((ignored, error) -> files.deleteAll());
        AppExecutorUtil.getAppExecutorService().execute(() -> run.run(() -> launch(projectRoot, sessionId, access, files, run::isCancelled), prompt));
        return run;
    }

    /** The CLI arguments after the executable. An unusable {@code sessionId} means a fresh session. */
    abstract List<String> arguments(Path projectRoot, @Nullable String sessionId, AgentAccess access);

    abstract CliEventParser newParser();

    /**
     * The arguments for this launch. Runs on the agent's background thread, so an agent may consult
     * the CLI first (Codex lists its MCP servers); throwing refuses the turn.
     */
    List<String> launchArguments(Path binary, Path projectRoot, @Nullable String sessionId, AgentAccess access,
                                 Map<String, String> environment, TurnFiles files, BooleanSupplier cancelled) throws IOException {
        return arguments(projectRoot, sessionId, access);
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
    private Process launch(Path projectRoot, @Nullable String sessionId, AgentAccess access, TurnFiles files,
                           BooleanSupplier cancelled) throws Exception {
        Map<String, String> environment = EnvironmentUtil.getEnvironmentMap();
        Path binary = executable != null ? executable : ExecutableResolver.resolve(executableName, projectRoot, environment);
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(launchArguments(binary, projectRoot, sessionId, access, environment, files, cancelled));
        return new GeneralCommandLine(command)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withWorkingDirectory(projectRoot)
                .withRedirectErrorStream(true)
                .withCharset(StandardCharsets.UTF_8)
                .createProcess();
    }

    /**
     * CLI flags are an unstable surface: after an upgrade the agent fails right away while parsing
     * arguments. Point at where to look instead of showing bare stderr.
     */
    static String flagHint(String cliOutput) {
        String lower = cliOutput.toLowerCase(Locale.ROOT);
        boolean looksLikeFlagError = lower.contains("unknown option")
                || lower.contains("unexpected argument")
                || lower.contains("unrecognized option")
                || lower.contains("unrecognized argument");
        return looksLikeFlagError
                ? ConsiliumBundle.message("error.flag.hint")
                : "";
    }
}
