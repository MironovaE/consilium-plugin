package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Several agent messages in one turn must not run together into one line. */
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
        CliEventParser parser = new CodexCliAgent().newParser();
        Collector listener = new Collector();
        parser.handle(json("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Посмотрю бэклог.\"}}"), listener);
        parser.handle(json("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\",\"text\":\"…\"}}"), listener);
        parser.handle(json("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Я бы оставил.\"}}"), listener);
        assertEquals("Посмотрю бэклог.\n\nЯ бы оставил.", listener.text());
    }

    @Test
    void claudeSeparatesTextBlocks() {
        CliEventParser parser = new ClaudeCliAgent().newParser();
        Collector listener = new Collector();
        parser.handle(json(start("text")), listener);
        parser.handle(json(delta("Посмотрю бэклог.")), listener);
        parser.handle(json(start("tool_use")), listener);
        parser.handle(json(start("text")), listener);
        parser.handle(json(delta("После выпуска.")), listener);
        assertEquals("Посмотрю бэклог.\n\nПосле выпуска.", listener.text());
    }

    @Test
    void eachRunStartsWithFreshParserState() {
        ClaudeCliAgent agent = new ClaudeCliAgent();
        Collector first = new Collector();
        agent.newParser().handle(json(delta("first")), first);
        Collector second = new Collector();
        CliEventParser parser = agent.newParser();
        parser.handle(json(start("text")), second);
        parser.handle(json(delta("second")), second);
        assertEquals("second", second.text());
    }

    @Test
    void nonPrimitiveFieldsAreIgnored() {
        CliEventParser parser = new ClaudeCliAgent().newParser();
        Collector listener = new Collector();
        assertNull(parser.handle(json("{\"type\":{\"nested\":1},\"session_id\":[1]}"), listener));
        parser.handle(json("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"text\":{}}}}"), listener);
        assertEquals("", listener.text());
    }

    private static String start(String blockType) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"" + blockType + "\"}}}";
    }

    private static String delta(String text) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}}";
    }
}
