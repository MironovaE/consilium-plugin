package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

abstract class AbstractCliAgent implements CliAgent {
    @Override
    public RunningRequest start(Path projectRoot, String prompt, String sessionId, AgentAccess access, AgentListener listener) {
        AtomicReference<Process> processRef = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        AppExecutorUtil.getAppExecutorService().execute(() -> {
            StringBuilder nonJsonOutput = new StringBuilder();
            try {
                List<String> command = command(projectRoot, sessionId, access);
                Process process = new ProcessBuilder(command)
                        .directory(projectRoot.toFile())
                        .redirectErrorStream(true)
                        .start();
                processRef.set(process);
                if (cancelled.get()) {
                    process.destroyForcibly();
                    return;
                }

                try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write(prompt);
                    writer.flush();
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelled.get()) return;
                        JsonObject event = parse(line);
                        if (event == null) {
                            if (!line.isBlank()) nonJsonOutput.append(line).append('\n');
                            continue;
                        }
                        handleEvent(event, listener);
                    }
                }

                int exitCode = process.waitFor();
                if (cancelled.get()) return;
                if (exitCode == 0) {
                    listener.onCompleted();
                } else {
                    String detail = nonJsonOutput.toString().trim();
                    listener.onError(detail.isEmpty() ? speaker().label() + " CLI завершился с кодом " + exitCode : detail);
                }
            } catch (IOException exception) {
                if (!cancelled.get()) {
                    listener.onError("Не удалось запустить " + speaker().label() + " CLI: " + exception.getMessage());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!cancelled.get()) listener.onError(speaker().label() + " был прерван");
            } catch (RuntimeException exception) {
                if (!cancelled.get()) listener.onError(speaker().label() + ": " + exception.getMessage());
            }
        });

        return () -> {
            cancelled.set(true);
            Process process = processRef.get();
            if (process != null && process.isAlive()) {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
            }
        };
    }

    protected abstract List<String> command(Path projectRoot, String sessionId, AgentAccess access);

    protected abstract void handleEvent(JsonObject event, AgentListener listener);

    private static JsonObject parse(String line) {
        try {
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
