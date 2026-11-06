package dev.katherine.consilium.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the CLI flags the plugin relies on. When a CLI upgrade breaks a turn,
 * re-check these lists against `claude --help` / `codex exec --help`.
 * Last checked: Claude Code 2.1.319, 2026-11-06.
 */
class CliCommandTest {
    private static final Path ROOT = Path.of("/project");

    private static List<String> args(List<String> command) {
        return command.subList(1, command.size());
    }

    @Test
    void claudeReadOnlyResumesWithReadTools() {
        List<String> command = args(new ClaudeCliAgent().command(ROOT, "s1", AgentAccess.READ_ONLY));
        assertEquals(List.of("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--tools", "Read,Glob,Grep", "--resume", "s1"), command);
    }

    @Test
    void claudeWriteStartsFreshInAutoMode() {
        List<String> command = args(new ClaudeCliAgent().command(ROOT, "s1", AgentAccess.WRITE));
        assertEquals(List.of("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--permission-mode", "auto"), command);
    }

    @Test
    void codexSandboxFollowsAccess() {
        assertTrue(new CodexCliAgent().command(ROOT, null, AgentAccess.READ_ONLY).contains("read-only"));
        assertTrue(new CodexCliAgent().command(ROOT, null, AgentAccess.WRITE).contains("workspace-write"));
    }

    @Test
    void flagHintOnlyForArgumentErrors() {
        assertFalse(AbstractCliAgent.flagHint("error: unknown option '--tools'").isEmpty());
        assertFalse(AbstractCliAgent.flagHint("error: unexpected argument '--sandbox' found").isEmpty());
        assertTrue(AbstractCliAgent.flagHint("Not logged in").isEmpty());
        assertTrue(AbstractCliAgent.flagHint("").isEmpty());
    }
}
