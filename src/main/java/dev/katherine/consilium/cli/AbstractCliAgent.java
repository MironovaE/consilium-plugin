package dev.katherine.consilium.cli;

import dev.katherine.consilium.ConsiliumBundle;

import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
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

    private Process launch(Path projectRoot, @Nullable String sessionId, AgentAccess access, TurnFiles files,
                           BooleanSupplier cancelled) throws Exception {
        Map<String, String> environment = CliLauncher.environment();
        Path binary = CliLauncher.binary(executableName, executable, projectRoot, environment);
        return CliLauncher.start(binary, launchArguments(binary, projectRoot, sessionId, access, environment, files, cancelled), projectRoot);
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
