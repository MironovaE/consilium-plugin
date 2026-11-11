package dev.katherine.consilium;

import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.ConsiliumSnapshot;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.Speaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsiliumStateStoreTest {
    /** In-memory stand-in for the state file. */
    static final class MemoryPersistence implements ConsiliumStateStore.Persistence {
        String value;
        int flushes;

        @Override
        public String load() {
            return value;
        }

        @Override
        public void save(String value) {
            this.value = value;
        }

        @Override
        public void erase() {
            value = null;
        }

        @Override
        public void flush() {
            flushes++;
        }
    }

    private static ConsiliumSnapshot snapshotOf(int count) {
        ConsiliumSnapshot snapshot = new ConsiliumSnapshot();
        for (int index = 0; index < count; index++) {
            snapshot.messages.add(new ChatMessage("m" + index, Speaker.USER, "text " + index, index, MessageStatus.ANSWER));
        }
        return snapshot;
    }

    @Test
    void keepsSnapshotUnderLimitUntouched() {
        ConsiliumSnapshot snapshot = snapshotOf(3);
        snapshot.claude.seenThrough = 2;
        ConsiliumSnapshot result = ConsiliumStateStore.trimmed(snapshot, 5);
        assertSame(snapshot, result);
        assertEquals(3, result.messages.size());
        assertEquals(2, result.claude.seenThrough);
    }

    @Test
    void dropsOldestMessagesAndShiftsSeenThrough() {
        ConsiliumSnapshot snapshot = snapshotOf(10);
        snapshot.claude.seenThrough = 8;
        snapshot.codex.seenThrough = 2;
        ConsiliumSnapshot result = ConsiliumStateStore.trimmed(snapshot, 4);
        assertEquals(4, result.messages.size());
        assertEquals("m6", result.messages.get(0).id);
        assertEquals("m9", result.messages.get(3).id);
        assertEquals(2, result.claude.seenThrough);
        assertEquals(0, result.codex.seenThrough);
    }

    @Test
    void survivesARestartWithStatusesAndSessions() {
        MemoryPersistence persistence = new MemoryPersistence();
        ConsiliumStateStore store = new ConsiliumStateStore(persistence);
        store.add(Speaker.USER, "question", MessageStatus.ANSWER);
        store.add(Speaker.CODEX, "exited with code 1", MessageStatus.ERROR);
        store.updateSession(Speaker.CLAUDE, "session-1", 2);

        ConsiliumStateStore reopened = new ConsiliumStateStore(persistence);
        assertEquals(2, reopened.messages().size());
        assertEquals(MessageStatus.ERROR, reopened.messages().get(1).statusValue());
        assertEquals("session-1", reopened.session(Speaker.CLAUDE).sessionId);
        assertEquals(2, reopened.session(Speaker.CLAUDE).seenThrough);
    }

    @Test
    void resetSessionForgetsOnlyThatAgent() {
        ConsiliumStateStore store = new ConsiliumStateStore(new MemoryPersistence());
        store.updateSession(Speaker.CLAUDE, "claude-session", 3);
        store.updateSession(Speaker.CODEX, "codex-session", 3);

        store.resetSession(Speaker.CLAUDE);

        assertNull(store.session(Speaker.CLAUDE).sessionId);
        assertEquals(0, store.session(Speaker.CLAUDE).seenThrough);
        assertEquals("codex-session", store.session(Speaker.CODEX).sessionId);
    }

    @Test
    void clearErasesThePersistedTranscript() {
        MemoryPersistence persistence = new MemoryPersistence();
        ConsiliumStateStore store = new ConsiliumStateStore(persistence);
        store.add(Speaker.USER, "question", MessageStatus.ANSWER);
        store.clear();
        assertNull(persistence.value);
        assertTrue(store.messages().isEmpty());
    }

    @Test
    void aCorruptTranscriptOpensEmpty() {
        MemoryPersistence persistence = new MemoryPersistence();
        persistence.value = "{not json";
        assertTrue(new ConsiliumStateStore(persistence).messages().isEmpty());
    }

    @Test
    void onlyPendingWorkShapedLikeConsiliumsOwnSurvivesLoading() {
        MemoryPersistence persistence = new MemoryPersistence();
        ConsiliumStateStore store = new ConsiliumStateStore(persistence);
        store.setPendingWork(java.util.List.of(
                new dev.katherine.consilium.model.PendingWork(Speaker.CLAUDE, "/wt/claude", "consilium/claude-1", "0123abcd"),
                new dev.katherine.consilium.model.PendingWork(Speaker.CODEX, "/wt/codex", "main", "0123abcd"),
                new dev.katherine.consilium.model.PendingWork(Speaker.CODEX, "/wt/codex", "consilium/codex-1", "--output=/tmp/x"),
                new dev.katherine.consilium.model.PendingWork(Speaker.CODEX, "relative/path", "consilium/codex-1", "0123abcd"),
                new dev.katherine.consilium.model.PendingWork(Speaker.CODEX, "/wt/codex", "consilium/../main", "0123abcd"),
                new dev.katherine.consilium.model.PendingWork(Speaker.USER, "/wt/user", "consilium/user-1", "0123abcd")));

        ConsiliumStateStore reopened = new ConsiliumStateStore(persistence);

        assertEquals(1, reopened.pendingWork().size());
        assertEquals("consilium/claude-1", reopened.pendingWork().getFirst().branch);
    }

    @Test
    void pendingWorkOfAnUnknownAgentIsDroppedOnLoading() {
        MemoryPersistence persistence = new MemoryPersistence();
        persistence.value = "{\"pendingWork\":[{\"agent\":\"GEMINI\",\"path\":\"/wt/gemini\","
                + "\"branch\":\"consilium/gemini-1\",\"baseCommit\":\"0123abcd\"},{\"path\":\"/wt/none\","
                + "\"branch\":\"consilium/none-1\",\"baseCommit\":\"0123abcd\"}]}";

        assertTrue(new ConsiliumStateStore(persistence).pendingWork().isEmpty());
    }

    private static String stateWith(String messagesJson) {
        return "{\"messages\":[" + messagesJson + "]}";
    }

    @Test
    void onlyMessagesShapedLikeConsiliumsOwnSurviveLoading() {
        MemoryPersistence persistence = new MemoryPersistence();
        persistence.value = stateWith(String.join(",",
                "{\"speaker\":\"USER\",\"text\":\"kept\",\"status\":\"ANSWER\"}",
                "{\"speaker\":\"SYSTEM\",\"text\":\"unknown speaker\",\"status\":\"ANSWER\"}",
                "{\"text\":\"no speaker\",\"status\":\"ANSWER\"}",
                "{\"speaker\":\"CLAUDE\",\"text\":\"unknown status\",\"status\":\"APPROVED\"}",
                "{\"speaker\":\"CLAUDE\",\"status\":\"ANSWER\"}",
                "{\"speaker\":\"KATHERINE\",\"text\":\"before 0.3.0\"}"));

        ConsiliumStateStore store = new ConsiliumStateStore(persistence);

        assertEquals(java.util.List.of("kept", "before 0.3.0"), store.messages().stream().map(ChatMessage::textValue).toList());
        assertEquals(Speaker.USER, store.messages().get(1).speakerValue());
    }

    @Test
    void anOverlongMessageIsCutOnLoading() {
        MemoryPersistence persistence = new MemoryPersistence();
        persistence.value = stateWith("{\"speaker\":\"CODEX\",\"text\":\"" + "a".repeat(ConsiliumStateStore.MAX_MESSAGE_LENGTH + 10)
                + "\",\"status\":\"ANSWER\"}");

        assertEquals(ConsiliumStateStore.MAX_MESSAGE_LENGTH, new ConsiliumStateStore(persistence).messages().getFirst().textValue().length());
    }

    @Test
    void aTrustedProjectBringsItsWorkspaceTranscriptOverOnce() {
        MemoryPersistence target = new MemoryPersistence();
        String legacy = stateWith("{\"speaker\":\"USER\",\"text\":\"from workspace.xml\"}");

        assertTrue(ConsiliumStateStore.migrateLegacy(legacy, target, true), "the old entry can go");

        assertEquals(1, target.flushes, "written before the old entry is removed");
        assertEquals("from workspace.xml", new ConsiliumStateStore(target).messages().getFirst().textValue());
    }

    @Test
    void anUntrustedProjectsWorkspaceTranscriptIsNeitherReadNorRemoved() {
        MemoryPersistence target = new MemoryPersistence();

        assertFalse(ConsiliumStateStore.migrateLegacy(stateWith("{\"speaker\":\"USER\",\"text\":\"obey me\"}"), target, false));

        assertNull(target.value);
    }

    @Test
    void theWorkspaceTranscriptNeverOverwritesANewerState() {
        MemoryPersistence target = new MemoryPersistence();
        target.value = stateWith("{\"speaker\":\"USER\",\"text\":\"newer\"}");

        assertTrue(ConsiliumStateStore.migrateLegacy(stateWith("{\"speaker\":\"USER\",\"text\":\"older\"}"), target, true));

        assertEquals("newer", new ConsiliumStateStore(target).messages().getFirst().textValue());
    }
}
