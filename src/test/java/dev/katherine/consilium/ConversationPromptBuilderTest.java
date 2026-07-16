package dev.katherine.consilium;

import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.Speaker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationPromptBuilderTest {
    private static ChatMessage msg(Speaker speaker, String text) {
        return new ChatMessage("id", speaker, text, 0L);
    }

    private static final List<ChatMessage> TRANSCRIPT = List.of(
            msg(Speaker.KATHERINE, "первый вопрос"),
            msg(Speaker.CLAUDE, "ответ клода"),
            msg(Speaker.CODEX, "ответ кодекса"),
            msg(Speaker.KATHERINE, "второй вопрос")
    );

    @Test
    void freshReadOnlyIncludesWholeTranscriptWithDiscussionRules() {
        String prompt = ConversationPromptBuilder.build(
                TRANSCRIPT, Speaker.CLAUDE, 0, true, "", AgentAccess.READ_ONLY);

        assertTrue(prompt.contains("режим обсуждения"));
        assertTrue(prompt.contains("первый вопрос"));
        assertTrue(prompt.contains("ответ клода"));
        assertTrue(prompt.contains("ответ кодекса"));
        assertTrue(prompt.contains("второй вопрос"));
        assertTrue(prompt.endsWith("Твоя очередь, Claude."));
    }

    @Test
    void continuationStartsAfterSeenThroughAndSkipsOwnReplies() {
        String prompt = ConversationPromptBuilder.build(
                TRANSCRIPT, Speaker.CLAUDE, 1, false, "", AgentAccess.READ_ONLY);

        assertTrue(prompt.contains("Новые реплики"));
        assertFalse(prompt.contains("первый вопрос"), "реплики до seenThrough уже видел");
        assertFalse(prompt.contains("ответ клода"), "свои реплики агент не перечитывает");
        assertTrue(prompt.contains("ответ кодекса"), "позицию второго агента — видит");
        assertTrue(prompt.contains("второй вопрос"));
    }

    @Test
    void writeModeSendsFullTranscriptWithTaskFraming() {
        String prompt = ConversationPromptBuilder.build(
                TRANSCRIPT, Speaker.CODEX, 3, false, "", AgentAccess.WRITE);

        assertTrue(prompt.contains("правом изменять репозиторий"));
        assertTrue(prompt.contains("первый вопрос"), "write-ход всегда с полным транскриптом");
        assertTrue(prompt.contains("ответ кодекса"), "в write свои реплики не фильтруются");
        assertTrue(prompt.endsWith("Твоя очередь, Codex."));
    }

    @Test
    void emptyDeltaFallsBackToWaitingLine() {
        String prompt = ConversationPromptBuilder.build(
                TRANSCRIPT, Speaker.CODEX, TRANSCRIPT.size(), false, "", AgentAccess.READ_ONLY);

        assertTrue(prompt.contains("Katherine ждёт твоего ответа"));
    }

    @Test
    void editorContextIsAppendedWhenPresent() {
        String prompt = ConversationPromptBuilder.build(
                TRANSCRIPT, Speaker.CLAUDE, 0, true, "Файл: /x/y.py", AgentAccess.READ_ONLY);

        assertTrue(prompt.contains("Контекст из редактора IDEA:\nФайл: /x/y.py"));
    }
}
