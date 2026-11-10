package dev.katherine.consilium.cli;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Session ids come back from workspace.xml, which lives in the project and can be edited by
 * anyone who can commit to it. They end up in argv, so a value such as
 * {@code --dangerously-bypass-approvals-and-sandbox} must never reach the CLI as a flag.
 */
public final class SessionIds {
    private static final Logger LOG = Logger.getInstance(SessionIds.class);
    // Both CLIs hand out UUIDs; the looser pattern tolerates format changes, never a leading dash.
    private static final Pattern VALID = Pattern.compile("[0-9A-Za-z][0-9A-Za-z_-]{0,127}");

    private SessionIds() {
    }

    /** The id if it is safe to resume; null means "start a fresh session" rather than failing the turn. */
    public static @Nullable String resumable(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (VALID.matcher(sessionId).matches()) return sessionId;
        LOG.warn("Ignoring a stored session id that is not a plain id; starting a fresh session");
        return null;
    }
}
