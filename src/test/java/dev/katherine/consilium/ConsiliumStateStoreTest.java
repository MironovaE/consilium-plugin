package dev.katherine.consilium;

import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.ConsiliumSnapshot;
import dev.katherine.consilium.model.Speaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConsiliumStateStoreTest {
    private static ConsiliumSnapshot snapshotOf(int count) {
        ConsiliumSnapshot snapshot = new ConsiliumSnapshot();
        for (int index = 0; index < count; index++) {
            snapshot.messages.add(new ChatMessage("m" + index, Speaker.KATHERINE, "text " + index, index));
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
}
