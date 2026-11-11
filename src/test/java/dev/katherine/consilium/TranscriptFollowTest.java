package dev.katherine.consilium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptFollowTest {
    @Test
    void followsAtTheEndAndWithinSlack() {
        assertTrue(TranscriptFollow.isAtEnd(700, 300, 1000, 24));
        assertTrue(TranscriptFollow.isAtEnd(680, 300, 1000, 24));
    }

    @Test
    void stopsFollowingOnceTheReaderScrolledUp() {
        assertFalse(TranscriptFollow.isAtEnd(500, 300, 1000, 24));
    }

    @Test
    void followsWhenEverythingFits() {
        assertTrue(TranscriptFollow.isAtEnd(0, 300, 300, 24));
    }
}
