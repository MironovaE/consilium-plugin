package dev.katherine.consilium;

import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.Speaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessageLegacyTest {
    private static ChatMessage stored(String speaker, String text) {
        ChatMessage message = new ChatMessage();
        message.speaker = speaker;
        message.text = text;
        message.upgradeLegacyStatus();
        return message;
    }

    @Test
    void transcriptsBefore030StillLoadTheHuman() {
        assertEquals(Speaker.USER, stored("KATHERINE", "hi").speakerValue());
    }

    @Test
    void stoppedMarkInTheTextBecomesAStatus() {
        ChatMessage english = stored("CLAUDE", "half\n\n[stopped before the answer finished]");
        assertEquals(MessageStatus.STOPPED, english.statusValue());
        assertEquals("half", english.text);

        ChatMessage russian = stored("CODEX", "половина\n\n[остановлено до конца ответа]");
        assertEquals(MessageStatus.STOPPED, russian.statusValue());
        assertEquals("половина", russian.text);
    }

    @Test
    void errorPrefixBecomesAStatus() {
        ChatMessage error = stored("CODEX", "Couldn't answer: codex CLI exited with code 1");
        assertEquals(MessageStatus.ERROR, error.statusValue());
        assertEquals("codex CLI exited with code 1", error.text);
    }

    @Test
    void theUserCanTypeTheErrorPrefixThemselves() {
        ChatMessage message = stored("USER", "Couldn't answer: why does this happen?");
        assertEquals(MessageStatus.ANSWER, message.statusValue());
        assertEquals("Couldn't answer: why does this happen?", message.text);
    }

    @Test
    void anOrdinaryAnswerStaysAnAnswer() {
        assertEquals(MessageStatus.ANSWER, stored("CLAUDE", "After launch.").statusValue());
    }
}
