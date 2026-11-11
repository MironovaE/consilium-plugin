package dev.katherine.consilium;

import com.google.gson.Gson;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
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
    // Where 0.4.0 and earlier kept the state: the project's workspace.xml.
    private static final String LEGACY_KEY = "dev.katherine.consilium.state.v1";
    // The whole transcript is serialized on every change and read on every open. It is trimmed by
    // count on load and between turns (compact), never during one, so that the seenThrough and
    // snapshot indices of turns in flight don't shift.
    static final int MAX_STORED_MESSAGES = 300;
    // Generous for an agent's answer; a longer text is cut on load rather than kept whole.
    static final int MAX_MESSAGE_LENGTH = 200_000;
    // Anything larger isn't a state Consilium wrote: it opens empty instead of filling the heap.
    private static final int MAX_STATE_LENGTH = 64 * 1024 * 1024;

    /** Where the serialized state lives; a {@link StateFile} in the IDE. */
    interface Persistence {
        String load();

        /** May write later; {@link #flush()} makes sure it's written. */
        void save(String value);

        /** Writes what {@link #save} left pending, before returning. */
        default void flush() {
        }
    }

    private final Persistence persistence;
    private final Gson gson = new Gson();
    private ConsiliumSnapshot state;

    /** The project's store in the IDE, with the state brought over from workspace.xml if it is still there. */
    static ConsiliumStateStore open(Project project) {
        StateFile file = new StateFile(StateFile.locationOf(project),
                AppExecutorUtil.createBoundedApplicationPoolExecutor("Consilium state", 1));
        PropertiesComponent workspace = PropertiesComponent.getInstance(project);
        String legacy = workspace.getValue(LEGACY_KEY);
        if (legacy != null && migrateLegacy(legacy, file, TrustedProjects.isProjectTrusted(project))) {
            workspace.unsetValue(LEGACY_KEY);
        }
        return new ConsiliumStateStore(file);
    }

    /**
     * Brings the state kept in workspace.xml by 0.4.0 and earlier over to {@code target}, once.
     *
     * @return whether the old entry is done with and should be removed
     */
    static boolean migrateLegacy(String legacy, Persistence target, boolean projectTrusted) {
        // workspace.xml lies in the project tree, so a cloned repository can ship one with "user"
        // messages the agents would obey. Only a project the person trusts brings its transcript
        // along. An untrusted one's entry stays where it is, unread; it is brought over later only if
        // the project is trusted before the room was ever used or cleared here.
        if (!projectTrusted) return false;
        // A state already in the new place, even an empty one Clear left, is newer than anything
        // workspace.xml holds.
        String current = target.load();
        if (current == null) {
            target.save(legacy);
            target.flush();
        }
        return true;
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
        // An empty state, not a deleted file: a missing file is what lets an old workspace.xml
        // entry be brought over, and a cleared room must not get its transcript back that way.
        save();
    }

    /** Writes the state out now; for closing the project. */
    synchronized void flush() {
        persistence.flush();
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
        if (raw == null || raw.isBlank() || raw.length() > MAX_STATE_LENGTH) {
            return new ConsiliumSnapshot();
        }
        try {
            ConsiliumSnapshot parsed = gson.fromJson(raw, ConsiliumSnapshot.class);
            if (parsed == null) return new ConsiliumSnapshot();
            if (parsed.messages == null) parsed.messages = new ArrayList<>();
            // Only the shapes Consilium writes pass: an unknown speaker must not become the person.
            parsed.messages.removeIf(message -> message == null || !message.isWellFormed());
            for (ChatMessage message : parsed.messages) {
                message.upgradeLegacyStatus();
                if (message.text.length() > MAX_MESSAGE_LENGTH) message.text = message.text.substring(0, MAX_MESSAGE_LENGTH);
            }
            if (parsed.claude == null || parsed.claude.seenThrough < 0) parsed.claude = new AgentSession(null, 0);
            if (parsed.codex == null || parsed.codex.seenThrough < 0) parsed.codex = new AgentSession(null, 0);
            if (parsed.pendingWork == null) parsed.pendingWork = new ArrayList<>();
            // These values reach git arguments and a write turn's prompt, and a state brought over
            // from workspace.xml came from the project tree: only the shapes Consilium creates pass.
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
        if (work == null || !work.hasKnownAgent() || work.path == null || work.branch == null || work.baseCommit == null) return false;
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
}
