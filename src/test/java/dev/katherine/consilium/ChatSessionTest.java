package dev.katherine.consilium;

import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.cli.AgentListener;
import dev.katherine.consilium.cli.CliAgent;
import dev.katherine.consilium.cli.RunningRequest;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.PendingWork;
import dev.katherine.consilium.model.Speaker;
import dev.katherine.consilium.intent.Intent;
import dev.katherine.consilium.intent.IntentClassifier;
import dev.katherine.consilium.work.GitWorktrees;
import dev.katherine.consilium.work.Worktree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Turn logic against fake agents; the UI executor runs tasks inline. */
class ChatSessionTest {
    /** One fake CLI run: the test drives its listener and decides when the process "exits". */
    static final class FakeRun implements RunningRequest {
        final String prompt;
        final String sessionId;
        final AgentAccess access;
        final Path root;
        final AgentListener listener;
        final CompletableFuture<Void> exited = new CompletableFuture<>();
        boolean cancelled;

        FakeRun(Path root, String prompt, String sessionId, AgentAccess access, AgentListener listener) {
            this.root = root;
            this.prompt = prompt;
            this.sessionId = sessionId;
            this.access = access;
            this.listener = listener;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public CompletionStage<Void> terminated() {
            return exited;
        }

        void answer(String text, String session) {
            listener.onSessionId(session);
            listener.onTextDelta(text);
            listener.onCompleted();
            exited.complete(null);
        }
    }

    static final class FakeAgent implements CliAgent {
        final Speaker speaker;
        final List<FakeRun> runs = new ArrayList<>();

        FakeAgent(Speaker speaker) {
            this.speaker = speaker;
        }

        @Override
        public Speaker speaker() {
            return speaker;
        }

        @Override
        public RunningRequest start(Path projectRoot, String prompt, String sessionId, AgentAccess access, AgentListener listener) {
            FakeRun run = new FakeRun(projectRoot, prompt, sessionId, access, listener);
            runs.add(run);
            return run;
        }

        FakeRun last() {
            return runs.get(runs.size() - 1);
        }
    }

    static final class RecordingView implements ChatSession.View {
        final List<String> events = new ArrayList<>();
        ChatSession.State state = ChatSession.State.READY;

        @Override
        public void messageAdded(ChatMessage message) {
            events.add("message " + message.textValue());
        }

        @Override
        public ChatSession.AnswerView answerStarted(Speaker speaker) {
            String name = speaker.name().toLowerCase();
            return new ChatSession.AnswerView() {
                @Override
                public void preview(String markdown) {
                    events.add(name + " preview " + markdown);
                }

                @Override
                public void completed(String markdown) {
                    events.add(name + " completed " + markdown);
                }

                @Override
                public void failed(String detail) {
                    events.add(name + " failed " + detail);
                }

                @Override
                public void stopped(String partial) {
                    events.add(name + " stopped " + partial);
                }
            };
        }

        @Override
        public void stateChanged(ChatSession.State state) {
            this.state = state;
        }

        @Override
        public void transcriptCleared() {
            events.add("cleared");
        }
    }

    /** Answers with a preset intent; records what it was asked so tests can check it saw only the person's words. */
    static final class FakeClassifier implements IntentClassifier {
        Intent next = Intent.discussWithBoth();
        CompletableFuture<Intent> pending;
        final List<String> asked = new ArrayList<>();
        boolean hold;
        boolean cancelled;

        @Override
        public CompletionStage<Intent> classify(String userMessage, Path projectRoot) {
            asked.add(userMessage);
            if (hold) {
                pending = new CompletableFuture<>();
                return pending;
            }
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (pending != null) pending.complete(Intent.discussWithBoth());
        }
    }

    /** An in-memory git: worktrees are paths under /wt, commits are whatever the test says. */
    static final class FakeGit implements GitWorktrees {
        boolean repository = true;
        boolean dirty;
        final Map<String, List<String>> commits = new HashMap<>();
        final Map<String, List<String>> changed = new HashMap<>();
        final List<Worktree> created = new ArrayList<>();
        final List<Worktree> removed = new ArrayList<>();
        final List<String> committed = new ArrayList<>();
        final Set<String> branches = new java.util.HashSet<>();
        final Map<String, String> nested = new HashMap<>();

        @Override
        public Path repositoryRoot(Path projectRoot) {
            return repository ? Path.of("/repo") : null;
        }

        @Override
        public boolean hasUncommittedChanges(Path repositoryRoot) {
            return dirty;
        }

