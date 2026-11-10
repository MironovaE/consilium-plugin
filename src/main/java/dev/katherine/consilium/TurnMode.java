package dev.katherine.consilium;

import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.model.Speaker;

import java.util.List;

/**
 * Who answers a message and with what access. The explicit modes are for people who prefer to pick;
 * {@link #AUTO} reads it from the message. Only a "does" mode, or a clear request in AUTO, grants
 * write access.
 */
enum TurnMode {
    /** The person just writes; what to do is read from the message (see ChatSession). */
    AUTO("target.auto", AgentAccess.READ_ONLY),
    BOTH("target.both", AgentAccess.READ_ONLY, Speaker.CLAUDE, Speaker.CODEX),
    CLAUDE("target.claude", AgentAccess.READ_ONLY, Speaker.CLAUDE),
    CODEX("target.codex", AgentAccess.READ_ONLY, Speaker.CODEX),
    CLAUDE_WRITES("target.claude.writes", AgentAccess.WRITE, Speaker.CLAUDE),
    CODEX_WRITES("target.codex.writes", AgentAccess.WRITE, Speaker.CODEX);

    private final String key;
    private final AgentAccess access;
    private final List<Speaker> agents;

    TurnMode(String key, AgentAccess access, Speaker... agents) {
        this.key = key;
        this.access = access;
        this.agents = List.of(agents);
    }

    AgentAccess access() {
        return access;
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
