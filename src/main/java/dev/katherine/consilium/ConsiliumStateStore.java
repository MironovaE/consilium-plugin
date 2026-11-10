package dev.katherine.consilium;

import com.google.gson.Gson;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import dev.katherine.consilium.model.AgentSession;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.ConsiliumSnapshot;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.Speaker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ConsiliumStateStore {
    private static final String KEY = "dev.katherine.consilium.state.v1";
    // The whole transcript is serialized into the project's workspace.xml, which the IDE reads on
    // every open. It is trimmed by count and only on load, so that the seenThrough and snapshot
    // indices of turns in flight never shift mid-session.
    static final int MAX_STORED_MESSAGES = 300;

    /** Where the serialized transcript lives; the project's PropertiesComponent in the IDE. */
    interface Persistence {
        String load();

        void save(String value);

        void erase();
    }

    private final Persistence persistence;
    private final Gson gson = new Gson();
    private ConsiliumSnapshot state;

    ConsiliumStateStore(Project project) {
        this(propertiesOf(PropertiesComponent.getInstance(project)));
    }

    ConsiliumStateStore(Persistence persistence) {
        this.persistence = persistence;
        state = read();
    }

    synchronized List<ChatMessage> messages() {
        return new ArrayList<>(state.messages);
    }

    synchronized ChatMessage add(Speaker speaker, String text, MessageStatus status) {
        ChatMessage message = new ChatMessage(UUID.randomUUID().toString(), speaker, text, System.currentTimeMillis(), status);
        state.messages.add(message);
        save();
        return message;
    }

    synchronized AgentSession session(Speaker speaker) {
        AgentSession source = sessionOf(speaker);
        return new AgentSession(source.sessionId, source.seenThrough);
    }

    synchronized void updateSession(Speaker speaker, String sessionId, int seenThrough) {
        AgentSession target = sessionOf(speaker);
        if (sessionId != null && !sessionId.isBlank()) {
            target.sessionId = sessionId;
        }
        target.seenThrough = seenThrough;
        save();
    }

    /**
     * Forgets the agent's discussion session, so its next discussion turn starts fresh with the
     * whole transcript. Needed after a "does" turn: that ran in a separate session, and resuming
     * the old one would skip the agent's own report of what it changed.
     */
    synchronized void resetSession(Speaker speaker) {
        AgentSession target = sessionOf(speaker);
        target.sessionId = null;
        target.seenThrough = 0;
        save();
    }

    synchronized void clear() {
        state = new ConsiliumSnapshot();
        persistence.erase();
    }

    private AgentSession sessionOf(Speaker speaker) {
        return switch (speaker) {
            case CLAUDE -> state.claude;
            case CODEX -> state.codex;
            case USER -> throw new IllegalArgumentException("The user has no agent session");
        };
    }

    private ConsiliumSnapshot read() {
        String raw = persistence.load();
        if (raw == null || raw.isBlank()) {
            return new ConsiliumSnapshot();
        }
        try {
            ConsiliumSnapshot parsed = gson.fromJson(raw, ConsiliumSnapshot.class);
            if (parsed == null) return new ConsiliumSnapshot();
            if (parsed.messages == null) parsed.messages = new ArrayList<>();
            parsed.messages.removeIf(message -> message == null);
            parsed.messages.forEach(ChatMessage::upgradeLegacyStatus);
            if (parsed.claude == null) parsed.claude = new AgentSession(null, 0);
            if (parsed.codex == null) parsed.codex = new AgentSession(null, 0);
            return trimmed(parsed, MAX_STORED_MESSAGES);
        } catch (RuntimeException ignored) {
            // A corrupt entry must not lock the tool window; the user loses the transcript, not the IDE.
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
        persistence.save(gson.toJson(state));
    }

    private static Persistence propertiesOf(PropertiesComponent properties) {
        return new Persistence() {
            @Override
            public String load() {
                return properties.getValue(KEY);
            }

            @Override
            public void save(String value) {
                properties.setValue(KEY, value);
            }

            @Override
            public void erase() {
                properties.unsetValue(KEY);
            }
        };
    }
}