        @Override
        public Worktree create(Path repositoryRoot, String agentName) {
            Worktree worktree = new Worktree(Path.of("/wt/" + agentName), "consilium/" + agentName + "-1", "base");
            created.add(worktree);
            branches.add(worktree.branch());
            commits.putIfAbsent(worktree.branch(), List.of("abc1234 " + agentName + "'s part"));
            return worktree;
        }

        @Override
        public List<String> commitsSinceCreated(Path repositoryRoot, Worktree worktree) {
            return commits.getOrDefault(worktree.branch(), List.of());
        }

        @Override
        public List<String> changedFiles(Path repositoryRoot, Worktree worktree) {
            return changed.getOrDefault(worktree.branch(), List.of("src/part.py"));
        }

        @Override
        public boolean hasUncommittedChanges(Worktree worktree) {
            return false;
        }

        @Override
        public boolean commitAll(Worktree worktree, String message) {
            committed.add(worktree.branch() + ": " + message);
            return true;
        }

        @Override
        public boolean owns(Path path) {
            return path.startsWith("/wt");
        }

        @Override
        public String nestedRepository(Worktree worktree) {
            return nested.get(worktree.branch());
        }

        @Override
        public List<String> changedGitlinks(Path repositoryRoot, Worktree worktree) {
            return List.of();
        }

        @Override
        public String hooksPathInRepository(Path repositoryRoot) {
            return null;
        }

        @Override
        public boolean branchExists(Path repositoryRoot, String branch) {
            return branches.contains(branch);
        }

        @Override
        public boolean remove(Path repositoryRoot, Worktree worktree) {
            removed.add(worktree);
            branches.remove(worktree.branch());
            return true;
        }
    }

