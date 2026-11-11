package dev.katherine.consilium;

import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.MessageStatus;
import dev.katherine.consilium.model.PendingWork;
import dev.katherine.consilium.model.Speaker;
import dev.katherine.consilium.work.Worktree;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Parallel work from "go ahead" to the merge: a worktree per agent, each part committed by
 * Consilium when its agent is done, then one agent merges the branches into the project and the
 * copies go. Keeps which parts are in flight and how each ended; {@link ChatSession} runs the
 * agents and owns the room's state.
 *
 * <p>Confined to the UI thread, like the session; git work runs as {@link Steps} in the background.
 */
final class ParallelTask {
    /** What the task needs from the session. */
    interface Host {
        void notice(String text);

        void startPart(Speaker agent, Worktree worktree, List<ChatMessage> snapshot, String editorContext, String header);

        void startMerge(Speaker integrator, String header);

        /** True once the project is closing; safe to ask from any thread. */
        boolean closed();
    }

    /** Who merges the parallel parts when the person didn't name anyone. */
    static final Speaker DEFAULT_INTEGRATOR = Speaker.CLAUDE;
    private static final List<Speaker> BOTH = List.of(Speaker.CLAUDE, Speaker.CODEX);

    /** What preparing a new task found: earlier parts still waiting, fresh worktrees, or why it can't start. */
    private record Attempt(List<PendingWork> waiting, ParallelWork.Prepared prepared, String refusal) {
    }

    /** What committing the finished parts found, in the order the merge checks it. */
    private record Committed(List<ParallelWork.Held> held, List<Speaker> idle, boolean projectDirty) {
    }

    /** The parts that hold work, ready for "merge", and whether the project is clean enough for it. */
    private record Mergeable(List<PendingWork> present, List<PendingWork> withWork, List<ParallelWork.Held> held, boolean projectDirty) {
    }

    private final ConsiliumStateStore store;
    private final ParallelWork parallelWork;
    private final Path projectRoot;
    private final Steps steps;
    private final Executor background;
    private final Host host;
    // The parts of the task in flight; null when no parallel task is running.
    private List<PendingWork> partsInFlight;
    private final Map<Speaker, MessageStatus> partOutcomes = new EnumMap<>(Speaker.class);
    private final Map<Speaker, String> partReports = new EnumMap<>(Speaker.class);
    // Branches the person was told need a look before merging; their next "merge" is the go-ahead.
    // Kept in memory only: after a restart the person is told once more, which errs on the safe side.
    private final Set<String> reviewAnnounced = new HashSet<>();

    ParallelTask(ConsiliumStateStore store, ParallelWork parallelWork, Path projectRoot, Steps steps, Executor background, Host host) {
        this.store = store;
        this.parallelWork = parallelWork;
        this.projectRoot = projectRoot;
        this.steps = steps;
        this.background = background;
        this.host = host;
    }

    /**
     * After a restart: forgets records of branches that are gone (the person may have merged or
     * deleted them meanwhile) and says once that the rest is still waiting. Records pointing outside
     * Consilium's folder are dropped at once, before any step could hand them to git; asking git
     * which branches remain blocks, so that runs as a step.
     */
    void restore() {
        List<PendingWork> recorded = store.pendingWork();
        List<PendingWork> owned = parallelWork.owned(recorded);
        if (owned.size() != recorded.size()) store.setPendingWork(owned);
        if (owned.isEmpty()) return;
        // Stopped before git answered, the records stay as they are: the next "merge" checks them again.
        steps.run(() -> parallelWork.stillThere(projectRoot, owned), present -> {
            store.setPendingWork(present);
            if (present.isEmpty()) return;
            String text = ConsiliumBundle.message("notice.pending", branches(present));
            List<ChatMessage> messages = store.messages();
            boolean alreadySaid = !messages.isEmpty() && text.equals(messages.getLast().textValue());
            if (!alreadySaid) host.notice(text);
        });
    }

    /** "Go ahead" to both: a worktree each, unless earlier parts still wait to be merged. */
    void start(String editorContext) {
        List<PendingWork> earlier = store.pendingWork();
        steps.run(() -> prepare(earlier), attempt -> {
            if (!attempt.waiting().isEmpty()) {
                store.setPendingWork(attempt.waiting());
                host.notice(ConsiliumBundle.message("notice.pending", branches(attempt.waiting())));
            } else if (attempt.prepared() == null) {
                store.setPendingWork(attempt.waiting());
                host.notice(attempt.refusal());
            } else {
                runParts(attempt.prepared(), editorContext);
            }
        }, attempt -> {
            if (attempt.prepared() != null) discardInBackground(parts(attempt.prepared()));
        });
    }

