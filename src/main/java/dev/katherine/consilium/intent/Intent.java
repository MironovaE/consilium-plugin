package dev.katherine.consilium.intent;

import dev.katherine.consilium.model.Speaker;

import java.util.Set;

/**
 * What the person's message asks for. Decided from the person's own words only, never from what
 * the agents wrote: write access must not be something an agent can talk itself into.
 *
 * @param agents who should act; for {@link Kind#INTEGRATE} the one agent that merges the parts.
 *               Never contains {@link Speaker#USER}; empty only for {@link Kind#UNCLEAR}.
 */
public record Intent(Kind kind, Set<Speaker> agents) {
    public enum Kind {
        /** Questions, opinions, planning: the agents read but don't change anything. */
        DISCUSS,
        /** Change the code. One agent: it works in the project. Both: each does its agreed part in its own copy. */
        WORK,
        /** Merge the parts the agents did in their copies into the project. */
        INTEGRATE,
        /** The message could mean work or discussion; ask instead of guessing. */
        UNCLEAR
    }

    public Intent {
        agents = Set.copyOf(agents);
        if (agents.contains(Speaker.USER)) throw new IllegalArgumentException("The user is not an agent");
        if (kind != Kind.UNCLEAR && agents.isEmpty()) throw new IllegalArgumentException(kind + " needs an agent");
        if (kind == Kind.INTEGRATE && agents.size() != 1) throw new IllegalArgumentException("One agent integrates");
    }

    /** The safe reading when nothing better is known: both agents discuss, nobody writes. */
    public static Intent discussWithBoth() {
        return new Intent(Kind.DISCUSS, Set.of(Speaker.CLAUDE, Speaker.CODEX));
    }
}
