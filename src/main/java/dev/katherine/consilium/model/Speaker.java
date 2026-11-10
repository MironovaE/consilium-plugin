package dev.katherine.consilium.model;

import dev.katherine.consilium.ConsiliumBundle;

public enum Speaker {
    USER,
    CLAUDE,
    CODEX;

    /** Подпись в окне: «You»/«Вы» для человека, имя агента для агентов. */
    public String label() {
        return switch (this) {
            case USER -> ConsiliumBundle.message("speaker.user");
            case CLAUDE -> "Claude";
            case CODEX -> "Codex";
        };
    }

    /** Подпись в транскрипте, который читают агенты: им нужно «User», а не «You». */
    public String promptLabel() {
        return this == USER ? ConsiliumBundle.message("prompt.speaker.user") : label();
    }
}
