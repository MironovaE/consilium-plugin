package dev.katherine.consilium.model;

import java.util.Arrays;
import java.util.List;

/** One transcript entry. Public mutable fields keep it a plain Gson record in the state file. */
public final class ChatMessage {
    // Before 0.3.2 a stopped answer was stored with this mark appended, and an error was stored as
    // an ordinary answer with this prefix. Both languages, because the IDE locale may have changed
    // since the transcript was written.
    private static final List<String> LEGACY_STOPPED_MARKS = List.of(
            "[stopped before the answer finished]", "[остановлено до конца ответа]");
    private static final List<String> LEGACY_ERROR_PREFIXES = List.of(
            "Couldn't answer: ", "Не получилось ответить: ");
    // Transcripts before 0.3.0 stored the human under this name, which is today's USER.
    private static final String LEGACY_USER = "KATHERINE";

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

    /**
     * True for the shapes Consilium writes, past versions included. Anything else came from
     * somewhere else and is dropped on load: an unknown speaker must never pass for the person.
     */
    public boolean isWellFormed() {
        boolean knownSpeaker = LEGACY_USER.equals(speaker) || isNameOf(Speaker.values(), speaker);
        boolean knownStatus = status == null || isNameOf(MessageStatus.values(), status);
        return text != null && knownSpeaker && knownStatus;
    }

    /** Valid for a {@linkplain #isWellFormed() well-formed} message. */
    public Speaker speakerValue() {
        return LEGACY_USER.equals(speaker) ? Speaker.USER : Speaker.valueOf(speaker);
    }

    /** Valid for a {@linkplain #isWellFormed() well-formed} message; absent means an answer. */
    public MessageStatus statusValue() {
        return status == null ? MessageStatus.ANSWER : MessageStatus.valueOf(status);
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

    private static boolean isNameOf(Enum<?>[] values, String name) {
        return Arrays.stream(values).anyMatch(value -> value.name().equals(name));
    }
}
