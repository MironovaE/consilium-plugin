package dev.katherine.consilium.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the CLI flags the plugin relies on. When a CLI upgrade breaks a turn,
 * re-check these lists against `claude --help`, `codex exec --help` and `codex exec resume --help`.
 * Last checked: Claude Code 2.1.322 and codex-cli 0.167.0, 2026-11-10 (the read-only Claude flags
 * and both Codex forms with `--` were also run with a trivial prompt in an empty directory).
 */
class CliCommandTest {
    private static final Path ROOT = Path.of("/project");
    private static final List<String> CLAUDE_READ_ONLY = List.of("-p", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages", "--tools", "Read,Glob,Grep", "--restricted", "--strict-mcp-config",
            "--permission-prompts", "none");

    @Test
    void claudeReadOnlyResumesWithReadToolsOnly() {
        List<String> expected = new ArrayList<>(CLAUDE_READ_ONLY);
        expected.addAll(List.of("--resume", "s1"));
        assertEquals(expected, new ClaudeCliAgent(null, null).arguments(ROOT, "s1", AgentAccess.READ_ONLY));
    }

    @Test
    void claudeReadOnlyStartsFreshWithoutSession() {
        assertEquals(CLAUDE_READ_ONLY, new ClaudeCliAgent(null, null).arguments(ROOT, null, AgentAccess.READ_ONLY));
    }

    @Test
    void claudeWriteStartsFreshInAutoMode() {
        assertEquals(List.of("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--permission-mode", "auto"), new ClaudeCliAgent(null, null).arguments(ROOT, "s1", AgentAccess.WRITE));
    }

    @Test
    void codexFreshReadOnlyCannotEscalate() {
        assertEquals(List.of("exec", "--json", "--sandbox", "read-only", "-c", "approval_policy=\"never\"",
                "--cd", "/project", "--", "-"), new CodexCliAgent().arguments(ROOT, null, AgentAccess.READ_ONLY));
    }

    @Test
    void codexResumeForcesReadOnlySandbox() {
        assertEquals(List.of("exec", "resume", "--json", "-c", "sandbox_mode=\"read-only\"",
                "-c", "approval_policy=\"never\"", "--", "s1", "-"),
                new CodexCliAgent().arguments(ROOT, "s1", AgentAccess.READ_ONLY));
    }

    @Test
    void codexReadOnlySwitchesOffListedMcpServers() {
        CodexCliAgent agent = new CodexCliAgent();
        assertEquals(List.of("exec", "--json", "--sandbox", "read-only", "-c", "approval_policy=\"never\"",
                "-c", "mcp_servers.idea.enabled=false", "-c", "mcp_servers.mui-mcp.enabled=false",
                "--cd", "/project", "--", "-"),
                agent.arguments(ROOT, null, AgentAccess.READ_ONLY, List.of("idea", "mui-mcp")));
        assertEquals(List.of("exec", "resume", "--json", "-c", "sandbox_mode=\"read-only\"",
                "-c", "approval_policy=\"never\"", "-c", "mcp_servers.idea.enabled=false", "--", "s1", "-"),
                agent.arguments(ROOT, "s1", AgentAccess.READ_ONLY, List.of("idea")));
        assertEquals(agent.arguments(ROOT, null, AgentAccess.WRITE),
                agent.arguments(ROOT, null, AgentAccess.WRITE, List.of("idea")));
    }

    @Test
    void mcpListingKeepsOnlyEnabledBareKeyNames() throws TurnRefusedException {
        assertEquals(List.of("idea", "mui-mcp", "a_1"), CodexMcpServers.parse(
                "[{\"name\":\"idea\",\"enabled\":true,\"transport\":{}},{\"name\":\"off\",\"enabled\":false},"
                        + "{\"name\":\"mui-mcp\",\"enabled\":true},{\"name\":\"a_1\",\"enabled\":true}]"));
        assertEquals(List.of(), CodexMcpServers.parse("[]"));
        // A disabled server with an odd name needs no override, so it doesn't block the turn.
        assertEquals(List.of(), CodexMcpServers.parse("[{\"name\":\"a.b\",\"enabled\":false}]"));
        for (String bad : List.of("", "null", "{}", "[1]", "[{\"name\":\"a b\",\"enabled\":true}]",
                "[{\"name\":\"a.b\",\"enabled\":true}]", "[{\"name\":\"\\\"x\\\"\",\"enabled\":true}]",
                "[{\"name\":{},\"enabled\":true}]", "[{\"name\":\"x\",\"enabled\":\"yes\"}]")) {
            assertThrows(TurnRefusedException.class, () -> CodexMcpServers.parse(bad), bad);
        }
    }

    @Test
    void codexWriteStartsFreshInWorkspaceWrite() {
        assertEquals(List.of("exec", "--json", "--sandbox", "workspace-write", "--cd", "/project", "--", "-"),
                new CodexCliAgent().arguments(ROOT, "s1", AgentAccess.WRITE));
    }

    @Test
    void sessionIdThatLooksLikeAFlagStartsAFreshSession() {
        String hostile = "--dangerously-bypass-approvals-and-sandbox";
        assertEquals(CLAUDE_READ_ONLY, new ClaudeCliAgent(null, null).arguments(ROOT, hostile, AgentAccess.READ_ONLY));
        assertEquals(new CodexCliAgent().arguments(ROOT, null, AgentAccess.READ_ONLY),
                new CodexCliAgent().arguments(ROOT, hostile, AgentAccess.READ_ONLY));
    }

    @Test
    void sessionIdValidation() {
        assertEquals("550a32fe-6fae-434a-985d-97723b4f735d", SessionIds.resumable("550a32fe-6fae-434a-985d-97723b4f735d"));
        assertEquals("thread_1", SessionIds.resumable("thread_1"));
        assertNull(SessionIds.resumable(null));
        assertNull(SessionIds.resumable(" "));
        assertNull(SessionIds.resumable("-x"));
        assertNull(SessionIds.resumable("a b"));
        assertNull(SessionIds.resumable("a;rm -rf"));
        assertNull(SessionIds.resumable("a".repeat(129)));
    }

    @Test
    void flagHintOnlyForArgumentErrors() {
        assertFalse(AbstractCliAgent.flagHint("error: unknown option '--tools'").isEmpty());
        assertFalse(AbstractCliAgent.flagHint("error: unexpected argument '--sandbox' found").isEmpty());
        assertTrue(AbstractCliAgent.flagHint("Not logged in").isEmpty());
        assertTrue(AbstractCliAgent.flagHint("Invalid argument: API key missing").isEmpty());
        assertTrue(AbstractCliAgent.flagHint("unrecognized model name").isEmpty());
        assertTrue(AbstractCliAgent.flagHint("").isEmpty());
    }
}
