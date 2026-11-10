package dev.katherine.consilium.model;

import dev.katherine.consilium.ConsiliumBundle;

public enum Speaker {
    USER,
    CLAUDE,
    CODEX;

    /** The name shown in the tool window: "You" for the person, the agent's name otherwise. */
    public String label() {
        return switch (this) {
            case USER -> ConsiliumBundle.message("speaker.user");
            case CLAUDE -> "Claude";
            case CODEX -> "Codex";
        };
    }
}