    private final FakeClassifier classifier = new FakeClassifier();
    private final FakeGit git = new FakeGit();
    private final Map<Speaker, FakeAgent> agents = new EnumMap<>(Speaker.class);
    private final RecordingView view = new RecordingView();
    private boolean trusted = true;
    private ConsiliumStateStore store;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        agents.put(Speaker.CLAUDE, new FakeAgent(Speaker.CLAUDE));
        agents.put(Speaker.CODEX, new FakeAgent(Speaker.CODEX));
        store = new ConsiliumStateStore(new ConsiliumStateStoreTest.MemoryPersistence());
        session = new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted, Runnable::run, Runnable::run, view);
    }

    private FakeAgent claude() {
        return agents.get(Speaker.CLAUDE);
    }

    private FakeAgent codex() {
        return agents.get(Speaker.CODEX);
    }

    @Test
    void bothDiscussRunsBothAgentsReadOnlyAndStoresTheirAnswers() {
        assertTrue(session.send("video duplicates: before or after launch?", TurnMode.BOTH, ""));
        assertEquals(AgentAccess.READ_ONLY, claude().last().access);
        assertEquals(AgentAccess.READ_ONLY, codex().last().access);
        assertEquals(ChatSession.State.WORKING, view.state);

        claude().last().answer("After launch.", "claude-1");
        assertEquals(ChatSession.State.WORKING, view.state, "codex is still answering");
        codex().last().answer("After launch too.", "codex-1");

        assertEquals(ChatSession.State.READY, view.state);
        assertEquals(List.of("video duplicates: before or after launch?", "After launch.", "After launch too."),
                store.messages().stream().map(ChatMessage::textValue).toList());
        assertEquals("claude-1", store.session(Speaker.CLAUDE).sessionId);
        assertEquals(1, store.session(Speaker.CLAUDE).seenThrough);
    }

    @Test
    void theNextDiscussionResumesAndShowsTheOtherAgentOnly() {
        session.send("first", TurnMode.BOTH, "");
        claude().last().answer("claude one", "claude-1");
        codex().last().answer("codex one", "codex-1");

        session.send("second", TurnMode.CLAUDE, "");

        FakeRun resumed = claude().last();
        assertEquals("claude-1", resumed.sessionId);
        assertTrue(resumed.prompt.contains("codex one"), resumed.prompt);
        assertFalse(resumed.prompt.contains("claude one"), resumed.prompt);
        assertTrue(resumed.prompt.contains("second"), resumed.prompt);
    }

    @Test
    void afterADoesTurnTheAgentDiscussesWithItsOwnReport() {
        session.send("plan it", TurnMode.CLAUDE, "");
        claude().last().answer("plan", "claude-discuss");
        session.send("do it", TurnMode.CLAUDE_WRITES, "");
        FakeRun write = claude().last();
        assertEquals(AgentAccess.WRITE, write.access);
        assertNull(write.sessionId, "a does turn never resumes the discussion session");
        write.answer("I changed Parser.java", "claude-write");

        session.send("what did you change?", TurnMode.CLAUDE, "");

        FakeRun next = claude().last();
        assertNull(next.sessionId, "the discussion session predates the change, so start fresh");
        assertTrue(next.prompt.contains("I changed Parser.java"), next.prompt);
    }

    @Test
    void stopKeepsEverythingWrittenSoFarAndIgnoresALateCompletion() {
        session.send("question", TurnMode.CODEX, "");
        FakeRun run = codex().last();
        run.listener.onTextDelta("half ");
        run.listener.onTextDelta("an answer");

        session.stop();

        assertTrue(run.cancelled);
        ChatMessage partial = store.messages().get(1);
        assertEquals(MessageStatus.STOPPED, partial.statusValue());
        assertEquals("half an answer", partial.textValue());
        assertTrue(view.events.contains("codex stopped half an answer"), view.events.toString());

        run.listener.onCompleted();
        assertEquals(2, store.messages().size(), "the answer is not stored twice");
    }

    @Test
    void theRoomStaysBusyUntilTheStoppedProcessExits() {
        session.send("question", TurnMode.CLAUDE_WRITES, "");
        FakeRun run = claude().last();
        session.stop();

        assertEquals(ChatSession.State.FINISHING, view.state);
        assertFalse(session.send("next task", TurnMode.CODEX_WRITES, ""), "the previous writer may still be editing");
        assertFalse(session.clear());

        run.exited.complete(null);
        assertEquals(ChatSession.State.READY, view.state);
        assertTrue(session.send("next task", TurnMode.CODEX_WRITES, ""));
    }

    @Test
    void anErrorIsStoredAsAnErrorAndHiddenFromTheOtherAgent() {
        session.send("question", TurnMode.BOTH, "");
        claude().last().answer("my answer", "claude-1");
        FakeRun failed = codex().last();
        failed.listener.onError("codex CLI exited with code 1");
        failed.exited.complete(null);

        assertEquals(MessageStatus.ERROR, store.messages().get(2).statusValue());
        assertTrue(view.events.contains("codex failed codex CLI exited with code 1"), view.events.toString());

        session.send("follow-up", TurnMode.CODEX, "");
        assertFalse(codex().last().prompt.contains("exited with code"), codex().last().prompt);
    }

    @Test
    void anEmptyAnswerIsSaidOutLoud() {
        session.send("question", TurnMode.CLAUDE, "");
        claude().last().answer("", "claude-1");
        assertEquals(ConsiliumBundle.message("answer.empty"), store.messages().get(1).textValue());
    }

    @Test
    void blankMessagesAndBusyRoomsAreRefused() {
        assertFalse(session.send("   ", TurnMode.BOTH, ""));
        assertTrue(session.send("question", TurnMode.CLAUDE, ""));
        assertFalse(session.send("another", TurnMode.CODEX, ""));
        assertEquals(1, codex().runs.size() + claude().runs.size());
    }

    @Test
    void clearEmptiesTheRoomAndForgetsSessions() {
        session.send("question", TurnMode.CLAUDE, "");
        claude().last().answer("answer", "claude-1");

        assertTrue(session.clear());

        assertTrue(store.messages().isEmpty());
        assertNull(store.session(Speaker.CLAUDE).sessionId);
        assertTrue(view.events.contains("cleared"));
    }

    @Test
    void closingTheProjectKeepsThePartialAndStopsCallingTheView() {
        session.send("question", TurnMode.CLAUDE, "");
        FakeRun run = claude().last();
        run.listener.onTextDelta("partial");
        int eventsBefore = view.events.size();

        session.dispose();
        run.listener.onTextDelta(" more");
        run.listener.onCompleted();

        assertTrue(run.cancelled);
        assertEquals(MessageStatus.STOPPED, store.messages().get(1).statusValue());
        assertEquals("partial", store.messages().get(1).textValue());
        assertEquals(eventsBefore, view.events.size());
    }

    @Test
    void closingTheProjectWritesTheStateOutAtOnce() {
        ConsiliumStateStoreTest.MemoryPersistence persistence = new ConsiliumStateStoreTest.MemoryPersistence();
        ChatSession closing = new ChatSession(new ConsiliumStateStore(persistence), agents::get, classifier, new ParallelWork(git),
                Path.of("/project"), () -> trusted, Runnable::run, Runnable::run, view);
        closing.send("question", TurnMode.CLAUDE, "");
        claude().last().listener.onTextDelta("partial");

        closing.dispose();

        assertEquals(1, persistence.flushes);
        assertTrue(persistence.value.contains("partial"), persistence.value);
    }

    @Test
    void closingTheProjectStoresAFinishedAnswerWhoseCompletionWasStillQueued() {
        Queue<Runnable> edt = new ArrayDeque<>();
        ChatSession deferred = new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted, edt::add, Runnable::run, view);
        deferred.send("question", TurnMode.CLAUDE, "");
        claude().last().answer("full answer", "claude-1");

        deferred.dispose();
        while (!edt.isEmpty()) edt.poll().run();

        assertEquals(2, store.messages().size());
        assertEquals(MessageStatus.ANSWER, store.messages().get(1).statusValue(), "not cut off");
        assertEquals("full answer", store.messages().get(1).textValue());
        assertEquals("claude-1", store.session(Speaker.CLAUDE).sessionId, "the next discussion resumes it");
        assertEquals(1, store.session(Speaker.CLAUDE).seenThrough);
    }

    @Test
    void closingTheProjectKeepsAnErrorWhoseReportWasStillQueued() {
        Queue<Runnable> edt = new ArrayDeque<>();
        ChatSession deferred = new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted, edt::add, Runnable::run, view);
        deferred.send("question", TurnMode.CODEX, "");
        FakeRun run = codex().last();
        run.listener.onTextDelta("half");
        run.listener.onError("codex CLI exited with code 1");

        deferred.dispose();
        while (!edt.isEmpty()) edt.poll().run();

        assertEquals(2, store.messages().size());
        assertEquals(MessageStatus.ERROR, store.messages().get(1).statusValue());
        assertEquals("codex CLI exited with code 1", store.messages().get(1).textValue());
    }

    @Test
    void closingTheProjectTwiceStoresThePartialOnce() {
        session.send("question", TurnMode.CLAUDE, "");
        claude().last().listener.onTextDelta("partial");

        session.dispose();
        session.dispose();

        assertEquals(2, store.messages().size());
    }

    @Test
    void editorContextReachesThePrompt() {
        session.send("look at this", TurnMode.CLAUDE, "File: /project/Main.java");
        assertTrue(claude().last().prompt.contains("<editor-context>\nFile: /project/Main.java"), claude().last().prompt);
    }

    @Test
    void anAnswerArrivingAfterTheProcessExitedStillLandsBeforeTheNextTurn() {
        session.send("question", TurnMode.CODEX, "");
        FakeRun run = codex().last();
        run.exited.complete(null);

        assertEquals(ChatSession.State.WORKING, view.state, "the outcome hasn't arrived yet");
        assertFalse(session.send("next", TurnMode.CODEX, ""));

        run.listener.onError("codex CLI exited with code 1");
        assertEquals(ChatSession.State.READY, view.state);
        assertTrue(session.send("next", TurnMode.CODEX, ""));
        assertEquals(List.of("question", "codex CLI exited with code 1", "next"),
                store.messages().stream().map(ChatMessage::textValue).toList());
    }

    @Test
    void stopDoesNotCutOffAnAnswerThatAlreadyArrived() {
        Queue<Runnable> edt = new ArrayDeque<>();
        ChatSession deferred = new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted, edt::add, Runnable::run, view);
        deferred.send("question", TurnMode.CLAUDE, "");
        FakeRun run = claude().last();
        run.listener.onTextDelta("full answer");
        run.listener.onCompleted();

        deferred.stop();
        while (!edt.isEmpty()) edt.poll().run();

        ChatMessage answer = store.messages().get(1);
        assertEquals(MessageStatus.ANSWER, answer.statusValue());
        assertEquals("full answer", answer.textValue());
        assertEquals(2, store.messages().size());
    }

    @Test
    void anUnusableStoredSessionIdMeansAFreshPrompt() {
        session.send("first", TurnMode.CLAUDE, "");
        claude().last().answer("one", "claude-1");
        store.updateSession(Speaker.CLAUDE, "--dangerously-skip-permissions", 2);

        session.send("second", TurnMode.CLAUDE, "");

        FakeRun run = claude().last();
        assertNull(run.sessionId);
        assertTrue(run.prompt.contains("discussion turn"), run.prompt);
        assertTrue(run.prompt.contains("first"), "a fresh session gets the whole transcript");
    }

    @Test
    void theTranscriptIsTrimmedBetweenTurns() {
        for (int index = 0; index < ConsiliumStateStore.MAX_STORED_MESSAGES; index++) {
            store.add(Speaker.USER, "old " + index, MessageStatus.ANSWER);
        }
        session.send("question", TurnMode.CLAUDE, "");
        assertEquals(ConsiliumStateStore.MAX_STORED_MESSAGES + 1, store.messages().size(), "never during a turn");

        claude().last().answer("answer", "claude-1");

        assertEquals(ConsiliumStateStore.MAX_STORED_MESSAGES, store.messages().size());
        assertEquals("answer", store.messages().get(ConsiliumStateStore.MAX_STORED_MESSAGES - 1).textValue());
        assertEquals(ConsiliumStateStore.MAX_STORED_MESSAGES - 1, store.session(Speaker.CLAUDE).seenThrough,
                "seenThrough shifts with the trimmed messages");
    }

    @Test
    void stopAfterTheProcessesAlreadyExitedFreesTheRoom() {
        session.send("question", TurnMode.CODEX, "");
        FakeRun run = codex().last();
        run.listener.onTextDelta("partial");
        run.exited.complete(null);

        session.stop();

        assertEquals(ChatSession.State.READY, view.state);
        assertTrue(session.send("next", TurnMode.CODEX, ""));
    }

    // Auto: the person just writes

    private List<String> notices() {
        return store.messages().stream().filter(m -> m.speakerValue() == Speaker.CONSILIUM).map(ChatMessage::textValue).toList();
    }

    @Test
    void autoReadsOnlyThePersonsWordsAndDiscussesByDefault() {
        session.send("what do you think about caching?", TurnMode.AUTO, "");

        assertEquals(List.of("what do you think about caching?"), classifier.asked);
        assertEquals(AgentAccess.READ_ONLY, claude().last().access);
        assertEquals(AgentAccess.READ_ONLY, codex().last().access);
    }

    @Test
    void autoAsksOnlyTheAgentThatWasAddressed() {
        classifier.next = new Intent(Intent.Kind.DISCUSS, Set.of(Speaker.CODEX));
        session.send("codex, what do you say?", TurnMode.AUTO, "");

        assertEquals(1, codex().runs.size());
        assertEquals(0, claude().runs.size());
    }

    @Test
    void autoLetsOneNamedAgentWorkInTheProject() {
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE));
        session.send("claude, fix the parser", TurnMode.AUTO, "");

        FakeRun run = claude().last();
        assertEquals(AgentAccess.WRITE, run.access);
        assertEquals(Path.of("/project"), run.root);
        assertEquals(0, codex().runs.size());
        assertTrue(git.created.isEmpty(), "one agent works in the project itself");
    }

    @Test
    void anUntrustedProjectGetsWritersWithoutItsOwnSettings() {
        trusted = false;
        session.send("fix the parser", TurnMode.CLAUDE_WRITES, "");
        assertEquals(AgentAccess.WRITE_ISOLATED, claude().last().access);
        claude().last().answer("fixed", "c1");

        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CODEX));
        session.send("codex, fix the layout", TurnMode.AUTO, "");
        assertEquals(AgentAccess.WRITE_ISOLATED, codex().last().access);
        codex().last().answer("fixed", "x1");

        trusted = true;
        session.send("claude, now the tests", TurnMode.CLAUDE_WRITES, "");
        assertEquals(AgentAccess.WRITE, claude().last().access, "trust is asked at every write turn");
    }

    @Test
    void theMergeInAnUntrustedProjectRunsWithoutItsSettings() {
        trusted = false;
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        session.send("go ahead", TurnMode.AUTO, "");
        claude().last().answer("cards done", "c1");
        codex().last().answer("layout done", "x1");

        FakeRun merge = claude().last();
        assertEquals(Path.of("/project"), merge.root);
        assertEquals(AgentAccess.WRITE_ISOLATED, merge.access);
    }

    @Test
    void anUnclearMessageAsksInsteadOfStartingWork() {
        classifier.next = new Intent(Intent.Kind.UNCLEAR, Set.of());
        session.send("ok", TurnMode.AUTO, "");

        assertEquals(0, claude().runs.size() + codex().runs.size());
        assertEquals(List.of(ConsiliumBundle.message("notice.unclear")), notices());
        assertEquals(ChatSession.State.READY, view.state);
    }

    @Test
    void goAheadSendsBothToTheirOwnCopiesThenClaudeMergesAndTheCopiesGo() {
        session.send("let's split it: claude takes the cards, codex the layout", TurnMode.AUTO, "");
        claude().last().answer("I take the cards.", "c1");
        codex().last().answer("I take the layout.", "x1");
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));

        session.send("go ahead", TurnMode.AUTO, "");

        FakeRun claudePart = claude().last();
        FakeRun codexPart = codex().last();
        assertEquals(Path.of("/wt/claude"), claudePart.root);
        assertEquals(Path.of("/wt/codex"), codexPart.root);
        assertEquals(AgentAccess.WRITE_ISOLATED, claudePart.access, "a copy never loads the project's settings");
        assertEquals(AgentAccess.WRITE_ISOLATED, codexPart.access);
        assertTrue(claudePart.prompt.contains("consilium/claude-1"), claudePart.prompt);
        assertTrue(claudePart.prompt.contains("I take the layout."), "each part sees the agreement");
        assertEquals(2, store.pendingWork().size());
        assertTrue(notices().getFirst().contains("consilium/codex-1"), notices().toString());

        claudePart.answer("cards done", "c2");
        assertEquals(2, claude().runs.size(), "the discussion and the part: no merge before both parts are done");
        codexPart.answer("layout done", "x2");

        FakeRun merge = claude().last();
        assertEquals(Path.of("/project"), merge.root);
        assertEquals(AgentAccess.WRITE, merge.access);
        assertTrue(merge.prompt.contains("Claude: consilium/claude-1; Codex: consilium/codex-1"), merge.prompt);

        merge.answer("merged both, tests pass", "c3");

        assertEquals(2, git.removed.size());
        assertTrue(store.pendingWork().isEmpty());
        assertEquals(ConsiliumBundle.message("notice.merge.cleaned"), notices().getLast());
        assertEquals(ChatSession.State.READY, view.state);
    }

    @Test
    void withoutGitParallelWorkIsRefusedInWords() {
        git.repository = false;
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));

        session.send("go ahead", TurnMode.AUTO, "");

        assertEquals(0, claude().runs.size() + codex().runs.size());
        assertEquals(List.of(ConsiliumBundle.message("notice.parallel.no.git")), notices());
        assertEquals(ChatSession.State.READY, view.state);
    }

    @Test
    void aFailedPartIsNotMergedAndWaitsForWords() {
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        session.send("go ahead", TurnMode.AUTO, "");
        claude().last().answer("cards done", "c1");
        FakeRun codexPart = codex().last();
        codexPart.listener.onError("codex CLI exited with code 1");
        codexPart.exited.complete(null);

        assertEquals(1, claude().runs.size(), "no merge after a failed part");
        assertEquals(2, store.pendingWork().size(), "the work stays on its branches");

        classifier.next = new Intent(Intent.Kind.INTEGRATE, Set.of(Speaker.CODEX));
        session.send("codex, merge what's there", TurnMode.AUTO, "");

        FakeRun merge = codex().last();
        assertEquals(Path.of("/project"), merge.root);
        assertTrue(merge.prompt.contains("consilium/claude-1"), merge.prompt);
    }

    @Test
    void aPartWithoutCommitsIsNotMerged() {
        git.commits.put("consilium/codex-1", List.of());
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        session.send("go ahead", TurnMode.AUTO, "");
        claude().last().answer("cards done", "c1");
        codex().last().answer("there was no split, so I changed nothing", "x1");

        assertEquals(1, claude().runs.size(), "nothing merged");
        assertTrue(notices().getLast().startsWith("Codex"), notices().toString());
    }

    @Test
    void partsWhereNobodyChangedAnythingAreRemoved() {
        git.commits.put("consilium/claude-1", List.of());
        git.commits.put("consilium/codex-1", List.of());
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        session.send("go ahead", TurnMode.AUTO, "");
        claude().last().answer("it's a question, so I changed nothing", "c1");
        codex().last().answer("there was no split, so I changed nothing", "x1");

        assertEquals(1, claude().runs.size(), "nothing merged");
        assertEquals(2, git.removed.size(), "the empty copies are removed");
        assertTrue(store.pendingWork().isEmpty(), "nothing is left waiting for a merge");
        assertTrue(notices().getLast().startsWith("Neither agent"), notices().toString());
    }

    @Test
    void stopWhileReadingTheMessageStartsNothing() {
        classifier.hold = true;
        session.send("go ahead", TurnMode.AUTO, "");
        assertEquals(ChatSession.State.WORKING, view.state);

        session.stop();

        assertTrue(classifier.cancelled);
        assertEquals(ChatSession.State.READY, view.state);
        assertEquals(0, claude().runs.size() + codex().runs.size());
    }

    private void failBothParts() {
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        session.send("go ahead", TurnMode.AUTO, "");
        claude().last().listener.onError("boom");
        claude().last().exited.complete(null);
        codex().last().listener.onError("boom");
        codex().last().exited.complete(null);
    }

    @Test
    void clearKeepsUnmergedWorkAndSaysWhereItIs() {
        failBothParts();

        assertTrue(session.clear());

        assertTrue(git.removed.isEmpty(), "nothing an agent did is thrown away");
        assertEquals(2, store.pendingWork().size());
        assertEquals(List.of(ConsiliumBundle.message("notice.pending", "consilium/claude-1, consilium/codex-1")), notices());
    }

    @Test
    void aNewParallelTaskWaitsUntilEarlierPartsAreMerged() {
        failBothParts();
        int created = git.created.size();

        session.send("go ahead again", TurnMode.AUTO, "");

        assertEquals(created, git.created.size(), "no new copies over unmerged ones");
        assertEquals(ConsiliumBundle.message("notice.pending", "consilium/claude-1, consilium/codex-1"), notices().getLast());
    }

    @Test
    void theMergeWaitsWhileTheProjectHasUncommittedChanges() {
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        session.send("go ahead", TurnMode.AUTO, "");
        git.dirty = true;
        claude().last().answer("cards done", "c1");
        codex().last().answer("layout done", "x1");

        assertEquals(1, claude().runs.size(), "no merge over the person's own edits");
        assertTrue(notices().getLast().startsWith(ConsiliumBundle.message("notice.merge.dirty", "").substring(0, 20)), notices().toString());
    }

    @Test
    void consiliumCommitsEachPartAndTheAgentsAreToldNotTo() {
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        session.send("go ahead", TurnMode.AUTO, "");
        FakeRun part = claude().last();
        assertTrue(part.prompt.contains("Don't commit"), part.prompt);

        part.answer("cards done", "c1");
        codex().last().answer("layout done", "x1");

        assertEquals(2, git.committed.size());
        assertTrue(git.committed.getFirst().startsWith("consilium/claude-1: chore(consilium): claude part"), git.committed.toString());
        assertTrue(git.committed.getFirst().contains("cards done"), "the agent's report becomes the commit body");
    }

    @Test
    void aLateStepAfterStopDoesNotFreeTheRoom() {
        Queue<Runnable> work = new ArrayDeque<>();
        ChatSession queued = new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted,
                Runnable::run, work::add, view);
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        queued.send("go ahead", TurnMode.AUTO, "");
        queued.stop();
        classifier.hold = true;
        queued.send("what now?", TurnMode.AUTO, "");
        assertEquals(ChatSession.State.WORKING, queued.state());

        while (!work.isEmpty()) work.poll().run();

        assertEquals(ChatSession.State.WORKING, queued.state(), "the classifier is still reading the new message");
        assertEquals(2, git.removed.size(), "the copies made for the stopped task are removed");
        assertEquals(0, claude().runs.size() + codex().runs.size());
    }

    @Test
    void stopDuringCleanupStillRecordsWhatIsOnDisk() {
        Queue<Runnable> work = new ArrayDeque<>();
        ChatSession queued = new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted,
                Runnable::run, work::add, view);
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        queued.send("go ahead", TurnMode.AUTO, "");
        work.poll().run();
        claude().last().answer("cards done", "c1");
        codex().last().answer("layout done", "x1");
        work.poll().run();
        claude().last().answer("merged", "c2");

        queued.stop();
        while (!work.isEmpty()) work.poll().run();

        assertTrue(store.pendingWork().isEmpty(), "the copies are gone, so the record is too");
    }

    @Test
    void afterARestartTheRoomSaysOnceThatWorkIsWaiting() {
        failBothParts();
        int before = notices().size();

        new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted, Runnable::run, Runnable::run, view);
        new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted, Runnable::run, Runnable::run, view);

        assertEquals(before + 1, notices().size());
        assertEquals(ConsiliumBundle.message("notice.pending", "consilium/claude-1, consilium/codex-1"), notices().getLast());
    }

    @Test
    void afterARestartBranchesThatAreGoneAreForgottenQuietly() {
        failBothParts();
        git.branches.clear();
        int before = notices().size();

        new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted, Runnable::run, Runnable::run, view);

        assertTrue(store.pendingWork().isEmpty());
        assertEquals(before, notices().size(), "no notice about work that isn't there");
    }

    @Test
    void afterARestartGitIsAskedInTheBackground() {
        failBothParts();
        git.branches.remove("consilium/claude-1");
        Queue<Runnable> work = new ArrayDeque<>();

        ChatSession restarted = new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted,
                Runnable::run, work::add, view);

        assertEquals(ChatSession.State.WORKING, restarted.state(), "busy until git answers");
        assertEquals(2, store.pendingWork().size());
        while (!work.isEmpty()) work.poll().run();
        assertEquals(ChatSession.State.READY, view.state);
        assertEquals(List.of("consilium/codex-1"), store.pendingWork().stream().map(part -> part.branch).toList());
        assertEquals(ConsiliumBundle.message("notice.pending", "consilium/codex-1"), notices().getLast());
    }

    @Test
    void noticesNeverReachTheAgents() {
        classifier.next = new Intent(Intent.Kind.UNCLEAR, Set.of());
        session.send("ok", TurnMode.AUTO, "");
        classifier.next = Intent.discussWithBoth();

        session.send("what now?", TurnMode.AUTO, "");

        assertFalse(claude().last().prompt.contains(ConsiliumBundle.message("notice.unclear")), claude().last().prompt);
    }

    @Test
    void mergeAfterAStoppedPartCommitsWhatItLeftFirst() {
        failBothParts();
        git.committed.clear();
        classifier.next = new Intent(Intent.Kind.INTEGRATE, Set.of(Speaker.CLAUDE));

        session.send("merge what's there", TurnMode.AUTO, "");

        assertEquals(2, git.committed.size(), "the parts are committed before the merge");
        assertTrue(claude().last().prompt.contains("consilium/claude-1"), claude().last().prompt);
    }

    @Test
    void mergeNamesANestedRepositoryInsteadOfSayingThereIsNothing() {
        failBothParts();
        git.nested.put("consilium/codex-1", "vendor/lib");
        git.commits.clear();
        classifier.next = new Intent(Intent.Kind.INTEGRATE, Set.of(Speaker.CLAUDE));

        session.send("merge", TurnMode.AUTO, "");

        assertTrue(notices().getLast().contains("vendor/lib"), notices().getLast());
    }

    @Test
    void mergeAfterAStoppedPartTellsAboutHookSettingsBeforeMerging() {
        git.changed.put("consilium/codex-1", List.of(".claude/settings.json"));
        failBothParts();
        classifier.next = new Intent(Intent.Kind.INTEGRATE, Set.of(Speaker.CLAUDE));
        int runsBefore = claude().runs.size();

        session.send("merge what's there", TurnMode.AUTO, "");

        assertEquals(runsBefore, claude().runs.size(), "the first merge only tells");
        assertEquals(ConsiliumBundle.message("notice.merge.agent.settings", "consilium/codex-1", ".claude/settings.json"),
                notices().getLast());

        classifier.next = new Intent(Intent.Kind.INTEGRATE, Set.of(Speaker.CLAUDE));
        session.send("looked at it, merge", TurnMode.AUTO, "");

        assertTrue(claude().last().prompt.contains("consilium/codex-1"), "the person's word after the notice merges it");
    }

    @Test
    void aRecordPointingOutsideConsiliumsFolderIsDropped() {
        store.setPendingWork(List.of(new PendingWork(Speaker.CODEX, "/Users/someone/project", "consilium/codex-1", "0123abcd")));

        new ChatSession(store, agents::get, classifier, new ParallelWork(git), Path.of("/project"), () -> trusted, Runnable::run, Runnable::run, view);

        assertTrue(store.pendingWork().isEmpty());
    }

    @Test
    void aPartThatChangesHookSettingsWaitsForThePersonBeforeMerging() {
        git.changed.put("consilium/codex-1", List.of("src/layout.py", ".husky/pre-commit"));
        classifier.next = new Intent(Intent.Kind.WORK, Set.of(Speaker.CLAUDE, Speaker.CODEX));
        session.send("go ahead", TurnMode.AUTO, "");
        claude().last().answer("cards done", "c1");
        codex().last().answer("layout done", "x1");

        assertEquals(1, claude().runs.size(), "no automatic merge");
        assertEquals(ConsiliumBundle.message("notice.merge.hooks", "consilium/codex-1", ".husky/pre-commit"), notices().getLast());

        classifier.next = new Intent(Intent.Kind.INTEGRATE, Set.of(Speaker.CLAUDE));
        session.send("looked at it, merge", TurnMode.AUTO, "");

        assertTrue(claude().last().prompt.contains("consilium/codex-1"), "the person's word merges it");
    }
}
