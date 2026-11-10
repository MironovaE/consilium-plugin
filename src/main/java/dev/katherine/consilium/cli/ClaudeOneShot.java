package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One {@code claude -p --output-format json --json-schema …} call that answers with a structured
 * object instead of chat text. It runs on the same {@link CliRun} lifecycle as chat turns, so the
 * process tree is reaped on every path, and it carries the user's kept settings the same way a
 * {@code --restricted} discussion turn does ({@link ClaudeUserSettings}), so sign-in, proxies and
 * Bedrock/Vertex keep working.
 */
public final class ClaudeOneShot {
    private static final String LABEL = "Claude";

    private final @Nullable Path executable;
    private final @Nullable Path userSettings;

    /** The {@code claude} on the user's PATH with their kept settings. */
    public ClaudeOneShot() {
        this(null, ClaudeUserSettings.defaultFile());
    }

    /**
     * @param executable   a fixed executable instead of a PATH lookup; tests point it at a fake CLI
     * @param userSettings the user-level settings file whose kept subset is passed on, or null for none
     */
    public ClaudeOneShot(@Nullable Path executable, @Nullable Path userSettings) {
        this.executable = executable;
        this.userSettings = userSettings;
    }

    /**
     * Starts the call on a pooled thread and returns at once.
     *
     * @param arguments everything after the executable except {@code --settings}, which is added here
     * @param stdin     written to the CLI's standard input, then closed
     * @param timeout   after this the call is cancelled and fails with {@link TimeoutException}
     */
    public Call start(Path workingDirectory, List<String> arguments, String stdin, Duration timeout) {
        CompletableFuture<JsonObject> output = new CompletableFuture<>();
        ResultParser parser = new ResultParser();
        CliRun run = new CliRun(LABEL, new OutputListener(output, parser), parser);
        TurnFiles files = new TurnFiles();
        run.terminated().whenComplete((ignored, error) -> files.deleteAll());
        Call call = new Call(run, output);

        ScheduledFuture<?> timer = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                () -> call.stop(new TimeoutException(LABEL + " didn't answer within " + timeout)),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
        output.whenComplete((ignored, error) -> timer.cancel(false));

        List<String> fixedArguments = List.copyOf(arguments);
        AppExecutorUtil.getAppExecutorService().execute(
                () -> run.run(() -> launch(workingDirectory, fixedArguments, files), stdin));
        return call;
    }

    private Process launch(Path workingDirectory, List<String> arguments, TurnFiles files) throws Exception {
        Map<String, String> environment = CliLauncher.environment();
        Path binary = CliLauncher.binary("claude", executable, workingDirectory, environment);
        List<String> all = new ArrayList<>(arguments);
        String kept = ClaudeUserSettings.discussionSubset(userSettings);
        if (kept != null) {
            all.add("--settings");
            all.add(files.createPrivate("consilium-claude-settings-", kept).toString());
        }
        return CliLauncher.start(binary, all, workingDirectory);
    }

    /** A call in flight. */
    public static final class Call {
        private final CliRun run;
        private final CompletableFuture<JsonObject> output;

        private Call(CliRun run, CompletableFuture<JsonObject> output) {
            this.run = run;
            this.output = output;
        }

        /**
         * The {@code structured_output} object of Claude's result. Fails when the CLI couldn't
         * start, exited with an error, reported {@code is_error}, answered without a structured
         * object, timed out, or was cancelled ({@link CancellationException}).
         */
        public CompletionStage<JsonObject> structuredOutput() {
            return output;
        }

        /** Completes once the CLI and everything it spawned are gone. */
        public CompletionStage<Void> terminated() {
            return run.terminated();
        }

        /** Stops the call without blocking; safe on the EDT. */
        public void cancel() {
            stop(new CancellationException("cancelled"));
        }

        private void stop(Throwable reason) {
            // Fail first so nobody waiting on the output also waits for the process tree to exit.
            output.completeExceptionally(reason);
            run.cancel();
        }
    }

    private static final class ResultParser implements CliEventParser {
        private volatile @Nullable JsonObject structuredOutput;

        @Override
        public @Nullable TurnOutcome handle(JsonObject event, AgentListener listener) {
            if (!"result".equals(Json.string(event, "type"))) return null;
            if (Json.isTrue(event, "is_error")) {
                String detail = Json.string(event, "result");
                return TurnOutcome.failure(detail != null && !detail.isBlank() ? detail : "the result reported an error");
            }
            structuredOutput = Json.object(event, "structured_output");
            return TurnOutcome.SUCCESS;
        }

        @Override
        public @Nullable String reportedError() {
            return null;
        }
    }

    private record OutputListener(CompletableFuture<JsonObject> output, ResultParser parser) implements AgentListener {
        @Override
        public void onSessionId(String sessionId) {
        }

        @Override
        public void onTextDelta(String text) {
        }

        @Override
        public void onCompleted() {
            JsonObject structured = parser.structuredOutput;
            if (structured != null) {
                output.complete(structured);
            } else {
                output.completeExceptionally(new IOException(LABEL + " answered without a structured result"));
            }
        }

        @Override
        public void onError(String message) {
            output.completeExceptionally(new IOException(message));
        }
    }
}
