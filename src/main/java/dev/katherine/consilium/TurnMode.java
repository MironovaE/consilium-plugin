package dev.katherine.consilium;

import dev.katherine.consilium.model.Speaker;

import java.util.List;

/**
 * Who answers a message and whether they may write. The explicit modes are for people who prefer to
 * pick; {@link #AUTO} reads it from the message. Only a "does" mode, or a clear request in AUTO,
 * grants write access; how much the writer loads of the project's own settings is the session's
 * call (see ChatSession).
 */
enum TurnMode {
    /** The person just writes; what to do is read from the message (see ChatSession). */
    AUTO("target.auto", false),
    BOTH("target.both", false, Speaker.CLAUDE, Speaker.CODEX),
    CLAUDE("target.claude", false, Speaker.CLAUDE),
    CODEX("target.codex", false, Speaker.CODEX),
    CLAUDE_WRITES("target.claude.writes", true, Speaker.CLAUDE),
    CODEX_WRITES("target.codex.writes", true, Speaker.CODEX);

    private final String key;
    private final boolean writes;
    private final List<Speaker> agents;

    TurnMode(String key, boolean writes, Speaker... agents) {
        this.key = key;
        this.writes = writes;
        this.agents = List.of(agents);
    }

    boolean writes() {
        return writes;
    }

    List<Speaker> agents() {
        return agents;
    }

    /** The combo box shows the enum's {@code toString}. */
    @Override
    public String toString() {
        return ConsiliumBundle.message(key);
    }
}
