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

    abstract CliEventParser newParser();

    /**
     * The CLI arguments after the executable for one launch. Runs on the agent's background thread,
     * so an agent may consult the CLI first (Codex lists its MCP servers) or write turn files;
     * throwing refuses the turn. Each agent builds the list with a pure {@code arguments(...)}
     * method that {@code CliCommandTest} pins.
     */
    abstract List<String> launchArguments(Launch launch) throws IOException;

    /** Everything an agent may need to choose its arguments. An unusable session id means a fresh session. */
    record Launch(Path binary, Path projectRoot, @Nullable String sessionId, AgentAccess access,
                  Map<String, String> environment, TurnFiles files, BooleanSupplier cancelled) {
    }

    private Process launch(Path projectRoot, @Nullable String sessionId, AgentAccess access, TurnFiles files,
                           BooleanSupplier cancelled) throws Exception {
        Map<String, String> environment = CliLauncher.environment();
        Path binary = CliLauncher.binary(executableName, executable, projectRoot, environment);
        List<String> arguments = launchArguments(new Launch(binary, projectRoot, sessionId, access, environment, files, cancelled));
        return CliLauncher.start(binary, arguments, projectRoot);
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
