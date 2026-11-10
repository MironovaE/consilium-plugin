package dev.katherine.consilium;

import com.google.gson.Gson;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import dev.katherine.consilium.model.AgentSession;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.ConsiliumSnapshot;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.PendingWork;
import dev.katherine.consilium.model.Speaker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ConsiliumStateStore {
    private static final String KEY = "dev.katherine.consilium.state.v1";
    // The whole transcript is serialized into the project's workspace.xml, which the IDE reads on
    // every open. It is trimmed by count on load and between turns (compact), never during one,
    // so that the seenThrough and snapshot indices of turns in flight don't shift.
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

    synchronized List<PendingWork> pendingWork() {
        return new ArrayList<>(state.pendingWork);
    }

    synchronized void setPendingWork(List<PendingWork> work) {
        state.pendingWork = new ArrayList<>(work);
        save();
    }

    /** Trims the transcript to the limit; call only when no turn is running. */
    synchronized void compact() {
        if (state.messages.size() <= MAX_STORED_MESSAGES) return;
        state = trimmed(state, MAX_STORED_MESSAGES);
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
            case USER, CONSILIUM -> throw new IllegalArgumentException(speaker + " has no agent session");
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
            if (parsed.pendingWork == null) parsed.pendingWork = new ArrayList<>();
            // workspace.xml can be edited by anyone who commits to the project; these values reach git
            // arguments and a write turn's prompt, so only the shapes Consilium itself creates pass.
            parsed.pendingWork.removeIf(work -> !isOwnWork(work));
            return trimmed(parsed, MAX_STORED_MESSAGES);
        } catch (RuntimeException ignored) {
            // A corrupt entry must not lock the tool window; the user loses the transcript, not the IDE.
            return new ConsiliumSnapshot();
        }
    }

    private static final java.util.regex.Pattern OWN_BRANCH = java.util.regex.Pattern.compile("consilium/[A-Za-z0-9._-]{1,100}");
    private static final java.util.regex.Pattern COMMIT = java.util.regex.Pattern.compile("[0-9a-f]{7,64}");

    static boolean isOwnWork(PendingWork work) {
        if (work == null || work.path == null || work.branch == null || work.baseCommit == null) return false;
        if (!OWN_BRANCH.matcher(work.branch).matches() || work.branch.contains("..")) return false;
        if (!COMMIT.matcher(work.baseCommit).matches()) return false;
        try {
            return java.nio.file.Path.of(work.path).isAbsolute();
        } catch (RuntimeException invalidPath) {
            return false;
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
