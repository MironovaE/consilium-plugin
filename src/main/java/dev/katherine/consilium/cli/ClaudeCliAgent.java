package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import dev.katherine.consilium.model.Speaker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ClaudeCliAgent extends AbstractCliAgent {
    private boolean emittedStreamingText;

    @Override
    public Speaker speaker() {
        return Speaker.CLAUDE;
    }

    @Override
    protected List<String> command(Path projectRoot, String sessionId, AgentAccess access) {
        emittedStreamingText = false;
        List<String> command = new ArrayList<>();
        command.add(ExecutableResolver.resolve("claude"));
        command.add("-p");
        command.add("--output-format");
        command.add("stream-json");
        command.add("--verbose");
        command.add("--include-partial-messages");
        if (access == AgentAccess.READ_ONLY) {
            command.add("--tools");
            command.add("Read,Glob,Grep");
        } else {
            command.add("--permission-mode");
            command.add("auto");
        }
        if (access == AgentAccess.READ_ONLY && sessionId != null && !sessionId.isBlank()) {
            command.add("--resume");
            command.add(sessionId);
        }
        return command;
    }

    @Override
    protected void handleEvent(JsonObject event, AgentListener listener) {
        String type = string(event, "type");
        String sessionId = string(event, "session_id");
        if (sessionId != null) listener.onSessionId(sessionId);

        if ("stream_event".equals(type) && event.has("event") && event.get("event").isJsonObject()) {
            JsonObject streamEvent = event.getAsJsonObject("event");
            // Текст до вызова инструмента и после — разные блоки; без разделителя
            // они слипаются в одну строку, как у Codex.
            if ("content_block_start".equals(string(streamEvent, "type")) && emittedStreamingText
                    && streamEvent.has("content_block") && streamEvent.get("content_block").isJsonObject()
                    && "text".equals(string(streamEvent.getAsJsonObject("content_block"), "type"))) {
                listener.onTextDelta("\n\n");
                return;
            }
            if ("content_block_delta".equals(string(streamEvent, "type"))
                    && streamEvent.has("delta") && streamEvent.get("delta").isJsonObject()) {
                String text = string(streamEvent.getAsJsonObject("delta"), "text");
                if (text != null && !text.isEmpty()) {
                    emittedStreamingText = true;
                    listener.onTextDelta(text);
                }
            }
            return;
        }

        if ("result".equals(type) && !emittedStreamingText) {
            String result = string(event, "result");
            if (result != null && !result.isBlank()) listener.onTextDelta(result);
        }
    }

    private static String string(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
    }
}
