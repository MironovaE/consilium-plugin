package dev.katherine.consilium.intent;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/** Reads what the person's message asks for. Never completes exceptionally: on any failure it yields {@link Intent#discussWithBoth()}. */
public interface IntentClassifier {
    /**
     * @param userMessage only the person's message; never pass agent text here
     * @param projectRoot working directory for any process the classifier starts
     */
    CompletionStage<Intent> classify(String userMessage, Path projectRoot);

    /** Stops a classification in flight; the stage then completes with {@link Intent#discussWithBoth()}. */
    default void cancel() {
    }
}
