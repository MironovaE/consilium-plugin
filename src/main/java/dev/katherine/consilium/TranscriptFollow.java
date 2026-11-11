package dev.katherine.consilium;

/** When the transcript should keep following new text: only while the reader is at its end. */
final class TranscriptFollow {
    private TranscriptFollow() {
    }

    /**
     * True when the visible area reaches the end of the content, give or take {@code slack} pixels
     * (a trackpad rarely stops exactly at the last pixel).
     */
    static boolean isAtEnd(int value, int extent, int maximum, int slack) {
        return value + extent >= maximum - slack;
    }
}
