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
import java.util.HashSet;
import java.util.Set;
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
    // Steps that keep the room busy without an agent running: reading the message, preparing,
    // committing or removing worktrees. Each step has its own token; Stop and Clear forget them all,
    // so a late result finds its token gone and is dropped instead of freeing the room for a step
    // that is still running, or starting work nobody wants any more.
    private final Set<Object> steps = new HashSet<>();
    // The parts of the parallel task in flight; null when no parallel task is running.
    private List<PendingWork> partsInFlight;
    private final Map<Speaker, MessageStatus> partOutcomes = new EnumMap<>(Speaker.class);
    private final Map<Speaker, String> partReports = new EnumMap<>(Speaker.class);
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
        // Records come back from workspace.xml; only worktrees in Consilium's own folder are trusted.
        List<PendingWork> recorded = store.pendingWork();
        List<PendingWork> waiting = parallelWork.owned(recorded);
        if (waiting.size() != recorded.size()) store.setPendingWork(waiting);
        // After a restart, say once that work is still waiting on its branches.
        if (!waiting.isEmpty()) {
            String text = ConsiliumBundle.message("notice.pending", branches(waiting));
            List<ChatMessage> messages = store.messages();
            boolean alreadySaid = !messages.isEmpty() && text.equals(messages.getLast().textValue());
            if (!alreadySaid) store.add(Speaker.CONSILIUM, text, MessageStatus.ANSWER);
        }
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
            Object step = beginStep();
            classifier.classify(message, projectRoot).whenComplete((intent, error) -> onUi(() -> {
                if (!endStep(step)) return;
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
                    startParallel(editorContext);
                }
            }
            case INTEGRATE -> {
                Speaker integrator = intent.agents().iterator().next();
                if (store.pendingWork().isEmpty()) {
                    // Nothing of ours to merge: the person means some other branches; it's an ordinary task.
                    startTurn(intent.agents(), AgentAccess.WRITE, editorContext);
                } else {
                    mergeWhenReady(integrator);
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

    private void startParallel(String editorContext) {
        List<Speaker> both = List.of(Speaker.CLAUDE, Speaker.CODEX);
        List<PendingWork> earlier = store.pendingWork();
        Object step = beginStep();
        background.execute(() -> {
            // Parts left from an earlier task must be merged first: a new task would bury them.
            List<PendingWork> waiting = parallelWork.stillThere(projectRoot, earlier);
            ParallelWork.Prepared prepared = null;
            String refusal = null;
            if (waiting.isEmpty()) {
                try {
                    prepared = parallelWork.prepare(projectRoot, both);
                } catch (ParallelWork.Refused refused) {
                    refusal = refused.getMessage();
                }
            }
            ParallelWork.Prepared ready = prepared;
            String reason = refusal;
            // Closed while the copies were being made: they hold nothing yet, so they go. If the
            // project closes in the moment between this check and the UI callback, the copies stay
            // unrecorded on disk; git keeps listing them (git worktree list) and nothing is lost.
            if (ready != null && disposed) {
                parallelWork.discard(projectRoot, parts(ready));
                return;
            }
            onUi(() -> {
                if (!endStep(step)) {
                    if (ready != null) discardInBackground(parts(ready));
                    view.stateChanged(state());
                    return;
                }
                if (!waiting.isEmpty()) {
                    store.setPendingWork(waiting);
                    notice(ConsiliumBundle.message("notice.pending", branches(waiting)));
                } else if (ready == null) {
                    store.setPendingWork(waiting);
                    notice(reason);
                } else {
                    runParts(ready, editorContext);
                }
                view.stateChanged(state());
            });
        });
    }

    private void runParts(ParallelWork.Prepared prepared, String editorContext) {
        List<PendingWork> parts = parts(prepared);
        store.setPendingWork(parts);
        partsInFlight = parts;
        partOutcomes.clear();
        partReports.clear();
        if (prepared.uncommittedChangesLeftOut()) notice(ConsiliumBundle.message("notice.parallel.dirty"));
        Worktree claude = prepared.worktrees().get(Speaker.CLAUDE);
        Worktree codex = prepared.worktrees().get(Speaker.CODEX);
        notice(ConsiliumBundle.message("notice.parallel.started", claude.branch(), codex.branch(), DEFAULT_INTEGRATOR.label()));
        List<ChatMessage> snapshot = store.messages();
        for (Speaker agent : List.of(Speaker.CLAUDE, Speaker.CODEX)) {
            Worktree own = prepared.worktrees().get(agent);
            Speaker other = agent == Speaker.CLAUDE ? Speaker.CODEX : Speaker.CLAUDE;
            String header = ConsiliumBundle.message("prompt.part", other.label(), own.branch(), DEFAULT_INTEGRATOR.label());
            start(agent, AgentAccess.WRITE, Role.PART, snapshot, editorContext, own.path(), header);
        }
    }

    /** Both parts are over: commit what each left, then merge if both finished and both have commits. */
    private void partsFinished(List<PendingWork> parts, Map<Speaker, MessageStatus> outcomes, Map<Speaker, String> reports) {
        boolean allAnswered = outcomes.values().stream().allMatch(status -> status == MessageStatus.ANSWER);
        if (!allAnswered) {
            notice(ConsiliumBundle.message("notice.parallel.incomplete", branches(parts)));
            return;
        }
        Object step = beginStep();
        background.execute(() -> {
            parallelWork.commitParts(parts, reports);
            List<Speaker> idle = parallelWork.withoutCommits(parts);
            boolean dirty = idle.isEmpty() && parallelWork.projectIsDirty(projectRoot);
            onUi(() -> {
                if (!endStep(step)) {
                    view.stateChanged(state());
                    return;
                }
                if (!idle.isEmpty()) {
                    String who = idle.stream().map(Speaker::label).collect(Collectors.joining(", "));
                    notice(ConsiliumBundle.message("notice.parallel.no.commits", who, branches(parts)));
                } else if (dirty) {
                    notice(ConsiliumBundle.message("notice.merge.dirty", branches(parts)));
                } else {
                    startMerge(DEFAULT_INTEGRATOR, parts);
                }
                view.stateChanged(state());
            });
        });
    }

    /**
     * "Merge": commit whatever a stopped or failed part left in its copy (agents never commit there),
     * check the branches are still there and the project is clean, then merge the parts that hold work.
     */
    private void mergeWhenReady(Speaker integrator) {
        List<PendingWork> recorded = store.pendingWork();
        Object step = beginStep();
        background.execute(() -> {
            List<PendingWork> present = parallelWork.stillThere(projectRoot, recorded);
            parallelWork.commitParts(present, Map.of());
            List<Speaker> idle = parallelWork.withoutCommits(present);
            List<PendingWork> withWork = present.stream().filter(part -> !idle.contains(part.agentValue())).toList();
            boolean dirty = !withWork.isEmpty() && parallelWork.projectIsDirty(projectRoot);
            onUi(() -> {
                if (!endStep(step)) {
                    view.stateChanged(state());
                    return;
                }
                store.setPendingWork(present);
                if (withWork.isEmpty()) {
                    notice(ConsiliumBundle.message("notice.merge.nothing"));
                } else if (dirty) {
                    notice(ConsiliumBundle.message("notice.merge.dirty", branches(withWork)));
                } else {
                    startMerge(integrator, withWork);
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
        Object step = beginStep();
        background.execute(() -> {
            List<PendingWork> kept = parallelWork.cleanUp(projectRoot, parts);
            onUi(() -> {
                // What is on disk is recorded even after Stop: the copies are gone either way.
                store.setPendingWork(kept);
                if (endStep(step)) {
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
        if (!steps.isEmpty()) {
            // Forget the steps first: a cancelled classification completes at once, and its result
            // must find its step gone rather than start a discussion the person just stopped.
            steps.clear();
            classifier.cancel();
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
            if (runs.isEmpty() && steps.isEmpty()) store.compact();
        }
        view.stateChanged(state());
    }

    private void afterRun(Run run) {
        switch (run.role) {
            case TURN -> {
            }
            case PART -> {
                partOutcomes.put(run.speaker, run.outcome);
                partReports.put(run.speaker, run.text());
                if (partsInFlight != null && partOutcomes.size() == partsInFlight.size()) {
                    List<PendingWork> parts = partsInFlight;
                    Map<Speaker, MessageStatus> outcomes = new EnumMap<>(partOutcomes);
                    Map<Speaker, String> reports = new EnumMap<>(partReports);
                    partsInFlight = null;
                    partOutcomes.clear();
                    partReports.clear();
                    partsFinished(parts, outcomes, reports);
                }
            }
            case MERGE -> merged(run);
        }
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
            notice(ConsiliumBundle.message("notice.pending", branches(parts)));
        }
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

    private Object beginStep() {
        Object step = new Object();
        steps.add(step);
        return step;
    }

    /** False when the step was forgotten by Stop or Clear: its result must be dropped. */
    private boolean endStep(Object step) {
        return steps.remove(step);
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
