package dev.katherine.consilium.cli;

import dev.katherine.consilium.ConsiliumBundle;

import com.google.gson.JsonObject;
import dev.katherine.consilium.model.Speaker;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class CodexCliAgent extends AbstractCliAgent {
    private final Duration mcpListTimeout;

    public CodexCliAgent() {
        this(null, CodexMcpServers.LIST_TIMEOUT);
    }

    CodexCliAgent(@Nullable Path executable, Duration mcpListTimeout) {
        super("codex", executable);
        this.mcpListTimeout = mcpListTimeout;
    }

    @Override
    public Speaker speaker() {
        return Speaker.CODEX;
    }

    // MCP servers from the user's (and the project's) Codex config start for every turn and run
    // outside the sandbox, so a read-only turn switches each of them off; see CodexMcpServers.
    @Override
    List<String> launchArguments(Path binary, Path projectRoot, @Nullable String sessionId, AgentAccess access,
                                 Map<String, String> environment, TurnFiles files, BooleanSupplier cancelled) throws IOException {
        if (access.writes()) return arguments(projectRoot, sessionId, access);
        List<String> servers = CodexMcpServers.enabled(binary, projectRoot, environment, mcpListTimeout, cancelled);
        return arguments(projectRoot, sessionId, access, servers);
    }

    @Override
    List<String> arguments(Path projectRoot, @Nullable String sessionId, AgentAccess access) {
        return arguments(projectRoot, sessionId, access, List.of());
    }

    /**
     * {@code mcpServersToDisable} only applies to read-only turns.
     *
     * <p>A write turn in a linked worktree gets no access to the repository's {@code .git}: that
     * would let the sandboxed agent write hooks or {@code core.fsmonitor} into {@code .git/config}
     * and run code outside the sandbox at the next git command. So Codex can't commit there, and
     * Consilium commits a parallel part itself (see ParallelWork).
     */
    List<String> arguments(Path projectRoot, @Nullable String sessionId, AgentAccess access, List<String> mcpServersToDisable) {
        List<String> command = new ArrayList<>();
        command.add("exec");
        String resumable = !access.writes() ? SessionIds.resumable(sessionId) : null;
        if (resumable != null) {
            // `exec resume` has no --sandbox flag: without -c it takes the sandbox from the user's
            // config (often workspace-write), and a discussion turn would write to the repository.
            command.add("resume");
            command.add("--json");
            command.add("-c");
            command.add("sandbox_mode=\"read-only\"");
            addNoEscalation(command);
            command.addAll(CodexMcpServers.disableArguments(mcpServersToDisable));
            // `--` keeps the session id a positional even if validation ever lets a dash through.
            command.add("--");
            command.add(resumable);
            command.add("-");
        } else {
            command.add("--json");
            command.add("--sandbox");
            command.add(access.writes() ? "workspace-write" : "read-only");
            if (!access.writes()) {
                addNoEscalation(command);
                command.addAll(CodexMcpServers.disableArguments(mcpServersToDisable));
            }
            command.add("--cd");
            command.add(projectRoot.toString());
            command.add("--");
            command.add("-");
        }
        return command;
    }

    // Without this a denied write is retried with an escalation request, and an auto-approving
    // reviewer from the user's config lets it through: the read-only turn still changes files
    // (reproduced on codex-cli 0.167.0).
    private static void addNoEscalation(List<String> command) {
        command.add("-c");
        command.add("approval_policy=\"never\"");
    }

    @Override
    CliEventParser newParser() {
        return new EventParser();
    }

    static final class EventParser implements CliEventParser {
        // Codex sends several agent_message items per turn: a "let me look…" note and the answer.
        // Without a separator they run together into one line.
        private boolean emittedMessage;
        private volatile @Nullable String lastError;

        @Override
        public @Nullable TurnOutcome handle(JsonObject event, AgentListener listener) {
            String type = Json.string(event, "type");
            if (type == null) return null;
            switch (type) {
                case "thread.started" -> {
                    String threadId = Json.string(event, "thread_id");
                    if (threadId != null) listener.onSessionId(threadId);
                }
                case "item.completed" -> handleItem(Json.object(event, "item"), listener);
                // A top-level `error` isn't final: Codex also reports reconnect attempts this way.
                // Keep it for the case where the process then dies without `turn.failed`.
                case "error" -> {
                    String message = Json.string(event, "message");
                    if (message != null && !message.isBlank()) lastError = message;
                }
                case "turn.completed" -> {
                    return TurnOutcome.SUCCESS;
                }
                case "turn.failed" -> {
                    String message = Json.string(Json.object(event, "error"), "message");
                    if (message == null || message.isBlank()) message = lastError;
                    return TurnOutcome.failure(message != null
                            ? message
                            : ConsiliumBundle.message("error.reported", Speaker.CODEX.label(), type));
                }
                default -> {
                }
            }
            return null;
        }

        @Override
        public @Nullable String reportedError() {
            return lastError;
        }

        private void handleItem(@Nullable JsonObject item, AgentListener listener) {
            if (!"agent_message".equals(Json.string(item, "type"))) return;
            String text = Json.string(item, "text");
            if (text == null || text.isBlank()) return;
            listener.onTextDelta(emittedMessage ? "\n\n" + text : text);
            emittedMessage = true;
        }
    }
}
