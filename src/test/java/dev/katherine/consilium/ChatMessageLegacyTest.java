package dev.katherine.consilium;

import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.Speaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessageLegacyTest {
    @Test
    void transcriptsBefore030StillLoadTheHuman() {
        ChatMessage stored = new ChatMessage();
        stored.speaker = "KATHERINE";
        assertEquals(Speaker.USER, stored.speakerValue());
    }
}
