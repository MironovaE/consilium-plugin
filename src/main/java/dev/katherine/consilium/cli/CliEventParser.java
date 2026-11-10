package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Turns one run's JSON events into listener calls. A new parser is created for every run, so
 * per-turn state (whether text was already emitted) cannot leak between runs or agents.
 */
interface CliEventParser {
    /**
     * Handles one event, forwarding session ids and text to the listener. Returns the outcome when
     * the event is the CLI's own end-of-turn marker, otherwise null. Never calls
     * {@link AgentListener#onCompleted()} or {@link AgentListener#onError(String)}: the run owns
     * that decision because it also has to account for the exit code.
     */
    @Nullable TurnOutcome handle(JsonObject event, AgentListener listener);

    /** The last error the CLI reported as JSON without ending the turn, used for a failed exit. */
    @Nullable String reportedError();

    /** How a turn ended according to the CLI; {@code error} is null on success. */
    record TurnOutcome(@Nullable String error) {
        static final TurnOutcome SUCCESS = new TurnOutcome(null);

        static TurnOutcome failure(String error) {
            return new TurnOutcome(error);
        }
    }
}
