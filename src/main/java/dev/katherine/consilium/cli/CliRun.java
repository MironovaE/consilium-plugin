package dev.katherine.consilium.cli;

import dev.katherine.consilium.ConsiliumBundle;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * One CLI turn: starts the process, feeds the prompt, parses events, and guarantees that the
 * listener hears exactly one of completed/error (none after {@link #cancel()}) and that the process
 * tree is gone on every path, including exceptions. {@link #terminated()} completes only after the
 * outcome was delivered (or the turn was cancelled), so a caller that waits for it never sees a
 * late answer after it has moved on.
 */
final class CliRun implements RunningRequest {
    /** How long a CLI may linger after its end-of-turn event before its tree is stopped. */
    static final Duration ROOT_EXIT_GRACE = Duration.ofSeconds(3);
    // If the tree is gone but the reader is still blocked (a process escaped the tree and holds
    // stdout), the turn is settled from the exit code after this pause, so the card can't hang.
    private static final Duration STUCK_READER_GRACE = Duration.ofSeconds(2);
    private static final Logger LOG = Logger.getInstance(CliRun.class);

    @FunctionalInterface
    interface Launcher {
        Process launch() throws Exception;
    }

    private final String label;
    private final AgentListener listener;
    private final CliEventParser parser;
    private final OutputTail tail = new OutputTail(OutputTail.DEFAULT_CAPACITY);
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();

    // Delivery and settling share one lock so that cancel() returning means "no more callbacks",
    // not "no callbacks that start later".
    private final Object deliveryLock = new Object();
    private boolean settled; // guarded by deliveryLock

    private volatile boolean cancelled;
    private volatile @Nullable ProcessTree tree;

    private final AgentListener guardedListener = new AgentListener() {
        @Override
        public void onSessionId(String sessionId) {
            synchronized (deliveryLock) {
                if (!settled) listener.onSessionId(sessionId);
            }
        }

        @Override
        public void onTextDelta(String text) {
            synchronized (deliveryLock) {
                if (!settled) listener.onTextDelta(text);
            }
        }

        @Override
        public void onCompleted() {
            settle(null);
        }

        @Override
        public void onError(String message) {
            settle(message);
        }
    };

    CliRun(String label, AgentListener listener, CliEventParser parser) {
        this.label = label;
        this.listener = listener;
        this.parser = parser;
    }

    @Override
    public void cancel() {
        cancelled = true;
        synchronized (deliveryLock) {
            settled = true;
        }
        ProcessTree current = tree;
        if (current != null) current.stop();
        // Otherwise run() hasn't published the tree yet and will see the flag right after it does.
    }

    @Override
    public CompletionStage<Void> terminated() {
        return terminated;
    }

    /** Runs the turn on the calling (pooled) thread; returns when the output is consumed. */
    void run(Launcher launcher, String prompt) {
        if (cancelled) {
            terminated.complete(null);
            return;
        }
        Process process;
        try {
            process = launcher.launch();
        } catch (TurnRefusedException exception) {
            settle(exception.getMessage());
            terminated.complete(null);
            return;
        } catch (Exception exception) {
            settle(ConsiliumBundle.message("error.launch", label, messageOf(exception)));
            terminated.complete(null);
            return;
        }

        ProcessTree processTree = new ProcessTree(process);
        processTree.gone().whenComplete((ignored, error) -> awaitOutcomeThenTerminate(process, System.nanoTime()));
        tree = processTree;
        if (cancelled) {
            processTree.stop();
            return;
        }

        try {
            writePromptAsync(process, prompt);
            if (!readUntilEndOfTurn(process)) {
                // End of stream without an end-of-turn event: the CLI is exiting (or closed stdout
                // and hangs, which the grace period handles), so the exit code decides the outcome.
                processTree.stopAfter(ROOT_EXIT_GRACE);
                process.waitFor();
                settleFromExit(process);
            }
        } catch (IOException | RuntimeException exception) {
            processTree.stop();
            if (!cancelled) settle(label + ": " + messageOf(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            processTree.stop();
            settle(ConsiliumBundle.message("error.interrupted", label));
        } finally {
            // Every path ends here, an Error included: the CLI gets a moment to exit on its own
            // after its end-of-turn event, then whatever is left of the tree is stopped.
            processTree.stopAfter(ROOT_EXIT_GRACE);
        }
    }

    /**
     * Reads events until the CLI's own end-of-turn event (returns true) or end of stream (false).
     * Stopping at the event matters: a grandchild that inherited stdout keeps the stream open
     * long after the CLI is done, so waiting for end of stream could wait forever.
     */
    private boolean readUntilEndOfTurn(Process process) throws IOException {
        ProcessTree processTree = tree;
        try (BoundedLineReader reader = new BoundedLineReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), BoundedLineReader.DEFAULT_MAX_LINE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled) return true;
                JsonObject event = reader.lastLineTruncated() ? null : Json.parseObject(line);
                if (event == null) {
                    if (!line.isBlank()) tail.appendLine(reader.lastLineTruncated() ? line + "…" : line);
                    continue;
                }
                CliEventParser.TurnOutcome outcome = parser.handle(event, guardedListener);
                if (outcome != null) {
                    // The CLI is still alive here, so this is the last chance to see its children.
                    if (processTree != null) processTree.recordDescendants();
                    settle(outcome.error());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Writes the prompt on its own thread. Written inline before reading, a CLI that exits early
     * (an unknown flag) turns the write into "Broken pipe" that hides its real message, and a
     * prompt larger than the pipe buffer deadlocks against a CLI that prints before reading stdin.
     */
    private static void writePromptAsync(Process process, String prompt) {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(prompt);
            } catch (IOException exception) {
                // The CLI stopped reading; its output and exit code tell why, so this is noise.
                LOG.debug("CLI closed stdin before the prompt was written", exception);
            }
        });
    }

    private void settleFromExit(Process process) {
        if (process.isAlive()) return;
        int exitCode = process.exitValue();
        if (exitCode == 0) {
            settle(null);
            return;
        }
        String output = tail.text();
        String reported = parser.reportedError();
        String detail = reported != null ? reported : output;
        String message = detail.isEmpty() ? ConsiliumBundle.message("error.exit.code", label, exitCode) : detail;
        settle(message + AbstractCliAgent.flagHint(output));
    }

    /**
     * Completes {@link #terminated()} once the outcome is delivered. The reader usually settles the
     * turn within moments of the exit; if it doesn't (a process that escaped the tree still holds
     * stdout), the exit code decides after {@link #STUCK_READER_GRACE} and the stream is closed so
     * the reader thread isn't left blocked.
     */
    private void awaitOutcomeThenTerminate(Process process, long goneAt) {
        if (isSettled()) {
            terminated.complete(null);
            return;
        }
        if (System.nanoTime() - goneAt >= STUCK_READER_GRACE.toNanos()) {
            try {
                settleFromExit(process);
                closeQuietly(process);
            } finally {
                terminated.complete(null);
            }
            return;
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
                () -> awaitOutcomeThenTerminate(process, goneAt), 50, TimeUnit.MILLISECONDS);
    }

    private boolean isSettled() {
        synchronized (deliveryLock) {
            return settled;
        }
    }

    private static void closeQuietly(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException exception) {
            LOG.debug("Couldn't close the CLI's output", exception);
        }
    }

    private void settle(@Nullable String error) {
        synchronized (deliveryLock) {
            if (settled) return;
            settled = true;
            if (error == null) {
                listener.onCompleted();
            } else {
                listener.onError(error);
            }
        }
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
