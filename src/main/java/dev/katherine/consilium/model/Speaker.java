package dev.katherine.consilium.model;

import dev.katherine.consilium.ConsiliumBundle;

public enum Speaker {
    USER,
    CLAUDE,
    CODEX,
    /** The plugin itself: notices such as "both agents are working". Never sent to the agents. */
    CONSILIUM;

    /** The name shown in the tool window: "You" for the person, the agent's name otherwise. */
    public String label() {
        return switch (this) {
            case USER -> ConsiliumBundle.message("speaker.user");
            case CLAUDE -> "Claude";
            case CODEX -> "Codex";
            case CONSILIUM -> "Consilium";
        };
    }
}
