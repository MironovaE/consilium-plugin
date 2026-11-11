package dev.katherine.consilium.cli;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.EnvironmentUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The part of the user's own Claude settings that discussion turns keep.
 *
 * <p>Discussion turns run with {@code --restricted}, which ignores every settings file so that a
 * cloned repository's hooks and allow rules can't run in a read-only turn. That also drops the
 * user's model, auth helper and environment (proxy, Bedrock, base URL), so discussion would use a
 * different model than "does" turns, or fail to sign in. These keys are read from the user-level
 * file only (never the project's) and passed back with {@code --settings}, which
 * {@code --restricted} still honours (checked with Claude Code 2.1.322). Hooks, permissions,
 * plugins and the status line stay out.
 */
final class ClaudeUserSettings {
    private static final Logger LOG = Logger.getInstance(ClaudeUserSettings.class);
    static final List<String> KEPT_KEYS = List.of(
            "model", "effortLevel", "alwaysThinkingEnabled", "env",
            "apiKeyHelper", "awsAuthRefresh", "awsCredentialExport", "forceLoginMethod");

    private ClaudeUserSettings() {
    }

    /** {@code $CLAUDE_CONFIG_DIR/settings.json}, or {@code ~/.claude/settings.json}. */
    static Path defaultFile() {
        String configDir = EnvironmentUtil.getValue("CLAUDE_CONFIG_DIR");
        Path dir = configDir != null && !configDir.isBlank()
                ? Path.of(configDir)
                : Path.of(System.getProperty("user.home"), ".claude");
        return dir.resolve("settings.json");
    }

    /**
     * Writes the kept keys to a private turn file for {@code --settings}, or returns null when there
     * is nothing to keep. A file rather than inline JSON because {@code env} may hold keys and argv
     * is visible to every local user in {@code ps}.
     */
    static @Nullable Path keptSubsetFile(@Nullable Path settingsFile, TurnFiles files) throws IOException {
        String kept = discussionSubset(settingsFile);
        return kept != null ? files.createPrivate("consilium-claude-settings-", kept) : null;
    }

    /** {@code --settings <file>}, or nothing when there is no file. */
    static List<String> settingsFlag(@Nullable Path keptSubsetFile) {
        return keptSubsetFile != null ? List.of("--settings", keptSubsetFile.toString()) : List.of();
    }

    /** The kept keys as a JSON object string, or null when there is nothing to keep. */
    static @Nullable String discussionSubset(@Nullable Path settingsFile) {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) return null;
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(settingsFile, StandardCharsets.UTF_8));
        } catch (IOException | JsonParseException exception) {
            // Claude itself reports a broken settings file; the turn just runs with defaults.
            LOG.warn("Couldn't read Claude settings at " + settingsFile, exception);
            return null;
        }
        if (!parsed.isJsonObject()) return null;
        JsonObject all = parsed.getAsJsonObject();
        JsonObject kept = new JsonObject();
        for (String key : KEPT_KEYS) {
            JsonElement value = all.get(key);
            if (value != null && !value.isJsonNull()) kept.add(key, value);
        }
        return kept.isEmpty() ? null : new Gson().toJson(kept);
    }
}
