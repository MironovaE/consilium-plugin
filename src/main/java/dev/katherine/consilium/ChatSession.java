package dev.katherine.consilium;

import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.cli.AgentListener;
import dev.katherine.consilium.cli.CliAgent;
import dev.katherine.consilium.cli.RunningRequest;
import dev.katherine.consilium.cli.SessionIds;
import dev.katherine.consilium.intent.Intent;
import dev.katherine.consilium.intent.IntentClassifier;
import dev.katherine.consilium.model.AgentSession;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.PendingWork;
import dev.katherine.consilium.model.Speaker;
import dev.katherine.consilium.work.Worktree;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * One room's turns: who runs, what each agent is told, what gets stored. No Swing here, so the
 * logic that decides what the agents see is covered by plain unit tests.
 *
 * <p>Threading: the session is confined to the UI thread. The panel calls it there, and every
 * {@link View} callback arrives there through the {@code ui} executor. Agent callbacks come from
 * background threads and are handed over to the UI thread at once, so a turn is settled in exactly
 * one place and Stop can't race a completion.
 *
 * <p>A turn stays "busy" until every agent process has exited, not only until its answer is
 * complete: otherwise a new "does" turn could start while the previous writer's children were
 * still editing files.
 *
 * <p>In {@link TurnMode#AUTO} the person just writes. What the message asks for (discussion, work
 * by one agent, work by both, merging) is read from the person's words alone by the
 * {@link IntentClassifier}; agent text never grants write access. When both agents work, the
 * {@link ParallelTask} gives each its own git worktree and has Claude merge the branches.
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

    /** What a run is for; decides what happens after it. */
    private enum Role {
        /** A discussion answer or a one-agent task in the project. */
        TURN,
        /** One agent's part of parallel work, in its own worktree. */
        PART,
        /** Merging the parallel parts into the project. */
        MERGE
    }

    private final class Run implements AgentListener {
        final Speaker speaker;
        final AgentAccess access;
        final Role role;
        final int snapshotSize;
        final AnswerView view;
        MessageStatus outcome;
        private final StringBuilder text = new StringBuilder();
        private final AtomicBoolean framePending = new AtomicBoolean();
        private volatile String sessionId;
        // Set on the agent's thread as soon as it reports an outcome (ANSWER or ERROR), before the
        // UI hears of it: Stop or closing the project in between must not turn a finished answer
        // into a cut-off one. The error detail is written first, so whoever sees ERROR sees it too.
        private volatile String errorDetail;
        private volatile MessageStatus reported;
        RunningRequest request;
        boolean settled;
        boolean exited;

        Run(Speaker speaker, AgentAccess access, Role role, int snapshotSize, String sessionId, AnswerView view) {
            this.speaker = speaker;
            this.access = access;
            this.role = role;
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
            reported = MessageStatus.ANSWER;
            onUi(() -> complete(this));
        }

        @Override
        public void onError(String message) {
            errorDetail = message;
            reported = MessageStatus.ERROR;
            onUi(() -> fail(this, message));
        }
    }

    /** The session's side of parallel work: notices and agent runs. */
    private final class Host implements ParallelTask.Host {
        @Override
        public void notice(String text) {
            ChatSession.this.notice(text);
        }

        @Override
        public void startPart(Speaker agent, Worktree worktree, List<ChatMessage> snapshot, String editorContext, String header) {
            // A copy's project settings are files the agents themselves can write, so they never
            // load there, whatever IntelliJ thinks of the project.
            start(agent, AgentAccess.WRITE_ISOLATED, Role.PART, snapshot, editorContext, worktree.path(), header);
        }

        @Override
        public void startMerge(Speaker integrator, String header) {
            start(integrator, projectWriteAccess(), Role.MERGE, store.messages(), "", projectRoot, header);
        }

        @Override
        public boolean closed() {
            return disposed;
        }
    }

    private final ConsiliumStateStore store;
    private final Function<Speaker, CliAgent> agents;
    private final IntentClassifier classifier;
    private final Path projectRoot;
    private final BooleanSupplier projectTrusted;
    private final Executor ui;
    private final View view;
    private final Map<Speaker, Run> runs = new EnumMap<>(Speaker.class);
    private final Steps steps;
    private final ParallelTask parallelTask;
    private volatile boolean disposed;

    /**
     * @param projectTrusted whether IntelliJ trusts the project, asked at the start of every write
     *                       turn in it: only then may the agent load the project's own settings
     */
    ChatSession(ConsiliumStateStore store, Function<Speaker, CliAgent> agents, IntentClassifier classifier,
                ParallelWork parallelWork, Path projectRoot, BooleanSupplier projectTrusted, Executor ui,
                Executor background, View view) {
        this.store = store;
        this.agents = agents;
        this.classifier = classifier;
        this.projectRoot = projectRoot;
        this.projectTrusted = projectTrusted;
        this.ui = ui;
        this.view = view;
        this.steps = new Steps(background, this::onUi, () -> view.stateChanged(state()));
        this.parallelTask = new ParallelTask(store, parallelWork, projectRoot, steps, background, new Host());
        parallelTask.restore();
    }

    List<ChatMessage> messages() {
        return store.messages();
    }

    State state() {
        if (!steps.isEmpty()) return State.WORKING;
        if (runs.isEmpty()) return State.READY;
        for (Run run : runs.values()) {
            if (!run.settled) return State.WORKING;
        }
        return State.FINISHING;
    }

    /** Starts a turn; returns false when the room is busy or the text is blank. */
    boolean send(String text, TurnMode mode, String editorContext) {
        if (disposed || state() != State.READY || text == null || text.isBlank()) return false;
        String message = text.trim();
        view.messageAdded(store.add(Speaker.USER, message, MessageStatus.ANSWER));
        if (mode == TurnMode.AUTO) {
            Object step = steps.begin();
            classifier.classify(message, projectRoot).whenComplete((intent, error) -> onUi(() -> {
                if (!steps.end(step)) return;
                act(intent != null ? intent : Intent.discussWithBoth(), editorContext);
            }));
        } else {
            startTurn(mode.agents(), mode.writes() ? projectWriteAccess() : AgentAccess.READ_ONLY, editorContext);
        }
        view.stateChanged(state());
        return true;
    }

    private void act(Intent intent, String editorContext) {
        switch (intent.kind()) {
            case DISCUSS -> startTurn(intent.agents(), AgentAccess.READ_ONLY, editorContext);
            case WORK -> {
                if (intent.agents().size() == 1) {
                    startTurn(intent.agents(), projectWriteAccess(), editorContext);
                } else {
                    parallelTask.start(editorContext);
                }
            }
            case INTEGRATE -> {
                if (store.pendingWork().isEmpty()) {
                    // Nothing of ours to merge: the person means some other branches; it's an ordinary task.
                    startTurn(intent.agents(), projectWriteAccess(), editorContext);
                } else {
                    parallelTask.merge(intent.agents().iterator().next());
                }
            }
            case UNCLEAR -> notice(ConsiliumBundle.message("notice.unclear"));
        }
        view.stateChanged(state());
    }

    /**
     * Write access in the project itself. Its own agent settings (hooks, MCP servers) are files a
     * cloned repository ships, so they load only when IntelliJ trusts the project, the same promise
     * the IDE makes about running the project's build scripts.
     */
    private AgentAccess projectWriteAccess() {
        return projectTrusted.getAsBoolean() ? AgentAccess.WRITE : AgentAccess.WRITE_ISOLATED;
    }

    private void startTurn(Collection<Speaker> speakers, AgentAccess access, String editorContext) {
        List<ChatMessage> snapshot = store.messages();
        for (Speaker speaker : ordered(speakers)) {
            start(speaker, access, Role.TURN, snapshot, editorContext, projectRoot, null);
        }
    }

    // Runs

    private void start(Speaker speaker, AgentAccess access, Role role, List<ChatMessage> snapshot, String editorContext,
                       Path workingDirectory, String writeHeader) {
        AgentSession session = store.session(speaker);
        // A write turn always starts a fresh session with the whole transcript: a clean task
        // context, with permissions set by explicit flags rather than inherited from a discussion
        // session (codex can't set the sandbox when resuming). A stored id the CLI layer wouldn't
        // resume counts as no session here too, or the agent would get a follow-up prompt in a
        // session that has never seen the conversation.
        String resumable = access.writes() ? null : SessionIds.resumable(session.sessionId);
        boolean fresh = resumable == null;
        String prompt = ConversationPromptBuilder.build(snapshot, speaker, session.seenThrough, fresh, editorContext, access, writeHeader);
        Run run = new Run(speaker, access, role, snapshot.size(), resumable, view.answerStarted(speaker));
        runs.put(speaker, run);
        try {
            run.request = agents.apply(speaker).start(workingDirectory, prompt, resumable, access, run);
        } catch (RuntimeException exception) {
            run.exited = true;
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
        run.view.completed(recordAnswer(run));
        release(run);
    }

    private void fail(Run run, String detail) {
        if (run.settled) return;
        recordError(run, detail);
        run.view.failed(detail);
        release(run);
    }

    /** Settles a finished answer in the store and returns the text it stored. */
    private String recordAnswer(Run run) {
        run.settled = true;
        run.outcome = MessageStatus.ANSWER;
        String answer = run.text().trim();
        if (answer.isEmpty()) answer = ConsiliumBundle.message("answer.empty");
        store.add(run.speaker, answer, MessageStatus.ANSWER);
        if (!run.access.writes()) {
            store.updateSession(run.speaker, run.sessionId, run.snapshotSize);
        } else {
            store.resetSession(run.speaker);
        }
        return answer;
    }

    private void recordError(Run run, String detail) {
        run.settled = true;
        run.outcome = MessageStatus.ERROR;
        store.add(run.speaker, detail, MessageStatus.ERROR);
        if (run.access.writes()) store.resetSession(run.speaker);
    }

    /** Keeps what the agent wrote before it was stopped, marked as cut off. */
    private void recordStopped(Run run) {
        run.settled = true;
        run.outcome = MessageStatus.STOPPED;
        // The full text, not the last rendered frame: a frame may still be queued.
        String partial = run.text().trim();
        if (!partial.isEmpty()) store.add(run.speaker, partial, MessageStatus.STOPPED);
        if (run.access.writes()) store.resetSession(run.speaker);
    }

    /** Stops every agent; what they managed to write is kept, marked as cut off. */
    void stop() {
        // Forget the steps first: a cancelled classification completes at once, and its result
        // must find its step gone rather than start a discussion the person just stopped.
        if (steps.forgetAll()) classifier.cancel();
        for (Run run : new ArrayList<>(runs.values())) {
            // cancel() returns only after any callback in flight has finished, so after it the
            // reported outcome is final: a reported answer is left to its queued completion.
            if (run.request != null) run.request.cancel();
            if (run.settled || run.reported != null) continue;
            recordStopped(run);
            run.view.stopped(run.text().trim());
            // Its processes may already be gone; then nothing else would release the run.
            release(run);
        }
        view.stateChanged(state());
    }

    /**
     * A run leaves the room only when its answer is settled AND its processes have exited, in
     * whichever order those happen; until then the room isn't ready for the next turn.
     */
    private void release(Run run) {
        if (run.settled && run.exited && runs.get(run.speaker) == run) {
            runs.remove(run.speaker);
            switch (run.role) {
                case TURN -> {
                }
                case PART -> parallelTask.partEnded(run.speaker, run.outcome, run.text());
                case MERGE -> parallelTask.mergeEnded(run.outcome);
            }
            // Indices of turns in flight are no longer in use, so the transcript can be trimmed.
            if (runs.isEmpty() && steps.isEmpty()) store.compact();
        }
        view.stateChanged(state());
    }

    /**
     * Empties the transcript and forgets both agents' sessions; refused while a turn is running.
     * Unmerged parallel work survives it: the branches stay recorded and the new transcript says so.
     */
    boolean clear() {
        if (state() != State.READY) return false;
        List<PendingWork> parts = store.pendingWork();
        store.clear();
        view.transcriptCleared();
        if (!parts.isEmpty()) {
            store.setPendingWork(parts);
            notice(ConsiliumBundle.message("notice.pending", ParallelTask.branches(parts)));
        }
        return true;
    }

    /**
     * Closing the project stops the agents. An answer that had already finished is stored as its
     * completion would have stored it: that completion is queued for the UI thread and is dropped
     * once the session is closed. An unfinished one keeps its partial text, as a manual Stop does.
     */
    void dispose() {
        if (disposed) return;
        disposed = true;
        classifier.cancel();
        for (Run run : runs.values()) {
            // As in stop(): after cancel() the reported outcome is final.
            if (run.request != null) run.request.cancel();
            if (run.settled) continue;
            if (run.reported == MessageStatus.ANSWER) {
                recordAnswer(run);
            } else if (run.reported == MessageStatus.ERROR) {
                recordError(run, run.errorDetail);
            } else {
                recordStopped(run);
            }
        }
        runs.clear();
    }

    // Helpers

    private void notice(String text) {
        view.messageAdded(store.add(Speaker.CONSILIUM, text, MessageStatus.ANSWER));
    }

    /** Claude first, then Codex: cards appear in a stable order whatever set the classifier returns. */
    private static List<Speaker> ordered(Collection<Speaker> speakers) {
        return List.of(Speaker.CLAUDE, Speaker.CODEX).stream().filter(speakers::contains).toList();
    }

    private void onUi(Runnable action) {
        if (disposed) return;
        ui.execute(() -> {
            if (!disposed) action.run();
        });
    }
}
