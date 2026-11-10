package dev.katherine.consilium.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

/** Lenient field access for CLI events, whose shape changes between CLI versions. */
final class Json {
    private Json() {
    }

    /** Parses a JSON object line; anything else (log lines, banners, arrays) is not an event. */
    static @Nullable JsonObject parseObject(String line) {
        // Gson's lenient parser would turn a plain log line into a string primitive.
        if (!line.stripLeading().startsWith("{")) return null;
        try {
            JsonElement element = JsonParser.parseString(line);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonParseException exception) {
            return null;
        }
    }

    /** The field as text if it is a JSON primitive; null for missing, null, objects and arrays. */
    static @Nullable String string(@Nullable JsonObject object, String field) {
        if (object == null) return null;
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    static @Nullable JsonObject object(@Nullable JsonObject object, String field) {
        if (object == null) return null;
        JsonElement value = object.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    static boolean isTrue(@Nullable JsonObject object, String field) {
        if (object == null) return false;
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
}
