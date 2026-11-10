package dev.katherine.consilium;

import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.cli.AgentListener;
import dev.katherine.consilium.cli.CliAgent;
import dev.katherine.consilium.cli.RunningRequest;
import dev.katherine.consilium.cli.SessionIds;
import dev.katherine.consilium.model.AgentSession;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.Speaker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * One room's turns: who runs, what each agent is told, what gets stored. No Swing here, so the
 * logic that decides what the agents see is covered by plain unit tests.
 *
 * <p>Threading: every public method must be called on the UI thread, and every {@link View}
 * callback arrives on it, through the {@code ui} executor. Agent callbacks come from background
 * threads and are handed over to the UI thread at once, so a turn is settled in exactly one place
 * and Stop can't race a completion.
 *
 * <p>A turn stays "busy" until every agent process has exited, not only until its answer is
 * complete: otherwise a new "does" turn could start while the previous writer's children were
 * still editing files.
 */
final class ChatSession {
    enum State {
        READY,
        WORKING,
        /** Every answer is settled, but an agent process is still exiting. */
        FINISHING
    }

    interface View {
        void messageAdded(ChatMessage message);

        AnswerView answerStarted(Speaker speaker);

        void stateChanged(State state);

        void transcriptCleared();
    }

    interface AnswerView {
        /** The answer so far, while it streams. */
        void preview(String markdown);

        void completed(String markdown);

        void failed(String detail);

        void stopped(String partial);
    }

    private final class Run implements AgentListener {
        final Speaker speaker;
        final AgentAccess access;
        final int snapshotSize;
        final AnswerView view;
        private final StringBuilder text = new StringBuilder();
        private final AtomicBoolean framePending = new AtomicBoolean();
        private volatile String sessionId;
        // Set on the agent's thread as soon as it reports an outcome, before the UI hears of it:
        // Stop pressed in between must not turn a finished answer into a cut-off one.
        private volatile boolean outcomeReported;
        RunningRequest request;
        boolean settled;
        boolean exited;

        Run(Speaker speaker, AgentAccess access, int snapshotSize, String sessionId, AnswerView view) {
            this.speaker = speaker;
            this.access = access;
            this.snapshotSize = snapshotSize;
            this.sessionId = sessionId;
            this.view = view;
        }

        String text() {
            synchronized (text) {
                return text.toString();
            }
        }

        @Override
        public void onSessionId(String value) {
            if (value != null && !value.isBlank()) sessionId = value;
        }

        @Override
        public void onTextDelta(String delta) {
            synchronized (text) {
                text.append(delta);
            }
            // Deltas come every few tokens and re-rendering Markdown is expensive: at most one
            // frame is queued, and it reads the newest text when it runs, not when it was queued.
            if (framePending.compareAndSet(false, true)) {
                onUi(() -> {
                    framePending.set(false);
                    if (!settled) view.preview(text());
                });
            }
        }

        @Override
        public void onCompleted() {
            outcomeReported = true;
            onUi(() -> complete(this));
        }

        @Override
        public void onError(String message) {
            outcomeReported = true;
            onUi(() -> fail(this, message));
        }
    }

    private final ConsiliumStateStore store;
    private final Function<Speaker, CliAgent> agents;
    private final Path projectRoot;
    private final Executor ui;
    private final View view;
    private final Map<Speaker, Run> runs = new EnumMap<>(Speaker.class);
    private volatile boolean disposed;

    ChatSession(ConsiliumStateStore store, Function<Speaker, CliAgent> agents, Path projectRoot, Executor ui, View view) {
        this.store = store;
        this.agents = agents;
        this.projectRoot = projectRoot;
        this.ui = ui;
        this.view = view;
    }

    List<ChatMessage> messages() {
        return store.messages();
    }

    State state() {
        if (runs.isEmpty()) return State.READY;
        for (Run run : runs.values()) {
            if (!run.settled) return State.WORKING;
        }
        return State.FINISHING;
    }

    /** Starts a turn; returns false when the room is busy or the text is blank. */
    boolean send(String text, TurnMode mode, String editorContext) {
        if (disposed || state() != State.READY || text == null || text.isBlank()) return false;
        view.messageAdded(store.add(Speaker.USER, text.trim(), MessageStatus.ANSWER));
        List<ChatMessage> snapshot = store.messages();
        for (Speaker speaker : mode.agents()) {
            start(speaker, mode.access(), snapshot, editorContext);
        }
        view.stateChanged(state());
        return true;
    }

