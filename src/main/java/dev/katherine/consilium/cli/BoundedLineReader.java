package dev.katherine.consilium.cli;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/**
 * Line reader with a length cap. {@link java.io.BufferedReader#readLine()} buffers a line of any
 * length, so a CLI printing a huge line without a newline could exhaust the IDE heap.
 */
final class BoundedLineReader implements Closeable {
    // Far above any real event: tool results are truncated by the CLIs long before this.
    static final int DEFAULT_MAX_LINE = 8 * 1024 * 1024;

    private final Reader in;
    private final int maxLine;
    private final char[] buffer = new char[8192];
    private int position;
    private int limit;
    private boolean truncated;

    BoundedLineReader(Reader in, int maxLine) {
        this.in = in;
        this.maxLine = maxLine;
    }

    /**
     * Returns the next line without its terminator, or null at end of stream. A line longer than
     * the cap is returned cut to the cap, the rest is skipped and {@link #lastLineTruncated()} is set.
     */
    @Nullable String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        truncated = false;
        boolean sawAny = false;
        while (true) {
            if (position == limit) {
                limit = in.read(buffer, 0, buffer.length);
                position = 0;
                if (limit <= 0) {
                    limit = 0;
                    return sawAny ? finish(line) : null;
                }
            }
            sawAny = true;
            int start = position;
            while (position < limit && buffer[position] != '\n') position++;
            int room = maxLine - line.length();
            int chunk = position - start;
            if (chunk > room) {
                line.append(buffer, start, Math.max(room, 0));
                truncated = true;
            } else {
                line.append(buffer, start, chunk);
            }
            if (position < limit) {
                position++;
                return finish(line);
            }
        }
    }

    boolean lastLineTruncated() {
        return truncated;
    }

    private static String finish(StringBuilder line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') end--;
        return line.substring(0, end);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
