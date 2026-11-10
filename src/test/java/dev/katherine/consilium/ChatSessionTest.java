package dev.katherine.consilium;

import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.cli.AgentListener;
import dev.katherine.consilium.cli.CliAgent;
import dev.katherine.consilium.cli.RunningRequest;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.Speaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
        final AgentListener listener;
        final CompletableFuture<Void> exited = new CompletableFuture<>();
        boolean cancelled;

        FakeRun(String prompt, String sessionId, AgentAccess access, AgentListener listener) {
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
            FakeRun run = new FakeRun(prompt, sessionId, access, listener);
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

    private final Map<Speaker, FakeAgent> agents = new EnumMap<>(Speaker.class);
    private final RecordingView view = new RecordingView();
    private ConsiliumStateStore store;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        agents.put(Speaker.CLAUDE, new FakeAgent(Speaker.CLAUDE));
        agents.put(Speaker.CODEX, new FakeAgent(Speaker.CODEX));
        store = new ConsiliumStateStore(new ConsiliumStateStoreTest.MemoryPersistence());
        session = new ChatSession(store, agents::get, Path.of("/project"), Runnable::run, view);
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
    void editorContextReachesThePrompt() {
        session.send("look at this", TurnMode.CLAUDE, "File: /project/Main.java");
        assertTrue(claude().last().prompt.contains("<editor-context>\nFile: /project/Main.java"), claude().last().prompt);
    }
}
