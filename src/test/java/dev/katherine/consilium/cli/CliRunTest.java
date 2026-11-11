package dev.katherine.consilium.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Runs the agents against fake CLIs: small shell scripts that play a scripted turn. */
@DisabledOnOs(OS.WINDOWS)
class CliRunTest {
    private static final String CLAUDE_RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\",\"session_id\":\"s-2\"}";
    private static final String CODEX_COMPLETED = "{\"type\":\"turn.completed\"}";

    @TempDir
    Path dir;

    /** Records every callback; {@link #settled} opens on the first completed/error. */
    private static final class Recorder implements AgentListener {
        final List<String> events = new ArrayList<>();
        final CountDownLatch settled = new CountDownLatch(1);
        final CountDownLatch firstDelta = new CountDownLatch(1);

        @Override public synchronized void onSessionId(String sessionId) { events.add("session:" + sessionId); }
        @Override public synchronized void onTextDelta(String text) { events.add("text:" + text); firstDelta.countDown(); }
        @Override public synchronized void onCompleted() { events.add("completed"); settled.countDown(); }
        @Override public synchronized void onError(String message) { events.add("error:" + message); settled.countDown(); }

        synchronized List<String> events() {
            return List.copyOf(events);
        }

        synchronized List<String> outcomes() {
            return events.stream().filter(event -> event.equals("completed") || event.startsWith("error:")).toList();
        }

        String awaitOutcome() throws InterruptedException {
            assertTrue(settled.await(15, TimeUnit.SECONDS), "no outcome; events: " + events());
            List<String> outcomes = outcomes();
            assertEquals(1, outcomes.size(), "exactly one outcome expected: " + events());
            return outcomes.getFirst();
        }
    }

    private Path script(String body) throws IOException {
        return script("echo '[]'", body);
    }

