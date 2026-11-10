package dev.katherine.consilium.cli;

import dev.katherine.consilium.ConsiliumBundle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.katherine.consilium.model.Speaker;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ClaudeCliAgent extends AbstractCliAgent {
    private final @Nullable Path userSettings;

    public ClaudeCliAgent() {
        this(null, ClaudeUserSettings.defaultFile());
    }

    /** For tests: a fake CLI and no user settings. */
    ClaudeCliAgent(@Nullable Path executable) {
        this(executable, null);
    }

    ClaudeCliAgent(@Nullable Path executable, @Nullable Path userSettings) {
        super("claude", executable);
        this.userSettings = userSettings;
    }

    @Override
    public Speaker speaker() {
        return Speaker.CLAUDE;
    }

    @Override
    List<String> arguments(Path projectRoot, @Nullable String sessionId, AgentAccess access) {
        List<String> command = new ArrayList<>();
        command.add("-p");
        command.add("--output-format");
        command.add("stream-json");
        command.add("--verbose");
        command.add("--include-partial-messages");
        if (access == AgentAccess.READ_ONLY) {
            command.add("--tools");
            command.add("Read,Glob,Grep");
            // -p skips the workspace-trust dialog, so without these a cloned repository's
            // .claude/settings.json (hooks, allow rules) and .mcp.json would load in a discussion
            // turn. --restricted ignores user, project and local settings files and confines the
            // file tools to the project; --strict-mcp-config drops every MCP server we don't pass.
            command.add("--restricted");
            command.add("--strict-mcp-config");
            // Anything that would need approval is denied instead of waiting for a host that
            // doesn't exist in print mode.
            command.add("--permission-prompts");
            command.add("none");
            String resumable = SessionIds.resumable(sessionId);
            if (resumable != null) {
                command.add("--resume");
                command.add(resumable);
            }
        } else {
            command.add("--permission-mode");
            command.add("auto");
        }
        return command;
    }

    @Override
    List<String> launchArguments(Path binary, Path projectRoot, @Nullable String sessionId, AgentAccess access,
                                 Map<String, String> environment, TurnFiles files) throws IOException {
        List<String> command = arguments(projectRoot, sessionId, access);
        if (access == AgentAccess.READ_ONLY) {
            String kept = ClaudeUserSettings.discussionSubset(userSettings);
            if (kept != null) {
                command.add("--settings");
                command.add(files.createPrivate("consilium-claude-settings-", kept).toString());
            }
        }
        return command;
    }

    @Override
    CliEventParser newParser() {
        return new EventParser();
    }

    static final class EventParser implements CliEventParser {
        private boolean emittedStreamingText;

        @Override
        public @Nullable TurnOutcome handle(JsonObject event, AgentListener listener) {
            String type = Json.string(event, "type");
            String sessionId = Json.string(event, "session_id");
            if (sessionId != null) listener.onSessionId(sessionId);

            if ("stream_event".equals(type)) {
                handleStreamEvent(Json.object(event, "event"), listener);
                return null;
            }
            if (!"result".equals(type)) return null;

            // `result` is Claude's last word on the turn; with is_error it carries the reason
            // (API errors, max turns), which must not be shown as the answer.
            if (Json.isTrue(event, "is_error")) return TurnOutcome.failure(errorDetail(event));
            if (!emittedStreamingText) {
                String result = Json.string(event, "result");
                if (result != null && !result.isBlank()) listener.onTextDelta(result);
            }
            return TurnOutcome.SUCCESS;
        }

        @Override
        public @Nullable String reportedError() {
            return null;
        }

        private void handleStreamEvent(@Nullable JsonObject streamEvent, AgentListener listener) {
            String type = Json.string(streamEvent, "type");
            // Text before and after a tool call arrives in separate blocks; without a separator
            // they run together into one line.
            if ("content_block_start".equals(type) && emittedStreamingText
                    && "text".equals(Json.string(Json.object(streamEvent, "content_block"), "type"))) {
                listener.onTextDelta("\n\n");
                return;
            }
            if ("content_block_delta".equals(type)) {
                String text = Json.string(Json.object(streamEvent, "delta"), "text");
                if (text != null && !text.isEmpty()) {
                    emittedStreamingText = true;
                    listener.onTextDelta(text);
                }
            }
        }

        private static String errorDetail(JsonObject result) {
            String text = Json.string(result, "result");
            if (text != null && !text.isBlank()) return text;
            JsonElement errors = result.get("errors");
            if (errors != null && errors.isJsonArray()) {
                List<String> messages = new ArrayList<>();
                for (JsonElement error : errors.getAsJsonArray()) {
                    if (error.isJsonPrimitive()) messages.add(error.getAsString());
                }
                if (!messages.isEmpty()) return String.join("\n", messages);
            }
            String subtype = Json.string(result, "subtype");
            return ConsiliumBundle.message("error.reported", Speaker.CLAUDE.label(), subtype != null ? subtype : "result");
        }
    }
}
