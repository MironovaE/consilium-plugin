package dev.katherine.consilium.model;

public final class ChatMessage {
    public String id;
    public String speaker;
    public String text;
    public long createdAt;

    @SuppressWarnings("unused")
    public ChatMessage() {
    }

    public ChatMessage(String id, Speaker speaker, String text, long createdAt) {
        this.id = id;
        this.speaker = speaker.name();
        this.text = text;
        this.createdAt = createdAt;
    }

    public Speaker speakerValue() {
        try {
            return Speaker.valueOf(speaker);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Транскрипты до 0.3.0 хранят человека как KATHERINE — это тот же USER.
            return Speaker.USER;
        }
    }
}
