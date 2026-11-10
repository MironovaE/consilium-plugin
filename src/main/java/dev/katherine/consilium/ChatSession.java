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
import java.util.stream.Collectors;
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
 *
 * <p>In {@link TurnMode#AUTO} the person just writes. What the message asks for (discussion, work
 * by one agent, work by both, merging) is read from the person's words alone by the
 * {@link IntentClassifier}; agent text never grants write access. When both agents work, each
 * does its agreed part in its own git worktree, and Claude then merges the branches.
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
        // Set on the agent's thread as soon as it reports an outcome, before the UI hears of it:
        // Stop pressed in between must not turn a finished answer into a cut-off one.
        private volatile boolean outcomeReported;
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
            outcomeReported = true;
            onUi(() -> complete(this));
        }

        @Override
        public void onError(String message) {
            outcomeReported = true;
            onUi(() -> fail(this, message));
        }
    }

    /** Who merges the parallel parts when the person didn't name anyone. */
    static final Speaker DEFAULT_INTEGRATOR = Speaker.CLAUDE;

    private final ConsiliumStateStore store;
    private final Function<Speaker, CliAgent> agents;
    private final IntentClassifier classifier;
    private final ParallelWork parallelWork;
    private final Path projectRoot;
    private final Executor ui;
    private final Executor background;
    private final View view;
    private final Map<Speaker, Run> runs = new EnumMap<>(Speaker.class);
    // Steps that keep the room busy without an agent running: reading the message, preparing or
    // inspecting worktrees. Each async step checks the generation it started in; Stop and Clear bump
    // it, so a late result is dropped instead of starting work nobody wants any more.
    private int pendingSteps;
    private int generation;
    // The parts of the parallel task in flight; null when no parallel task is running.
    private List<PendingWork> partsInFlight;
    private final Map<Speaker, MessageStatus> partOutcomes = new EnumMap<>(Speaker.class);
    private volatile boolean disposed;

    ChatSession(ConsiliumStateStore store, Function<Speaker, CliAgent> agents, IntentClassifier classifier,
                ParallelWork parallelWork, Path projectRoot, Executor ui, Executor background, View view) {
        this.store = store;
        this.agents = agents;
        this.classifier = classifier;
        this.parallelWork = parallelWork;
        this.projectRoot = projectRoot;
        this.ui = ui;
        this.background = background;
        this.view = view;
    }

    List<ChatMessage> messages() {
        return store.messages();
    }

    State state() {
        if (pendingSteps > 0) return State.WORKING;
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
            int started = generation;
            beginStep();
            classifier.classify(message, projectRoot).whenComplete((intent, error) -> onUi(() -> {
                if (started != generation) return;
                endStep();
                act(intent != null ? intent : Intent.discussWithBoth(), editorContext);
            }));
        } else {
            startTurn(mode.agents(), mode.access(), editorContext);
        }
        view.stateChanged(state());
        return true;
    }

    private void act(Intent intent, String editorContext) {
        switch (intent.kind()) {
            case DISCUSS -> startTurn(intent.agents(), AgentAccess.READ_ONLY, editorContext);
            case WORK -> {
                if (intent.agents().size() == 1) {
                    startTurn(intent.agents(), AgentAccess.WRITE, editorContext);
                } else {
                    startParallel();
                }
            }
            case INTEGRATE -> {
                Speaker integrator = intent.agents().iterator().next();
                List<PendingWork> parts = store.pendingWork();
                if (parts.isEmpty()) {
                    // Nothing of ours to merge: the person means some other branches; it's an ordinary task.
                    startTurn(intent.agents(), AgentAccess.WRITE, editorContext);
                } else {
                    startMerge(integrator, parts);
                }
            }
            case UNCLEAR -> notice(ConsiliumBundle.message("notice.unclear"));
        }
        view.stateChanged(state());
    }

    private void startTurn(Collection<Speaker> speakers, AgentAccess access, String editorContext) {
        List<ChatMessage> snapshot = store.messages();
        for (Speaker speaker : ordered(speakers)) {
            start(speaker, access, Role.TURN, snapshot, editorContext, projectRoot, null);
        }
    }

    // Parallel work

    private void startParallel() {
        List<Speaker> both = List.of(Speaker.CLAUDE, Speaker.CODEX);
        int started = generation;
        beginStep();
        background.execute(() -> {
            ParallelWork.Prepared prepared = null;
            String refusal = null;
            try {
                prepared = parallelWork.prepare(projectRoot, both);
            } catch (ParallelWork.Refused refused) {
                refusal = refused.getMessage();
            }
            ParallelWork.Prepared ready = prepared;
            String reason = refusal;
            onUi(() -> {
                endStep();
                if (started != generation) {
                    // Stopped while the copies were being made: they hold nothing yet, so they go.
                    if (ready != null) discardInBackground(parts(ready));
                    view.stateChanged(state());
                    return;
                }
                if (ready == null) {
                    notice(reason);
                } else {
                    runParts(ready);
                }
                view.stateChanged(state());
            });
        });
    }

    private void runParts(ParallelWork.Prepared prepared) {
        List<PendingWork> parts = parts(prepared);
        store.setPendingWork(parts);
        partsInFlight = parts;
        if (prepared.uncommittedChangesLeftOut()) notice(ConsiliumBundle.message("notice.parallel.dirty"));
        Worktree claude = prepared.worktrees().get(Speaker.CLAUDE);
        Worktree codex = prepared.worktrees().get(Speaker.CODEX);
        notice(ConsiliumBundle.message("notice.parallel.started", claude.branch(), codex.branch(), DEFAULT_INTEGRATOR.label()));
        List<ChatMessage> snapshot = store.messages();
        for (Speaker agent : List.of(Speaker.CLAUDE, Speaker.CODEX)) {
            Worktree own = prepared.worktrees().get(agent);
            Speaker other = agent == Speaker.CLAUDE ? Speaker.CODEX : Speaker.CLAUDE;
            String header = ConsiliumBundle.message("prompt.part", other.label(), own.branch(), DEFAULT_INTEGRATOR.label());
            start(agent, AgentAccess.WRITE, Role.PART, snapshot, "", own.path(), header);
        }
    }

    /** Both parts are over: merge them if both finished with commits, otherwise say what's left. */
    private void partsFinished(List<PendingWork> parts, Map<Speaker, MessageStatus> outcomes) {
        boolean allAnswered = outcomes.values().stream().allMatch(status -> status == MessageStatus.ANSWER);
        if (!allAnswered) {
            notice(ConsiliumBundle.message("notice.parallel.incomplete", branches(parts)));
            return;
        }
        int started = generation;
        beginStep();
        background.execute(() -> {
            List<Speaker> idle = parallelWork.withoutCommits(parts);
            onUi(() -> {
                endStep();
                if (started != generation) {
                    view.stateChanged(state());
                    return;
                }
                if (idle.isEmpty()) {
                    startMerge(DEFAULT_INTEGRATOR, parts);
                } else {
                    String who = idle.stream().map(Speaker::label).collect(Collectors.joining(", "));
                    notice(ConsiliumBundle.message("notice.parallel.no.commits", who, branches(parts)));
                }
                view.stateChanged(state());
            });
        });
    }

    private void startMerge(Speaker integrator, List<PendingWork> parts) {
        notice(ConsiliumBundle.message("notice.merge.started", integrator.label(), branches(parts)));
        String header = ConsiliumBundle.message("prompt.merge", describe(parts));
        start(integrator, AgentAccess.WRITE, Role.MERGE, store.messages(), "", projectRoot, header);
    }

    private void merged(Run run) {
        List<PendingWork> parts = store.pendingWork();
        if (run.outcome != MessageStatus.ANSWER) {
            notice(ConsiliumBundle.message("notice.merge.failed", branches(parts)));
            return;
        }
        int started = generation;
        beginStep();
        background.execute(() -> {
            List<PendingWork> kept = parallelWork.cleanUp(projectRoot, parts);
            onUi(() -> {
                endStep();
                if (started == generation) {
                    store.setPendingWork(kept);
                    notice(kept.isEmpty()
                            ? ConsiliumBundle.message("notice.merge.cleaned")
                            : ConsiliumBundle.message("notice.merge.kept", branches(kept)));
                }
                view.stateChanged(state());
            });
        });
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
        String resumable = access == AgentAccess.WRITE ? null : SessionIds.resumable(session.sessionId);
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
        run.settled = true;
        run.outcome = MessageStatus.ANSWER;
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
        run.outcome = MessageStatus.ERROR;
        store.add(run.speaker, detail, MessageStatus.ERROR);
        if (run.access == AgentAccess.WRITE) store.resetSession(run.speaker);
        run.view.failed(detail);
        release(run);
    }

    /** Stops every agent; what they managed to write is kept, marked as cut off. */
    void stop() {
        generation++;
        if (pendingSteps > 0) {
            classifier.cancel();
            pendingSteps = 0;
        }
        for (Run run : new ArrayList<>(runs.values())) {
            // cancel() returns only after any callback in flight has finished, so after it
            // outcomeReported is final: a reported answer is left to its queued completion.
            if (run.request != null) run.request.cancel();
            if (run.settled || run.outcomeReported) continue;
            run.settled = true;
            run.outcome = MessageStatus.STOPPED;
            keepPartial(run);
            run.view.stopped(run.text().trim());
            // Its processes may already be gone; then nothing else would release the run.
            release(run);
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
            afterRun(run);
            // Indices of turns in flight are no longer in use, so the transcript can be trimmed.
            if (runs.isEmpty() && pendingSteps == 0) store.compact();
        }
        view.stateChanged(state());
    }

    private void afterRun(Run run) {
        switch (run.role) {
            case TURN -> {
            }
            case PART -> {
                partOutcomes.put(run.speaker, run.outcome);
                if (partsInFlight != null && partOutcomes.size() == partsInFlight.size()) {
                    List<PendingWork> parts = partsInFlight;
                    Map<Speaker, MessageStatus> outcomes = new EnumMap<>(partOutcomes);
                    partsInFlight = null;
                    partOutcomes.clear();
                    partsFinished(parts, outcomes);
                }
            }
            case MERGE -> merged(run);
        }
    }

    /** Empties the transcript and forgets both agents' sessions; refused while a turn is running. */
    boolean clear() {
        if (state() != State.READY) return false;
        generation++;
        List<PendingWork> parts = store.pendingWork();
        store.clear();
        if (!parts.isEmpty()) discardInBackground(parts);
        view.transcriptCleared();
        return true;
    }

    /** Closing the project stops the agents and keeps partial answers, as a manual Stop does. */
    void dispose() {
        disposed = true;
        classifier.cancel();
        for (Run run : runs.values()) {
            if (run.request != null) run.request.cancel();
            if (run.settled) continue;
            run.settled = true;
            keepPartial(run);
        }
        runs.clear();
    }

    // Helpers

    private void notice(String text) {
        view.messageAdded(store.add(Speaker.CONSILIUM, text, MessageStatus.ANSWER));
    }

    private void beginStep() {
        pendingSteps++;
    }

    private void endStep() {
        if (pendingSteps > 0) pendingSteps--;
    }

    private void discardInBackground(List<PendingWork> parts) {
        background.execute(() -> parallelWork.discard(projectRoot, parts));
    }

    private static List<PendingWork> parts(ParallelWork.Prepared prepared) {
        List<PendingWork> parts = new ArrayList<>();
        prepared.worktrees().forEach((agent, worktree) -> parts.add(ParallelWork.pending(agent, worktree)));
        return parts;
    }

    private static String branches(List<PendingWork> parts) {
        return parts.stream().map(part -> part.branch).collect(Collectors.joining(", "));
    }

    private static String describe(List<PendingWork> parts) {
        return parts.stream()
                .map(part -> part.agentValue().label() + ": " + part.branch)
                .collect(Collectors.joining("; "));
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
