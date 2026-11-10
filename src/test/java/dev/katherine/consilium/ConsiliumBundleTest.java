package dev.katherine.consilium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every interface string exists in English and Russian, so a new one can't ship untranslated. */
class ConsiliumBundleTest {
    @Test
    void russianHasExactlyTheEnglishKeys() throws IOException {
        assertEquals(load("ConsiliumBundle.properties").stringPropertyNames(),
                load("ConsiliumBundle_ru.properties").stringPropertyNames());
    }

    @Test
    void apostrophesSurviveWithAndWithoutParameters() {
        // MessageFormat only runs when there are parameters: '' is right there and wrong without.
        assertEquals("Both parts are merged; the agents' copies are removed.", ConsiliumBundle.message("notice.merge.cleaned"));
        assertEquals("Couldn't prepare separate copies for the agents: x", ConsiliumBundle.message("notice.parallel.failed", "x"));
        String part = ConsiliumBundle.message("prompt.part", "Codex", "consilium/claude-1", "Claude");
        assertTrue(part.contains("don't touch the other agent's part"), part);
        assertTrue(part.contains("on branch consilium/claude-1"), part);
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (var stream = ConsiliumBundleTest.class.getResourceAsStream("/messages/" + name)) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
