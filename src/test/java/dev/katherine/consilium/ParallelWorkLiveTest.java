package dev.katherine.consilium;

import dev.katherine.consilium.cli.ClaudeCliAgent;
import dev.katherine.consilium.cli.CodexCliAgent;
import dev.katherine.consilium.intent.ClaudeIntentClassifier;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.Speaker;
import dev.katherine.consilium.work.GitCliWorktrees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The whole parallel flow against the real CLIs, in a throwaway git repository: the agents agree on
 * a split, the person says "go ahead", each does its part in its own worktree, Claude merges both.
 * A developer step, not part of the normal suite: it costs real model calls and takes minutes.
 *
 * <pre>CONSILIUM_LIVE_E2E=1 ./gradlew test --tests '*ParallelWorkLiveTest' --rerun</pre>
 *
 * The repository lives under ~/Library/Caches, not /tmp: Codex's sandbox allows writes to /tmp
 * anyway, which would hide a missing writable directory.
 */
@EnabledIfEnvironmentVariable(named = "CONSILIUM_LIVE_E2E", matches = "1")
class ParallelWorkLiveTest {
    private static final long TURN_TIMEOUT_MINUTES = 15;

    private static final class Recorder implements ChatSession.View {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        volatile ChatSession.State state = ChatSession.State.READY;

        @Override
        public void messageAdded(ChatMessage message) {
            log.add(message.speakerValue().label() + ": " + message.textValue());
        }

        @Override
        public ChatSession.AnswerView answerStarted(Speaker speaker) {
            return new ChatSession.AnswerView() {
                @Override
                public void preview(String markdown) {
                }

                @Override
                public void completed(String markdown) {
                    log.add(speaker.label() + ": " + markdown);
                }

                @Override
                public void failed(String detail) {
                    log.add(speaker.label() + " FAILED: " + detail);
                }

                @Override
                public void stopped(String partial) {
                    log.add(speaker.label() + " STOPPED: " + partial);
                }
            };
        }

        @Override
        public void stateChanged(ChatSession.State state) {
            this.state = state;
        }

        @Override
        public void transcriptCleared() {
        }
    }

    @Test
    void agentsAgreeSplitWorkInParallelAndClaudeMerges() throws Exception {
        Path home = Path.of(System.getProperty("user.home"), "Library", "Caches", "consilium-e2e-" + System.nanoTime());
        Path repo = Files.createDirectories(home.resolve("repo"));
        Path worktrees = Files.createDirectories(home.resolve("worktrees"));
        ExecutorService ui = Executors.newSingleThreadExecutor();
        Recorder view = new Recorder();
        try {
            initRepository(repo);
            ConsiliumStateStore store = new ConsiliumStateStore(new ConsiliumStateStoreTest.MemoryPersistence());
            ChatSession session = new ChatSession(store,
                    speaker -> speaker == Speaker.CLAUDE ? new ClaudeCliAgent() : new CodexCliAgent(),
                    new ClaudeIntentClassifier(), new ParallelWork(new GitCliWorktrees(worktrees)),
                    repo, ui, command -> new Thread(command).start(), view);

            say(ui, session, view, "В репозитории два файла: calc_add.py и calc_mul.py. Нужно добавить функцию add(a, b) "
                    + "в calc_add.py и функцию mul(a, b) в calc_mul.py. Договоритесь коротко, в двух строках: "
                    + "Claude берёт calc_add.py, Codex берёт calc_mul.py. Ничего пока не меняйте.");
            say(ui, session, view, "делайте");

            String added = Files.readString(repo.resolve("calc_add.py"), StandardCharsets.UTF_8);
            String multiplied = Files.readString(repo.resolve("calc_mul.py"), StandardCharsets.UTF_8);
            String transcript = String.join("\n\n", view.log);
            assertTrue(added.contains("def add"), "Claude's part reached the project\n" + transcript);
            assertTrue(multiplied.contains("def mul"), "Codex's part reached the project\n" + transcript);
            assertTrue(store.pendingWork().isEmpty(), "the copies are gone\n" + transcript);
            try (Stream<Path> left = Files.list(worktrees)) {
                assertEquals(0, left.flatMap(ParallelWorkLiveTest::children).count(), "no worktree directory left\n" + transcript);
            }
            System.out.println(transcript);
        } finally {
            ui.shutdownNow();
            deleteRecursively(home);
        }
    }

    private static void say(ExecutorService ui, ChatSession session, Recorder view, String text) throws Exception {
        boolean sent = ui.submit(() -> session.send(text, TurnMode.AUTO, "")).get(10, TimeUnit.SECONDS);
        assertTrue(sent, "the room was busy");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TURN_TIMEOUT_MINUTES);
        // Ready only counts after a stretch of quiet: the session chains steps (parts, then merge).
        int quietChecks = 0;
        while (quietChecks < 5) {
            if (System.nanoTime() > deadline) fail("turn didn't finish:\n" + String.join("\n\n", view.log));
            Thread.sleep(1000);
            ChatSession.State state = ui.submit(session::state).get(10, TimeUnit.SECONDS);
            quietChecks = state == ChatSession.State.READY ? quietChecks + 1 : 0;
        }
    }

    private static void initRepository(Path repo) throws IOException, InterruptedException {
        Files.writeString(repo.resolve("calc_add.py"), "# Addition helpers.\n");
        Files.writeString(repo.resolve("calc_mul.py"), "# Multiplication helpers.\n");
        Path hooks = Files.createDirectories(repo.resolve(".no-hooks"));
        Files.writeString(repo.resolve(".gitignore"), ".no-hooks/\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Consilium Test");
        git(repo, "config", "user.email", "test@example.invalid");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "config", "core.hooksPath", hooks.toString());
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "chore: start");
    }

    private static void git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException(String.join(" ", command) + ": " + output);
    }

    private static Stream<Path> children(Path directory) {
        try {
            return Files.list(directory).toList().stream();
        } catch (IOException exception) {
            return Stream.empty();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Collections.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
