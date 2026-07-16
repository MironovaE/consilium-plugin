package dev.katherine.consilium;

import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.Speaker;
import dev.katherine.consilium.cli.AgentAccess;

import java.util.List;

final class ConversationPromptBuilder {
    private ConversationPromptBuilder() {
    }

    static String build(List<ChatMessage> snapshot, Speaker agent, int seenThrough, boolean freshSession, String editorContext, AgentAccess access) {
        StringBuilder result = new StringBuilder();
        if (access == AgentAccess.WRITE) {
            result.append("""
                    Ты участвуешь в личном рабочем чате Consilium вместе с Katherine и вторым coding-агентом.
                    Katherine явно выбрала режим «ты делаешь». На этом ходе ты единственный агент с правом изменять репозиторий.
                    Выполни поставленную задачу полностью: исследуй код, внеси нужные изменения, проверь результат и кратко отчитайся.
                    Второй агент на этом ходе не запущен, поэтому не жди его и не ограничивайся советом или планом.
                    Ниже общий транскрипт комнаты. Katherine — председатель, её последнее сообщение является текущей задачей.

                    """);
        } else if (freshSession) {
            result.append("""
                    Ты участвуешь в личном рабочем чате Consilium вместе с Katherine и вторым coding-агентом.
                    Katherine — председатель. Отвечай ей напрямую, по существу и своим голосом. Не соглашайся ради консенсуса.
                    Сейчас режим обсуждения: репозиторий можно исследовать, но нельзя изменять. Не запускай запись, git-операции или внешние действия.
                    Ниже общий транскрипт комнаты. Твоя реплика должна продолжить его; не пересказывай правила.

                    """);
        } else {
            result.append("""
                    Новые реплики в общей комнате Consilium после твоего прошлого ответа приведены ниже.
                    Ответь Katherine с учётом позиции второго агента. Режим обсуждения остаётся read-only.

                    """);
        }

        int start = freshSession || access == AgentAccess.WRITE ? 0 : Math.max(0, Math.min(seenThrough, snapshot.size()));
        boolean wroteMessage = false;
        for (int index = start; index < snapshot.size(); index++) {
            ChatMessage message = snapshot.get(index);
            if (!freshSession && access == AgentAccess.READ_ONLY && message.speakerValue() == agent) {
                continue;
            }
            result.append(message.speakerValue().label()).append(":\n")
                    .append(message.text == null ? "" : message.text.trim()).append("\n\n");
            wroteMessage = true;
        }

        if (!wroteMessage) {
            result.append("Katherine ждёт твоего ответа на текущий ход.\n\n");
        }
        if (editorContext != null && !editorContext.isBlank()) {
            result.append("Контекст из редактора IDEA:\n").append(editorContext).append("\n\n");
        }
        result.append("Твоя очередь, ").append(agent.label()).append(".");
        return result.toString();
    }
}
