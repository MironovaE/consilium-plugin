package dev.katherine.consilium;

import com.intellij.openapi.diagnostic.Logger;
import dev.katherine.consilium.model.PendingWork;
import dev.katherine.consilium.model.Speaker;
import dev.katherine.consilium.work.GitWorktrees;
import dev.katherine.consilium.work.Worktree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The git side of parallel work: a worktree per agent before the turn, a look at what each agent
 * committed after it, and removal once the parts are merged. Every method blocks on git, so the
 * session calls them on a background thread.
 */
final class ParallelWork {
    private static final Logger LOG = Logger.getInstance(ParallelWork.class);

    /** Worktrees ready for a parallel turn. */
    record Prepared(Path repository, boolean uncommittedChangesLeftOut, Map<Speaker, Worktree> worktrees) {
    }

    /** Parallel work can't start; the message says why, in the person's language. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private final GitWorktrees git;

    ParallelWork(GitWorktrees git) {
        this.git = git;
    }

    Prepared prepare(Path projectRoot, List<Speaker> agents) throws Refused {
        Path repository;
        try {
            repository = git.repositoryRoot(projectRoot);
        } catch (IOException exception) {
            throw new Refused(ConsiliumBundle.message("notice.parallel.failed", exception.getMessage()));
        }
        if (repository == null) throw new Refused(ConsiliumBundle.message("notice.parallel.no.git"));
        Map<Speaker, Worktree> created = new EnumMap<>(Speaker.class);
        try {
            boolean dirty = git.hasUncommittedChanges(repository);
            for (Speaker agent : agents) {
                created.put(agent, git.create(repository, agent.name().toLowerCase(Locale.ROOT)));
            }
            return new Prepared(repository, dirty, created);
        } catch (IOException exception) {
            // Half a setup is worse than none: an agent without its copy would have nowhere to work.
            for (Worktree worktree : created.values()) removeQuietly(repository, worktree);
            throw new Refused(ConsiliumBundle.message("notice.parallel.failed", exception.getMessage()));
        }
    }

    /** Agents that committed nothing on their branch, in the order given. */
    List<Speaker> withoutCommits(List<PendingWork> parts) {
        List<Speaker> idle = new ArrayList<>();
        for (PendingWork part : parts) {
            try {
                if (git.commitsSinceCreated(worktree(part)).isEmpty()) idle.add(part.agentValue());
            } catch (IOException exception) {
                LOG.warn("Couldn't list the commits on " + part.branch, exception);
                idle.add(part.agentValue());
            }
        }
        return idle;
    }

    /**
     * Removes the worktrees of merged parts. A worktree with changes its agent didn't commit is
     * kept, and so is a branch that isn't merged; nothing an agent did is thrown away silently.
     *
     * @return the parts that were kept
     */
    List<PendingWork> cleanUp(Path projectRoot, List<PendingWork> parts) {
        Path repository = repositoryOrNull(projectRoot);
        if (repository == null) return parts;
        List<PendingWork> kept = new ArrayList<>();
        for (PendingWork part : parts) {
            try {
                Worktree worktree = worktree(part);
                if (git.hasUncommittedChanges(worktree)) {
                    kept.add(part);
                    continue;
                }
                if (!git.remove(repository, worktree)) kept.add(part);
            } catch (IOException exception) {
                LOG.warn("Couldn't remove the worktree of " + part.branch, exception);
                kept.add(part);
            }
        }
        return kept;
    }

    /** Clear: the person asked to forget everything, so worktrees go even with uncommitted changes. */
    void discard(Path projectRoot, List<PendingWork> parts) {
        Path repository = repositoryOrNull(projectRoot);
        if (repository == null) return;
        for (PendingWork part : parts) removeQuietly(repository, worktree(part));
    }

    private Path repositoryOrNull(Path projectRoot) {
        try {
            return git.repositoryRoot(projectRoot);
        } catch (IOException exception) {
            LOG.warn("Couldn't find the git repository of " + projectRoot, exception);
            return null;
        }
    }

    private void removeQuietly(Path repository, Worktree worktree) {
        try {
            git.remove(repository, worktree);
        } catch (IOException exception) {
            LOG.warn("Couldn't remove the worktree " + worktree.path(), exception);
        }
    }

    static PendingWork pending(Speaker agent, Worktree worktree) {
        return new PendingWork(agent, worktree.path().toString(), worktree.branch(), worktree.baseCommit());
    }

    static Worktree worktree(PendingWork part) {
        return new Worktree(Path.of(part.path), part.branch, part.baseCommit);
    }
}