    /** A fake CLI; {@code mcpList} answers `codex mcp list --json`, which read-only Codex turns run first. */
    private Path script(String mcpList, String body) throws IOException {
        Path script = dir.resolve("fake-cli-" + System.nanoTime());
        Files.writeString(script, "#!/bin/sh\nif [ \"$1\" = mcp ]; then\n" + mcpList + "\nexit $?\nfi\n" + body + "\n");
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    // The MCP listing gets a generous budget: under load even a shell script can take a second to
    // start, and a timed-out listing refuses the turn, which a test expecting an answer would
    // misread as a failure.
    private static CodexCliAgent codex(Path cli) {
        return new CodexCliAgent(cli, Duration.ofSeconds(10));
    }

    private Path project() throws IOException {
        return Files.createDirectories(dir.resolve("project"));
    }

    private static void awaitTerminated(RunningRequest request) {
        try {
            request.terminated().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception exception) {
            fail("terminated() did not complete", exception);
        }
    }

    private long pid(String file) throws Exception {
        Path path = dir.resolve(file);
        for (int i = 0; i < 100 && !(Files.exists(path) && !Files.readString(path).isBlank()); i++) Thread.sleep(50);
        return Long.parseLong(Files.readString(path).strip());
    }

    private static void assertGone(long pid) throws InterruptedException {
        // A killed process may linger as a zombie for a moment until its parent reaps it.
        for (int i = 0; i < 100; i++) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) return;
            Thread.sleep(50);
        }
        fail("process " + pid + " is still alive");
    }

    @Test
    void discussionSettingsGoThroughAPrivateFileThatIsDeletedAfterTheTurn() throws Exception {
        Path settings = dir.resolve("user-settings.json");
        Files.writeString(settings, "{\"model\": \"haiku\", \"hooks\": {\"SessionStart\": []}}");
        Path seen = dir.resolve("seen");
        // The fake CLI records the path after --settings, the file's mode and its content.
        Path cli = script("cat > /dev/null\n"
                + "while [ $# -gt 0 ]; do if [ \"$1\" = --settings ]; then f=\"$2\"; fi; shift; done\n"
                + "echo \"$f\" > '" + seen + ".path'\n"
                + "stat -f %Lp \"$f\" > '" + seen + ".mode'\n"
                + "cat \"$f\" > '" + seen + ".json'\n"
                + "echo '" + CLAUDE_RESULT + "'");
        Recorder listener = new Recorder();
        RunningRequest request = new ClaudeCliAgent(cli, settings).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("completed", listener.awaitOutcome());
        awaitTerminated(request);
        assertEquals("600", Files.readString(Path.of(seen + ".mode")).strip());
        assertEquals("{\"model\":\"haiku\"}", Files.readString(Path.of(seen + ".json")).strip());
        Path passed = Path.of(Files.readString(Path.of(seen + ".path")).strip());
        assertFalse(Files.exists(passed), "the settings copy is deleted once the turn is over");
    }

    @Test
    void claudeTurnCompletesWithStreamedTextAndReceivesThePrompt() throws Exception {
        Path cli = script("cat > '" + dir.resolve("stdin") + "'\n"
                + "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\"}'\n"
                + "echo 'a warning that is not JSON'\n"
                + "echo '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello\"}}}'\n"
                + "echo '" + CLAUDE_RESULT + "'");
        Recorder listener = new Recorder();
        RunningRequest request = new ClaudeCliAgent(cli).start(project(), "Привет, prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("completed", listener.awaitOutcome());
        assertEquals(List.of("session:s-1", "text:Hello", "session:s-2", "completed"), listener.events());
        awaitTerminated(request);
        assertEquals("Привет, prompt", Files.readString(dir.resolve("stdin"), StandardCharsets.UTF_8));
    }

    @Test
    void nonZeroExitReportsTheCliOutput() throws Exception {
        Path cli = script("cat > /dev/null\necho 'Not logged in. Please run /login'\nexit 3");
        Recorder listener = new Recorder();
        RunningRequest request = new ClaudeCliAgent(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("error:Not logged in. Please run /login", listener.awaitOutcome());
        awaitTerminated(request);
    }

    @Test
    void earlyExitBeforeReadingStdinKeepsTheRealError() throws Exception {
        Path cli = script("echo \"error: unknown option '--restricted'\"\nexit 2");
        Recorder listener = new Recorder();
        // Far larger than a pipe buffer, so writing it fails once the CLI is gone.
        String prompt = "x".repeat(4 * 1024 * 1024);
        RunningRequest request = new ClaudeCliAgent(cli).start(project(), prompt, null, AgentAccess.READ_ONLY, listener);

        String outcome = listener.awaitOutcome();
        assertTrue(outcome.startsWith("error:error: unknown option '--restricted'"), outcome);
        assertTrue(outcome.contains("--help"), "flag hint expected: " + outcome);
        assertFalse(outcome.toLowerCase().contains("broken pipe"), outcome);
        awaitTerminated(request);
    }

    @Test
    void cancelBeforeTheProcessStartsReportsNothing() throws Exception {
        Path cli = script("echo $$ > '" + dir.resolve("root.pid") + "'\nsleep 1\necho '" + CLAUDE_RESULT + "'");
        Recorder listener = new Recorder();
        RunningRequest request = new ClaudeCliAgent(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);
        request.cancel();

        awaitTerminated(request);
        assertFalse(listener.settled.await(500, TimeUnit.MILLISECONDS));
        assertEquals(List.of(), listener.events());
        if (Files.exists(dir.resolve("root.pid"))) assertGone(pid("root.pid"));
    }

    @Test
    void cancelAfterStartKillsTheTreeAndReportsNothingMore() throws Exception {
        Path cli = script("echo $$ > '" + dir.resolve("root.pid") + "'\n"
                + "sleep 300 &\necho $! > '" + dir.resolve("child.pid") + "'\n"
                + "echo '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"partial\"}}}'\n"
                + "sleep 300");
        Recorder listener = new Recorder();
        RunningRequest request = new ClaudeCliAgent(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);
        assertTrue(listener.firstDelta.await(15, TimeUnit.SECONDS));
        long root = pid("root.pid");
        long child = pid("child.pid");

        request.cancel();
        awaitTerminated(request);
        assertGone(root);
        assertGone(child);
        assertFalse(listener.settled.await(500, TimeUnit.MILLISECONDS));
        assertEquals(List.of("text:partial"), listener.events());
    }

    @Test
    void endOfTurnEventCompletesWhileAGrandchildHoldsStdout() throws Exception {
        // The CLI announces the end of the turn, then waits on a child that inherited stdout:
        // end of stream never comes on its own.
        Path cli = script("cat > /dev/null\nsleep 300 &\necho $! > '" + dir.resolve("child.pid") + "'\n"
                + "echo '" + CODEX_COMPLETED + "'\nwait");
        Recorder listener = new Recorder();
        long started = System.nanoTime();
        RunningRequest request = codex(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("completed", listener.awaitOutcome());
        // The grandchild sleeps 300 s: completing well before that proves the end-of-turn event,
        // not end of stream, ended the turn. The bound is loose so a busy CI runner can't flake it.
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 60,
                "completion must not wait for the grandchild");
        awaitTerminated(request);
        assertGone(pid("child.pid"));
    }

    @Test
    void orphanedGrandchildIsReapedAfterTheCliExits() throws Exception {
        Path cli = script("cat > /dev/null\nsleep 300 &\necho $! > '" + dir.resolve("child.pid") + "'\n"
                + "sleep 1\necho '" + CLAUDE_RESULT + "'\nexit 0");
        Recorder listener = new Recorder();
        RunningRequest request = new ClaudeCliAgent(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("completed", listener.awaitOutcome());
        awaitTerminated(request);
        assertGone(pid("child.pid"));
    }

    @Test
    void crashWithoutEndOfTurnWhileAGrandchildHoldsStdoutStillFails() throws Exception {
        Path cli = script("cat > /dev/null\nsleep 300 &\necho $! > '" + dir.resolve("child.pid") + "'\n"
                + "sleep 1\necho 'panic: something broke'\nexit 101");
        Recorder listener = new Recorder();
        RunningRequest request = codex(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("error:panic: something broke", listener.awaitOutcome());
        awaitTerminated(request);
        assertGone(pid("child.pid"));
    }

    @Test
    void claudeErrorResultIsAnErrorNotAnAnswer() throws Exception {
        Path cli = script("cat > /dev/null\n"
                + "echo '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":404,"
                + "\"result\":\"There is an issue with the selected model.\"}'\nexit 1");
        Recorder listener = new Recorder();
        RunningRequest request = new ClaudeCliAgent(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("error:There is an issue with the selected model.", listener.awaitOutcome());
        assertEquals(1, listener.events().size(), "no answer text expected: " + listener.events());
        awaitTerminated(request);
    }

    @Test
    void codexTurnFailedReportsItsMessage() throws Exception {
        Path cli = script("cat > /dev/null\n"
                + "echo '{\"type\":\"thread.started\",\"thread_id\":\"t-1\"}'\n"
                + "echo '{\"type\":\"error\",\"message\":\"Reconnecting... 1/5\"}'\n"
                + "echo '{\"type\":\"turn.failed\",\"error\":{\"message\":\"The model is not supported.\"}}'\nexit 1");
        Recorder listener = new Recorder();
        RunningRequest request = codex(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("error:The model is not supported.", listener.awaitOutcome());
        assertEquals(List.of("session:t-1", "error:The model is not supported."), listener.events());
        awaitTerminated(request);
    }

    @Test
    void codexErrorEventExplainsAFailedExit() throws Exception {
        Path cli = script("cat > /dev/null\necho 'some log line'\n"
                + "echo '{\"type\":\"error\",\"message\":\"stream disconnected\"}'\nexit 1");
        Recorder listener = new Recorder();
        RunningRequest request = codex(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

        assertEquals("error:stream disconnected", listener.awaitOutcome());
        awaitTerminated(request);
    }

    @Test
    void invalidSessionIdStartsAFreshSession() throws Exception {
        Path args = dir.resolve("args");
        Path cli = script("cat > /dev/null\nprintf '%s\\n' \"$@\" > '" + args + "'\necho '" + CODEX_COMPLETED + "'");
        Recorder listener = new Recorder();
        RunningRequest request = codex(cli).start(project(), "prompt",
                "--dangerously-bypass-approvals-and-sandbox", AgentAccess.READ_ONLY, listener);

        assertEquals("completed", listener.awaitOutcome());
        awaitTerminated(request);
        List<String> received = Files.readAllLines(args);
        assertFalse(received.contains("--dangerously-bypass-approvals-and-sandbox"), received.toString());
        assertFalse(received.contains("resume"), received.toString());
        assertEquals(CodexCliAgent.arguments(project(), null, AgentAccess.READ_ONLY, List.of()), received);
    }

    @Test
    void missingExecutableFailsAndTerminatesImmediately() throws Exception {
        Recorder listener = new Recorder();
        RunningRequest request = new ClaudeCliAgent(dir.resolve("no-such-cli")).start(project(), "prompt", null,
                AgentAccess.READ_ONLY, listener);

        assertTrue(listener.awaitOutcome().startsWith("error:"));
        awaitTerminated(request);
    }

    @Test
    void codexReadOnlyTurnSwitchesOffEveryEnabledMcpServer() throws Exception {
        Path args = dir.resolve("args");
        String listing = "[{\"name\":\"idea\",\"enabled\":true},{\"name\":\"off\",\"enabled\":false},"
                + "{\"name\":\"mui-mcp\",\"enabled\":true}]";
        Path cli = script("pwd -P > '" + dir.resolve("mcp.cwd") + "'\necho '" + listing + "'",
                "cat > /dev/null\nprintf '%s\\n' \"$@\" > '" + args + "'\necho '" + CODEX_COMPLETED + "'");
        Recorder listener = new Recorder();
        RunningRequest request = codex(cli).start(project(), "prompt", "s1", AgentAccess.READ_ONLY, listener);

        assertEquals("completed", listener.awaitOutcome());
        awaitTerminated(request);
        assertEquals(CodexCliAgent.arguments(project(), "s1", AgentAccess.READ_ONLY, List.of("idea", "mui-mcp")),
                Files.readAllLines(args));
        // Project config can add servers, so the listing must run where the turn runs.
        assertEquals(project().toRealPath().toString(), Files.readString(dir.resolve("mcp.cwd")).strip());
    }

    @Test
    void codexWriteTurnDoesNotListMcpServers() throws Exception {
        Path cli = script("touch '" + dir.resolve("listed") + "'\necho '[]'", "cat > /dev/null\necho '" + CODEX_COMPLETED + "'");
        Recorder listener = new Recorder();
        RunningRequest request = codex(cli).start(project(), "prompt", null, AgentAccess.WRITE, listener);

        assertEquals("completed", listener.awaitOutcome());
        awaitTerminated(request);
        assertFalse(Files.exists(dir.resolve("listed")));
    }

    @Test
    void stopWhileCodexListsMcpServersEndsTheTurnPromptly() throws Exception {
        Path ran = dir.resolve("ran");
        Path cli = script("exec sleep 30", "touch '" + ran + "'\necho '" + CODEX_COMPLETED + "'");
        Recorder listener = new Recorder();
        RunningRequest request = codex(cli).start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);
        Thread.sleep(300);
        long stopped = System.nanoTime();

        request.cancel();
        awaitTerminated(request);

        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stopped) < 5,
                "Stop must not wait for the 10 s listing timeout");
        assertFalse(Files.exists(ran), "the turn must not start after Stop");
        assertEquals(List.of(), listener.events());
    }

    @Test
    void codexReadOnlyTurnIsRefusedWhenMcpServersCannotBeListed() throws Exception {
        String[] listings = {
                "echo 'Error: failed to load configuration' >&2\nexit 1",
                "echo 'not json'",
                "echo '{\"servers\":[]}'",
                "echo '[{\"name\":\"a.b\",\"enabled\":true}]'",
                "echo '[{\"name\":\"q\\\"x\",\"enabled\":true}]'",
                "echo '[{\"name\":\"idea\"}]'",
                "exec sleep 30",
        };
        for (String listing : listings) {
            Path ran = dir.resolve("ran");
            Path cli = script(listing, "touch '" + ran + "'\necho '" + CODEX_COMPLETED + "'");
            Recorder listener = new Recorder();
            // A short timeout keeps the hanging case fast; every other case is refused either way.
            RunningRequest request = new CodexCliAgent(cli, Duration.ofSeconds(1))
                    .start(project(), "prompt", null, AgentAccess.READ_ONLY, listener);

            String outcome = listener.awaitOutcome();
            assertTrue(outcome.startsWith("error:Consilium couldn't list the Codex MCP servers"), listing + " -> " + outcome);
            awaitTerminated(request);
            assertFalse(Files.exists(ran), "the turn must not start: " + listing);
        }
    }

    @Test
    void outputTailIsBounded() {
        OutputTail tail = new OutputTail(10);
        for (int i = 0; i < 100; i++) tail.appendLine("line " + i);
        String text = tail.text();
        assertTrue(text.startsWith("…"), text);
        assertTrue(text.endsWith("line 99"), text);
        assertTrue(text.length() <= 11, text);
        assertNull(Json.parseObject("plain text"));
    }
}
