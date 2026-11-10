package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import dev.katherine.consilium.model.Speaker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CodexCliAgent extends AbstractCliAgent {
    // Codex за ход шлёт несколько agent_message: заметку «посмотрю…» и сам ответ.
    // Без разделителя они слипаются в одну строку: «…для выпуска.Я бы оставил».
    private boolean emittedMessage;

    @Override
    public Speaker speaker() {
        return Speaker.CODEX;
    }

    @Override
    protected List<String> command(Path projectRoot, String sessionId, AgentAccess access) {
        emittedMessage = false;
        List<String> command = new ArrayList<>();
        command.add(ExecutableResolver.resolve("codex"));
        command.add("exec");
        if (access == AgentAccess.READ_ONLY && sessionId != null && !sessionId.isBlank()) {
            // У `exec resume` нет флага --sandbox: без -c он берёт песочницу из
            // конфига пользователя (часто workspace-write), и обсуждение пишет в репо.
            command.add("resume");
            command.add("--json");
            command.add("-c");
            command.add("sandbox_mode=\"read-only\"");
            addNoEscalation(command);
            command.add(sessionId);
            command.add("-");
        } else {
            command.add("--json");
            command.add("--sandbox");
            command.add(access == AgentAccess.WRITE ? "workspace-write" : "read-only");
            if (access == AgentAccess.READ_ONLY) addNoEscalation(command);
            command.add("--cd");
            command.add(projectRoot.toString());
            command.add("-");
        }
        return command;
    }

    // Без этого запрещённая запись повторяется с запросом на эскалацию, и
    // автоодобрение из конфига пользователя её пропускает: read-only ход всё
    // равно меняет файлы (воспроизведено на codex-cli 0.167.0).
    private static void addNoEscalation(List<String> command) {
        command.add("-c");
        command.add("approval_policy=\"never\"");
    }

    @Override
    protected void handleEvent(JsonObject event, AgentListener listener) {
        String type = string(event, "type");
        if ("thread.started".equals(type)) {
            listener.onSessionId(string(event, "thread_id"));
            return;
        }
        if (!"item.completed".equals(type) || !event.has("item") || !event.get("item").isJsonObject()) {
            return;
        }
        JsonObject item = event.getAsJsonObject("item");
        if ("agent_message".equals(string(item, "type"))) {
            String text = string(item, "text");
            if (text == null || text.isBlank()) return;
            listener.onTextDelta(emittedMessage ? "\n\n" + text : text);
            emittedMessage = true;
        }
    }

    private static String string(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
    }
}