    private void start(Speaker speaker, AgentAccess access, List<ChatMessage> snapshot, String editorContext) {
        AgentSession session = store.session(speaker);
        // A "does" turn always starts a fresh session with the whole transcript: a clean task
        // context, with permissions set by explicit flags rather than inherited from a discussion
        // session (codex can't set the sandbox when resuming). A stored id the CLI layer wouldn't
        // resume counts as no session here too, or the agent would get a follow-up prompt in a
        // session that has never seen the conversation.
        String resumable = access == AgentAccess.WRITE ? null : SessionIds.resumable(session.sessionId);
        boolean fresh = resumable == null;
        String prompt = ConversationPromptBuilder.build(snapshot, speaker, session.seenThrough, fresh, editorContext, access);
        Run run = new Run(speaker, access, snapshot.size(), resumable, view.answerStarted(speaker));
        runs.put(speaker, run);
        try {
            run.request = agents.apply(speaker).start(projectRoot, prompt, resumable, access, run);
        } catch (RuntimeException exception) {
            runs.remove(speaker);
            fail(run, String.valueOf(exception.getMessage()));
            return;
        }
        run.request.terminated().whenComplete((ignored, error) -> onUi(() -> {
            run.exited = true;
            release(run);
        }));
    }

    private void complete(Run run) {
        if (run.settled) return;
        run.settled = true;
        String answer = run.text().trim();
        if (answer.isEmpty()) answer = ConsiliumBundle.message("answer.empty");
        store.add(run.speaker, answer, MessageStatus.ANSWER);
        if (run.access == AgentAccess.READ_ONLY) {
            store.updateSession(run.speaker, run.sessionId, run.snapshotSize);
        } else {
            store.resetSession(run.speaker);
        }
        run.view.completed(answer);
        release(run);
    }

    private void fail(Run run, String detail) {
        if (run.settled) return;
        run.settled = true;
        store.add(run.speaker, detail, MessageStatus.ERROR);
        if (run.access == AgentAccess.WRITE) store.resetSession(run.speaker);
        run.view.failed(detail);
        release(run);
    }

    /** Stops every agent; what they managed to write is kept, marked as cut off. */
    void stop() {
        for (Run run : new ArrayList<>(runs.values())) {
            // cancel() returns only after any callback in flight has finished, so after it
            // outcomeReported is final: a reported answer is left to its queued completion.
            if (run.request != null) run.request.cancel();
            if (run.settled || run.outcomeReported) continue;
            run.settled = true;
            keepPartial(run);
            run.view.stopped(run.text().trim());
        }
        view.stateChanged(state());
    }

    private void keepPartial(Run run) {
        // The full text, not the last rendered frame: a frame may still be queued.
        String partial = run.text().trim();
        if (!partial.isEmpty()) store.add(run.speaker, partial, MessageStatus.STOPPED);
        if (run.access == AgentAccess.WRITE) store.resetSession(run.speaker);
    }

    /**
     * A run leaves the room only when its answer is settled AND its processes have exited, in
     * whichever order those happen; until then the room isn't ready for the next turn.
     */
    private void release(Run run) {
        if (run.settled && run.exited && runs.get(run.speaker) == run) {
            runs.remove(run.speaker);
            // Indices of turns in flight are no longer in use, so the transcript can be trimmed.
            if (runs.isEmpty()) store.compact();
        }
        view.stateChanged(state());
    }

    /** Empties the transcript and forgets both agents' sessions; refused while a turn is running. */
    boolean clear() {
        if (state() != State.READY) return false;
        store.clear();
        view.transcriptCleared();
        return true;
    }

    /** Closing the project stops the agents and keeps partial answers, as a manual Stop does. */
    void dispose() {
        disposed = true;
        for (Run run : runs.values()) {
            if (run.request != null) run.request.cancel();
            if (run.settled) continue;
            run.settled = true;
            keepPartial(run);
        }
        runs.clear();
    }

    private void onUi(Runnable action) {
        if (disposed) return;
        ui.execute(() -> {
            if (!disposed) action.run();
        });
    }
}
