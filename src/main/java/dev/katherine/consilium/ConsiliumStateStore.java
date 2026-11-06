package dev.katherine.consilium;

import com.google.gson.Gson;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import dev.katherine.consilium.model.AgentSession;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.ConsiliumSnapshot;
import dev.katherine.consilium.model.Speaker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ConsiliumStateStore {
    private static final String KEY = "dev.katherine.consilium.state.v1";
    // Весь транскрипт сериализуется в workspace.xml проекта, который IDEA
    // читает при каждом открытии. Режем по количеству, и только при загрузке:
    // посреди сессии индексы seenThrough и snapshotSize в живых ходах не сдвигаются.
    static final int MAX_STORED_MESSAGES = 300;
    private final PropertiesComponent properties;
    private final Gson gson = new Gson();
    private ConsiliumSnapshot state;

    ConsiliumStateStore(Project project) {
        properties = PropertiesComponent.getInstance(project);
        state = read();
    }

    synchronized List<ChatMessage> messages() {
        return new ArrayList<>(state.messages);
    }

    synchronized ChatMessage add(Speaker speaker, String text) {
        ChatMessage message = new ChatMessage(UUID.randomUUID().toString(), speaker, text, System.currentTimeMillis());
        state.messages.add(message);
        save();
        return message;
    }

    synchronized AgentSession session(Speaker speaker) {
        AgentSession source = speaker == Speaker.CLAUDE ? state.claude : state.codex;
        return new AgentSession(source.sessionId, source.seenThrough);
    }

    synchronized void updateSession(Speaker speaker, String sessionId, int seenThrough) {
        AgentSession target = speaker == Speaker.CLAUDE ? state.claude : state.codex;
        if (sessionId != null && !sessionId.isBlank()) {
            target.sessionId = sessionId;
        }
        target.seenThrough = seenThrough;
        save();
    }

    synchronized void clear() {
        state = new ConsiliumSnapshot();
        properties.unsetValue(KEY);
    }

    private ConsiliumSnapshot read() {
        String raw = properties.getValue(KEY);
        if (raw == null || raw.isBlank()) {
            return new ConsiliumSnapshot();
        }
        try {
            ConsiliumSnapshot parsed = gson.fromJson(raw, ConsiliumSnapshot.class);
            if (parsed == null) return new ConsiliumSnapshot();
            if (parsed.messages == null) parsed.messages = new ArrayList<>();
            if (parsed.claude == null) parsed.claude = new AgentSession(null, 0);
            if (parsed.codex == null) parsed.codex = new AgentSession(null, 0);
            return trimmed(parsed, MAX_STORED_MESSAGES);
        } catch (RuntimeException ignored) {
            return new ConsiliumSnapshot();
        }
    }

    static ConsiliumSnapshot trimmed(ConsiliumSnapshot snapshot, int limit) {
        int excess = snapshot.messages.size() - limit;
        if (excess <= 0) return snapshot;
        snapshot.messages = new ArrayList<>(snapshot.messages.subList(excess, snapshot.messages.size()));
        snapshot.claude.seenThrough = Math.max(0, snapshot.claude.seenThrough - excess);
        snapshot.codex.seenThrough = Math.max(0, snapshot.codex.seenThrough - excess);
        return snapshot;
    }

    private void save() {
        properties.setValue(KEY, gson.toJson(state));
    }
}
