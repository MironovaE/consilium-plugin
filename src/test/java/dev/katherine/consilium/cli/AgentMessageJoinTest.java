package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Несколько сообщений агента за ход не должны слипаться в одну строку. */
class AgentMessageJoinTest {
    private static final class Collector implements AgentListener {
        final List<String> deltas = new ArrayList<>();

        @Override public void onSessionId(String sessionId) { }
        @Override public void onTextDelta(String text) { deltas.add(text); }
        @Override public void onCompleted() { }
        @Override public void onError(String message) { }

        String text() {
            return String.join("", deltas);
        }
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    @Test
    void codexSeparatesAgentMessages() {
        CodexCliAgent agent = new CodexCliAgent();
        agent.command(Path.of("/project"), null, AgentAccess.READ_ONLY);
        Collector listener = new Collector();
        agent.handleEvent(json("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Посмотрю бэклог.\"}}"), listener);
        agent.handleEvent(json("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\",\"text\":\"…\"}}"), listener);
        agent.handleEvent(json("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Я бы оставил.\"}}"), listener);
        assertEquals("Посмотрю бэклог.\n\nЯ бы оставил.", listener.text());
    }

    @Test
    void claudeSeparatesTextBlocks() {
        ClaudeCliAgent agent = new ClaudeCliAgent();
        agent.command(Path.of("/project"), null, AgentAccess.READ_ONLY);
        Collector listener = new Collector();
        agent.handleEvent(json(start("text")), listener);
        agent.handleEvent(json(delta("Посмотрю бэклог.")), listener);
        agent.handleEvent(json(start("tool_use")), listener);
        agent.handleEvent(json(start("text")), listener);
        agent.handleEvent(json(delta("После выпуска.")), listener);
        assertEquals("Посмотрю бэклог.\n\nПосле выпуска.", listener.text());
    }

    private static String start(String blockType) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"" + blockType + "\"}}}";
    }

    private static String delta(String text) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}}";
    }
}