    private Attempt prepare(List<PendingWork> earlier) {
        // Parts left from an earlier task must be merged first: a new task would bury them.
        List<PendingWork> waiting = parallelWork.stillThere(projectRoot, earlier);
        if (!waiting.isEmpty()) return new Attempt(waiting, null, null);
        ParallelWork.Prepared prepared;
        try {
            prepared = parallelWork.prepare(projectRoot, BOTH);
        } catch (ParallelWork.Refused refused) {
            return new Attempt(waiting, null, refused.getMessage());
        }
        // Closed while the copies were being made: they hold nothing yet, so they go, and the
        // result never reaches the closed session. If the project closes in the moment after this
        // check, the copies stay unrecorded on disk; git keeps listing them (git worktree list)
        // and nothing is lost.
        if (host.closed()) parallelWork.discard(projectRoot, parts(prepared));
        return new Attempt(waiting, prepared, null);
    }

    private void runParts(ParallelWork.Prepared prepared, String editorContext) {
        List<PendingWork> parts = parts(prepared);
        store.setPendingWork(parts);
        partsInFlight = parts;
        partOutcomes.clear();
        partReports.clear();
        if (prepared.uncommittedChangesLeftOut()) host.notice(ConsiliumBundle.message("notice.parallel.dirty"));
        Worktree claude = prepared.worktrees().get(Speaker.CLAUDE);
        Worktree codex = prepared.worktrees().get(Speaker.CODEX);
        host.notice(ConsiliumBundle.message("notice.parallel.started", claude.branch(), codex.branch(), DEFAULT_INTEGRATOR.label()));
        List<ChatMessage> snapshot = store.messages();
        for (Speaker agent : BOTH) {
            Worktree own = prepared.worktrees().get(agent);
            Speaker other = agent == Speaker.CLAUDE ? Speaker.CODEX : Speaker.CLAUDE;
            String header = ConsiliumBundle.message("prompt.part", other.label(), own.branch(), DEFAULT_INTEGRATOR.label());
            host.startPart(agent, own, snapshot, editorContext, header);
        }
    }

    /** One agent's part is over, its processes gone; once both are, the parts are committed. */
    void partEnded(Speaker agent, MessageStatus outcome, String report) {
        partOutcomes.put(agent, outcome);
        partReports.put(agent, report);
        if (partsInFlight == null || partOutcomes.size() != partsInFlight.size()) return;
        List<PendingWork> parts = partsInFlight;
        Map<Speaker, MessageStatus> outcomes = new EnumMap<>(partOutcomes);
        Map<Speaker, String> reports = new EnumMap<>(partReports);
        partsInFlight = null;
        partOutcomes.clear();
        partReports.clear();
        partsFinished(parts, outcomes, reports);
    }

    /** Both parts are over: commit what each left, then merge if both finished and both have commits. */
    private void partsFinished(List<PendingWork> parts, Map<Speaker, MessageStatus> outcomes, Map<Speaker, String> reports) {
        boolean allAnswered = outcomes.values().stream().allMatch(status -> status == MessageStatus.ANSWER);
        if (!allAnswered) {
            host.notice(ConsiliumBundle.message("notice.parallel.incomplete", branches(parts)));
            return;
        }
        steps.run(() -> {
            parallelWork.commitParts(parts, reports);
            // Checked before the commits are counted: a part holding another repository is never
            // committed, and "no commits" would hide why.
            List<ParallelWork.Held> held = parallelWork.needingReview(projectRoot, parts);
            List<Speaker> idle = held.isEmpty() ? parallelWork.withoutCommits(projectRoot, parts) : List.of();
            boolean dirty = held.isEmpty() && idle.isEmpty() && parallelWork.projectIsDirty(projectRoot);
            return new Committed(held, idle, dirty);
        }, committed -> {
            if (!committed.held().isEmpty()) {
                // Merging would run hooks or settings the agents changed, outside any sandbox: the person looks first.
                announceReview(committed.held());
            } else if (!committed.idle().isEmpty()) {
                String who = committed.idle().stream().map(Speaker::label).collect(Collectors.joining(", "));
                host.notice(ConsiliumBundle.message("notice.parallel.no.commits", who, branches(parts)));
            } else if (committed.projectDirty()) {
                host.notice(ConsiliumBundle.message("notice.merge.dirty", branches(parts)));
            } else {
                startMerge(DEFAULT_INTEGRATOR, parts);
            }
        });
    }

