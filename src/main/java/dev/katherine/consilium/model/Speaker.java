package dev.katherine.consilium.model;

public enum Speaker {
    KATHERINE("Katherine"),
    CLAUDE("Claude"),
    CODEX("Codex");

    private final String label;

    Speaker(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
