package dev.katherine.consilium.process;

import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Waits for a short-lived helper process (git, {@code codex mcp list}) with a deadline and bounded
 * output. Both pipes are drained concurrently, so a long listing can't fill one and stall the
 * process; output past the limit is read and dropped rather than left in the pipe. Whatever happens,
 * the process and its descendants are gone when {@link #await} returns or throws.
 */
public final class BoundedProcess {
    /** How often the deadline and the caller's cancellation are checked while the process runs. */
    private static final long POLL_MILLIS = 100;

    private BoundedProcess() {
    }

    /** What a finished process printed, each stream cut at its limit. */
    public record Result(int exitCode, byte[] output, byte[] error) {
    }

    /** Why a process gave no result; callers turn the kind into their own message. */
    public static final class Failure extends Exception {
        public enum Kind {
            /** The deadline passed; the process was killed. */
            TIMED_OUT,
            /** The caller cancelled; the process was killed. */
            CANCELLED,
            /** The waiting thread was interrupted; its interrupt flag is set again. */
            INTERRUPTED,
            /** A pipe couldn't be closed or read. */
            UNREADABLE
        }

        private final Kind kind;

        Failure(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /**
     * Closes the process's stdin, waits until it exits, the timeout passes or {@code cancelled}
     * turns true, and returns what it printed.
     *
     * @param outputLimit bytes of stdout kept
     * @param errorLimit  bytes of stderr kept
     * @param cancelled   polled every {@value #POLL_MILLIS} ms, so Stop doesn't wait out the timeout
     */
    public static Result await(Process process, Duration timeout, int outputLimit, int errorLimit,
                               BooleanSupplier cancelled) throws Failure {
        try {
            process.getOutputStream().close();
            CompletableFuture<byte[]> stdout = readAsync(process.getInputStream(), outputLimit);
            CompletableFuture<byte[]> stderr = readAsync(process.getErrorStream(), errorLimit);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw new Failure(Failure.Kind.CANCELLED, "cancelled", null);
                if (System.nanoTime() - deadline > 0) {
                    throw new Failure(Failure.Kind.TIMED_OUT, "did not finish in " + timeout.toSeconds() + " s", null);
                }
            }
            // The process is gone, so both readers end as soon as the pipes are empty.
            byte[] output = stdout.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            byte[] error = stderr.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Result(process.exitValue(), output, error);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Failure(Failure.Kind.INTERRUPTED, "interrupted", exception);
        } catch (IOException | ExecutionException | TimeoutException exception) {
            Throwable cause = exception;
            // A reader's IOException arrives wrapped twice: in the future, then in UncheckedIOException.
            while ((cause instanceof ExecutionException || cause instanceof UncheckedIOException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw new Failure(Failure.Kind.UNREADABLE, String.valueOf(cause.getMessage()), cause);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    /** Keeps the first {@code limit} bytes and drains the rest, so the writer never blocks. */
    private static CompletableFuture<byte[]> readAsync(InputStream stream, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                byte[] head = stream.readNBytes(limit);
                stream.transferTo(OutputStream.nullOutputStream());
                return head;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }
}
