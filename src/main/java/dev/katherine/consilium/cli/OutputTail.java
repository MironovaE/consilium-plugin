package dev.katherine.consilium.cli;

/**
 * The most recent non-JSON output of a run, kept for the error message. Bounded because a CLI
 * that loops on a warning would otherwise grow the buffer for as long as the turn lasts.
 */
final class OutputTail {
    static final int DEFAULT_CAPACITY = 64 * 1024;

    private final int capacity;
    private final StringBuilder text = new StringBuilder();
    private boolean dropped;

    OutputTail(int capacity) {
        this.capacity = capacity;
    }

    synchronized void appendLine(String line) {
        text.append(line).append('\n');
        // Trimming only past twice the capacity keeps appends amortized O(1).
        if (text.length() > 2 * capacity) {
            text.delete(0, text.length() - capacity);
            dropped = true;
        }
    }

    /** The kept output, trimmed to the capacity; starts with an ellipsis when older output was dropped. */
    synchronized String text() {
        boolean cut = dropped || text.length() > capacity;
        String kept = text.length() > capacity ? text.substring(text.length() - capacity) : text.toString();
        return (cut ? "…" : "") + kept.strip();
    }
}
