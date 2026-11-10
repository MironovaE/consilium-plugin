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
            result.append(ConsiliumBundle.message("prompt.write"));
        } else if (freshSession) {
            result.append(ConsiliumBundle.message("prompt.discuss"));
        } else {
            result.append(ConsiliumBundle.message("prompt.followup"));
        }

        int start = freshSession || access == AgentAccess.WRITE ? 0 : Math.max(0, Math.min(seenThrough, snapshot.size()));
        boolean wroteMessage = false;
        for (int index = start; index < snapshot.size(); index++) {
            ChatMessage message = snapshot.get(index);
            if (!freshSession && access == AgentAccess.READ_ONLY && message.speakerValue() == agent) {
                continue;
            }
            result.append(message.speakerValue().promptLabel()).append(":\n")
                    .append(message.text == null ? "" : message.text.trim()).append("\n\n");
            wroteMessage = true;
        }

        if (!wroteMessage) {
            result.append(ConsiliumBundle.message("prompt.waiting"));
        }
        if (editorContext != null && !editorContext.isBlank()) {
            result.append(ConsiliumBundle.message("prompt.editor.context")).append(editorContext).append("\n\n");
        }
        result.append(ConsiliumBundle.message("prompt.your.turn", agent.label()));
        return result.toString();
    }
}
