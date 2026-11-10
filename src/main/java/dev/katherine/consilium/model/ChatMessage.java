package dev.katherine.consilium.model;

import java.util.List;

/** One transcript entry. Public mutable fields keep it a plain Gson record in workspace.xml. */
public final class ChatMessage {
    // Before 0.3.2 a stopped answer was stored with this mark appended, and an error was stored as
    // an ordinary answer with this prefix. Both languages, because the IDE locale may have changed
    // since the transcript was written.
    private static final List<String> LEGACY_STOPPED_MARKS = List.of(
            "[stopped before the answer finished]", "[остановлено до конца ответа]");
    private static final List<String> LEGACY_ERROR_PREFIXES = List.of(
            "Couldn't answer: ", "Не получилось ответить: ");

    public String id;
    public String speaker;
    public String text;
    public long createdAt;
    /** {@link MessageStatus} name; absent in transcripts written before 0.3.2. */
    public String status;

    @SuppressWarnings("unused")
    public ChatMessage() {
    }

    public ChatMessage(String id, Speaker speaker, String text, long createdAt, MessageStatus status) {
        this.id = id;
        this.speaker = speaker.name();
        this.text = text;
        this.createdAt = createdAt;
        this.status = status.name();
    }

    public Speaker speakerValue() {
        try {
            return Speaker.valueOf(speaker);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Transcripts before 0.3.0 stored the human as KATHERINE, which is today's USER.
            return Speaker.USER;
        }
    }

    public MessageStatus statusValue() {
        if (status != null) {
            try {
                return MessageStatus.valueOf(status);
            } catch (IllegalArgumentException ignored) {
                return MessageStatus.ANSWER;
            }
        }
        return MessageStatus.ANSWER;
    }

    public String textValue() {
        return text == null ? "" : text;
    }

    /**
     * Upgrades a message written before 0.3.2, when the status lived inside the text. Agent
     * messages only: a person may well type "Couldn't answer: …" themselves.
     */
    public void upgradeLegacyStatus() {
        if (status != null || speakerValue() == Speaker.USER || text == null) return;
        String trimmed = text.trim();
        for (String mark : LEGACY_STOPPED_MARKS) {
            if (trimmed.endsWith(mark)) {
                text = trimmed.substring(0, trimmed.length() - mark.length()).trim();
                status = MessageStatus.STOPPED.name();
                return;
            }
        }
        for (String prefix : LEGACY_ERROR_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                text = trimmed.substring(prefix.length());
                status = MessageStatus.ERROR.name();
                return;
            }
        }
        status = MessageStatus.ANSWER.name();
    }
}
