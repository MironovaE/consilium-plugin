package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import dev.katherine.consilium.model.Speaker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CodexCliAgent extends AbstractCliAgent {
    @Override
    public Speaker speaker() {
        return Speaker.CODEX;
    }

    @Override
    protected List<String> command(Path projectRoot, String sessionId, AgentAccess access) {
        List<String> command = new ArrayList<>();
        command.add(ExecutableResolver.resolve("codex"));
        command.add("exec");
        if (access == AgentAccess.READ_ONLY && sessionId != null && !sessionId.isBlank()) {
            command.add("resume");
            command.add("--json");
            command.add(sessionId);
            command.add("-");
        } else {
            command.add("--json");
            command.add("--sandbox");
            command.add(access == AgentAccess.WRITE ? "workspace-write" : "read-only");
            command.add("--cd");
            command.add(projectRoot.toString());
            command.add("-");
        }
        return command;
    }

    @Override
    protected void handleEvent(JsonObject event, AgentListener listener) {
        String type = string(event, "type");
        if ("thread.started".equals(type)) {
            listener.onSessionId(string(event, "thread_id"));
            return;
        }
        if (!"item.completed".equals(type) || !event.has("item") || !event.get("item").isJsonObject()) {
            return;
        }
        JsonObject item = event.getAsJsonObject("item");
        if ("agent_message".equals(string(item, "type"))) {
            String text = string(item, "text");
            if (text != null && !text.isBlank()) listener.onTextDelta(text);
        }
    }

    private static String string(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
    }
}
