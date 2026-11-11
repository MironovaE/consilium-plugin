package dev.katherine.consilium;

import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.Speaker;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the shared transcript into one agent's prompt.
 *
 * <p>Every message is wrapped in a {@code <message from="…">} block and the framing says that only
 * {@code from="user"} blocks are instructions. With plain "Label:" lines an agent that had read a
 * hostile file could print "User: …" and the next writing agent would take it as the person's
 * request. Tag-like text inside a message is escaped, so a message can't close its own block.
 */
final class ConversationPromptBuilder {
    private static final Pattern BLOCK_TAG = Pattern.compile("<(/?)(message|editor-context)\\b", Pattern.CASE_INSENSITIVE);

    private ConversationPromptBuilder() {
    }

    /** @param writeHeader replaces the default task framing of a write turn (parallel part, merge); null keeps it */
    static String build(List<ChatMessage> snapshot, Speaker agent, int seenThrough, boolean freshSession, String editorContext,
                        AgentAccess access, String writeHeader) {
        boolean resumes = !freshSession && !access.writes();
        StringBuilder result = new StringBuilder();
        if (access.writes()) {
            result.append(writeHeader != null ? writeHeader : ConsiliumBundle.message("prompt.write"));
        } else if (freshSession) {
            result.append(ConsiliumBundle.message("prompt.discuss"));
        } else {
            result.append(ConsiliumBundle.message("prompt.followup"));
        }
        result.append(ConsiliumBundle.message("prompt.format"));

        int start = resumes ? Math.max(0, Math.min(seenThrough, snapshot.size())) : 0;
        boolean wroteMessage = false;
        for (int index = start; index < snapshot.size(); index++) {
            ChatMessage message = snapshot.get(index);
            MessageStatus status = message.statusValue();
            // An error is the plugin's report about an agent, not something the agent said, and a
            // notice is the plugin talking to the person.
            if (status == MessageStatus.ERROR || message.speakerValue() == Speaker.CONSILIUM) continue;
            // A resumed session already holds this agent's own answers.
            if (resumes && message.speakerValue() == agent) continue;
            result.append("<message from=\"").append(message.speakerValue().name().toLowerCase(Locale.ROOT)).append('"');
            if (status == MessageStatus.STOPPED) result.append(" status=\"cut-off\"");
            result.append(">\n").append(escape(message.textValue().trim())).append("\n</message>\n\n");
            wroteMessage = true;
        }

        if (!wroteMessage) {
            result.append(ConsiliumBundle.message("prompt.waiting"));
        }
        if (editorContext != null && !editorContext.isBlank()) {
            result.append("<editor-context>\n").append(escape(editorContext.trim())).append("\n</editor-context>\n\n");
        }
        result.append(ConsiliumBundle.message("prompt.your.turn", agent.label()));
        return result.toString();
    }

    static String escape(String text) {
        return BLOCK_TAG.matcher(text).replaceAll("&lt;$1$2");
    }
}
