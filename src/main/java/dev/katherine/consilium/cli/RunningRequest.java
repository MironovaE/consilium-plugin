package dev.katherine.consilium.cli;

import java.util.concurrent.CompletionStage;

/** A CLI turn in flight. */
public interface RunningRequest {
    /**
     * Stops the turn: the listener gets no further calls once this returns, and the CLI process
     * tree is asked to exit. Never blocks on the process, so it is safe to call on the EDT; it only
     * waits for a listener callback that is already running, which is why callbacks must not block.
     */
    void cancel();

    /**
     * Completes normally once the CLI process and all its descendants are gone, whether the turn
     * finished, failed or was cancelled. Completes immediately if the process never started.
     */
    CompletionStage<Void> terminated();
}