    /**
     * "Merge": commit whatever a stopped or failed part left in its copy (agents never commit there),
     * check the branches are still there and the project is clean, then merge the parts that hold work.
     */
    void merge(Speaker integrator) {
        List<PendingWork> recorded = store.pendingWork();
        steps.run(() -> {
            List<PendingWork> present = parallelWork.stillThere(projectRoot, recorded);
            parallelWork.commitParts(present, Map.of());
            List<Speaker> idle = parallelWork.withoutCommits(projectRoot, present);
            List<PendingWork> withWork = present.stream().filter(part -> !idle.contains(part.agentValue())).toList();
            List<ParallelWork.Held> held = parallelWork.needingReview(projectRoot, withWork);
            boolean dirty = !withWork.isEmpty() && parallelWork.projectIsDirty(projectRoot);
            return new Mergeable(present, withWork, held, dirty);
        }, mergeable -> {
            store.setPendingWork(mergeable.present());
            // "Merge" is the person's go-ahead only for what they were told about: a stopped part
            // never went through the check after the parts finished, so it is told here first.
            List<ParallelWork.Held> untold = mergeable.held().stream()
                    .filter(held -> !reviewAnnounced.contains(held.part().branch))
                    .toList();
            if (mergeable.withWork().isEmpty()) {
                host.notice(ConsiliumBundle.message("notice.merge.nothing"));
            } else if (!untold.isEmpty()) {
                announceReview(untold);
            } else if (mergeable.projectDirty()) {
                host.notice(ConsiliumBundle.message("notice.merge.dirty", branches(mergeable.withWork())));
            } else {
                startMerge(integrator, mergeable.withWork());
            }
        });
    }

    private void announceReview(List<ParallelWork.Held> held) {
        for (ParallelWork.Held part : held) {
            reviewAnnounced.add(part.part().branch);
            host.notice(reviewNotice(part));
        }
    }

    static String reviewNotice(ParallelWork.Held held) {
        String branch = held.part().branch;
        return switch (held.reason()) {
            case HOOKS -> ConsiliumBundle.message("notice.merge.hooks", branch, held.path());
            case AGENT_SETTINGS -> ConsiliumBundle.message("notice.merge.agent.settings", branch, held.path());
            case NESTED_REPOSITORY -> ConsiliumBundle.message("notice.merge.nested", branch, held.path());
            case UNCHECKED -> ConsiliumBundle.message("notice.merge.unchecked", branch);
        };
    }

    private void startMerge(Speaker integrator, List<PendingWork> parts) {
        host.notice(ConsiliumBundle.message("notice.merge.started", integrator.label(), branches(parts)));
        host.startMerge(integrator, ConsiliumBundle.message("prompt.merge", describe(parts)));
    }

    /** The merging agent is done; after a clean answer the copies of the merged parts go. */
    void mergeEnded(MessageStatus outcome) {
        List<PendingWork> parts = store.pendingWork();
        if (outcome != MessageStatus.ANSWER) {
            host.notice(ConsiliumBundle.message("notice.merge.failed", branches(parts)));
            return;
        }
        steps.run(() -> parallelWork.cleanUp(projectRoot, parts), kept -> {
            store.setPendingWork(kept);
            host.notice(kept.isEmpty()
                    ? ConsiliumBundle.message("notice.merge.cleaned")
                    : ConsiliumBundle.message("notice.merge.kept", branches(kept)));
        }, kept -> {
            // What is on disk is recorded even after Stop: the copies are gone either way.
            store.setPendingWork(kept);
        });
    }

    private void discardInBackground(List<PendingWork> parts) {
        background.execute(() -> parallelWork.discard(projectRoot, parts));
    }

    private static List<PendingWork> parts(ParallelWork.Prepared prepared) {
        List<PendingWork> parts = new ArrayList<>();
        prepared.worktrees().forEach((agent, worktree) -> parts.add(ParallelWork.pending(agent, worktree)));
        return parts;
    }

    static String branches(List<PendingWork> parts) {
        return parts.stream().map(part -> part.branch).collect(Collectors.joining(", "));
    }

    private static String describe(List<PendingWork> parts) {
        return parts.stream()
                .map(part -> part.agentValue().label() + ": " + part.branch)
                .collect(Collectors.joining("; "));
    }
}
