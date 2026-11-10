package dev.katherine.consilium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** У каждой строки интерфейса есть и английский, и русский вариант — новую не забудут перевести. */
class ConsiliumBundleTest {
    @Test
    void russianHasExactlyTheEnglishKeys() throws IOException {
        assertEquals(load("ConsiliumBundle.properties").stringPropertyNames(),
                load("ConsiliumBundle_ru.properties").stringPropertyNames());
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (var stream = ConsiliumBundleTest.class.getResourceAsStream("/messages/" + name)) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
