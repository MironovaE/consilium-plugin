package dev.katherine.consilium.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeUserSettingsTest {
    @TempDir
    Path dir;

    @Test
    void keepsModelAuthAndEnvironmentButNeverHooksOrPermissions() throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, """
                {"model": "sonnet", "effortLevel": "high", "env": {"HTTPS_PROXY": "http://proxy:3128"},
                 "apiKeyHelper": "~/bin/key", "hooks": {"SessionStart": []},
                 "permissions": {"allow": ["Bash(*)"]}, "enabledPlugins": {"x": true}, "statusLine": {}}
                """);

        JsonObject kept = JsonParser.parseString(ClaudeUserSettings.discussionSubset(file)).getAsJsonObject();

        assertEquals("sonnet", kept.get("model").getAsString());
        assertEquals("high", kept.get("effortLevel").getAsString());
        assertEquals("~/bin/key", kept.get("apiKeyHelper").getAsString());
        assertTrue(kept.has("env"));
        assertFalse(kept.has("hooks"));
        assertFalse(kept.has("permissions"));
        assertFalse(kept.has("enabledPlugins"));
        assertFalse(kept.has("statusLine"));
    }

    @Test
    void nothingToKeepMeansNoFlag() throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"theme\": \"dark\", \"hooks\": {}}");
        assertNull(ClaudeUserSettings.discussionSubset(file));
    }

    @Test
    void aMissingOrBrokenFileMeansNoFlag() throws Exception {
        assertNull(ClaudeUserSettings.discussionSubset(dir.resolve("absent.json")));
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{not json");
        assertNull(ClaudeUserSettings.discussionSubset(broken));
        assertNull(ClaudeUserSettings.discussionSubset(null));
    }
}
