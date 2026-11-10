package dev.katherine.consilium.cli;

import dev.katherine.consilium.ConsiliumBundle;

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
import java.util.concurrent.TimeUnit;
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
                    String message = detail.isEmpty() ? ConsiliumBundle.message("error.exit.code", speaker().label(), exitCode) : detail;
                    listener.onError(message + flagHint(detail));
                }
            } catch (IOException exception) {
                if (!cancelled.get()) {
                    listener.onError(ConsiliumBundle.message("error.launch", speaker().label(), exception.getMessage()));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!cancelled.get()) listener.onError(ConsiliumBundle.message("error.interrupted", speaker().label()));
            } catch (RuntimeException exception) {
                if (!cancelled.get()) listener.onError(speaker().label() + ": " + exception.getMessage());
            }
        });

        return () -> {
            cancelled.set(true);
            Process process = processRef.get();
            if (process != null && process.isAlive()) {
                process.destroy();
                // Прежний `if (isAlive()) destroyForcibly()` сразу после destroy()
                // почти всегда истинен (SIGTERM асинхронен) — форс летел мгновенно,
                // не давая CLI шанса умереть чисто. Ждём — но НЕ на вызывающем треде
                // (cancel зовётся с EDT, waitFor заморозил бы UI).
                AppExecutorUtil.getAppExecutorService().execute(() -> {
                    try {
                        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        process.destroyForcibly();
                    }
                });
            }
        };
    }

    protected abstract List<String> command(Path projectRoot, String sessionId, AgentAccess access);

    protected abstract void handleEvent(JsonObject event, AgentListener listener);

    // Флаги CLI — нестабильная поверхность: после апгрейда агент падает сразу
    // на разборе аргументов. Подсказываем, где искать, вместо голого stderr.
    static String flagHint(String cliOutput) {
        String lower = cliOutput.toLowerCase(java.util.Locale.ROOT);
        boolean looksLikeFlagError = lower.contains("unknown option")
                || lower.contains("unexpected argument")
                || lower.contains("unrecognized option")
                || lower.contains("unrecognized argument");
        return looksLikeFlagError
                ? ConsiliumBundle.message("error.flag.hint")
                : "";
    }

    private static JsonObject parse(String line) {
        try {
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
