package dev.katherine.consilium;

import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.model.Speaker;

import java.util.List;

/** Who answers a message and with what access. Only a "does" mode grants write access, and to one agent. */
enum TurnMode {
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
