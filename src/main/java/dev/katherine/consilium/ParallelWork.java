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
    record Prepared(boolean uncommittedChangesLeftOut, Map<Speaker, Worktree> worktrees) {
    }

    /** Parallel work can't start; the message says why, in the person's language. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private static final int MAX_REPORT_IN_MESSAGE = 2000;
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
            return new Prepared(dirty, created);
        } catch (IOException exception) {
            // Half a setup is worse than none: an agent without its copy would have nowhere to work.
            for (Worktree worktree : created.values()) removeQuietly(repository, worktree);
            throw new Refused(ConsiliumBundle.message("notice.parallel.failed", exception.getMessage()));
        }
    }

    /**
     * Commits what each agent left in its worktree. The agents don't commit themselves: Codex can't
     * without write access to the repository's .git, which would let it escape its sandbox, and the
     * merging agent rewrites these commits by the repository's conventions anyway.
     */
    void commitParts(List<PendingWork> parts, Map<Speaker, String> reports) {
        for (PendingWork part : parts) {
            Speaker agent = part.agentValue();
            String report = reports.getOrDefault(agent, "").strip();
            if (report.length() > MAX_REPORT_IN_MESSAGE) report = report.substring(0, MAX_REPORT_IN_MESSAGE) + "…";
            String message = "chore(consilium): " + agent.name().toLowerCase(Locale.ROOT) + " part of a parallel task"
                    + (report.isEmpty() ? "" : "\n\n" + report);
            try {
                git.commitAll(worktree(part), message);
            } catch (IOException exception) {
                LOG.warn("Couldn't commit the part on " + part.branch, exception);
            }
        }
    }

    /** The parts whose worktrees lie where Consilium makes them; anything else came from a tampered record. */
    List<PendingWork> owned(List<PendingWork> parts) {
        List<PendingWork> owned = new ArrayList<>();
        for (PendingWork part : parts) {
            if (git.owns(Path.of(part.path))) owned.add(part);
        }
        return owned;
    }

    /**
     * The parts whose branches still exist; the person may have merged or deleted some by hand. If
     * git can't be asked right now, every part is kept: a passing failure must not erase the record.
     */
    List<PendingWork> stillThere(Path projectRoot, List<PendingWork> parts) {
        Path repository = repositoryOrNull(projectRoot);
        if (repository == null) return parts;
        List<PendingWork> present = new ArrayList<>();
        for (PendingWork part : parts) {
            try {
                if (git.branchExists(repository, part.branch)) present.add(part);
            } catch (IOException exception) {
                LOG.warn("Couldn't check the branch " + part.branch, exception);
                present.add(part);
            }
        }
        return present;
    }

    /** True when the project has uncommitted changes, which a merge must not touch. */
    boolean projectIsDirty(Path projectRoot) {
        Path repository = repositoryOrNull(projectRoot);
        if (repository == null) return false;
        try {
            return git.hasUncommittedChanges(repository);
        } catch (IOException exception) {
            LOG.warn("Couldn't check the project for uncommitted changes", exception);
            return true;
        }
    }

    /**
     * Files that configure git hooks or hook runners. A part that changes one is not merged
     * automatically: merging runs the project's hooks outside any sandbox, so the person looks first.
     */
    private static final List<String> HOOK_CONFIG = List.of(
            ".husky/", ".githooks/", ".git-hooks/", ".hooks/", ".pre-commit-config.yaml", ".pre-commit-hooks.yaml",
            "lefthook.yml", "lefthook.yaml", ".lefthook.yml", ".lefthook.yaml", "lefthook-local.yml",
            ".lefthook-local.yml", ".simple-git-hooks.json", ".simple-git-hooks.js", ".overcommit.yml");

    /** Parts that change hook configuration, in the order given; unknown counts as yes. */
    List<PendingWork> touchingHooks(Path projectRoot, List<PendingWork> parts) {
        Path repository = repositoryOrNull(projectRoot);
        if (repository == null) return parts;
        List<PendingWork> touching = new ArrayList<>();
        for (PendingWork part : parts) {
            try {
                List<String> files = git.changedFiles(repository, worktree(part));
                boolean hooks = files.stream().anyMatch(file -> HOOK_CONFIG.stream().anyMatch(
                        config -> config.endsWith("/") ? file.startsWith(config) || file.contains("/" + config)
                                : file.equals(config) || file.endsWith("/" + config)));
                // package.json can declare hooks too (husky v4, simple-git-hooks), so it counts.
                if (hooks || files.stream().anyMatch(file -> file.equals("package.json") || file.endsWith("/package.json"))) {
                    touching.add(part);
                }
            } catch (IOException exception) {
                LOG.warn("Couldn't list the files changed on " + part.branch, exception);
                touching.add(part);
            }
        }
        return touching;
    }

    /** Agents that committed nothing on their branch, in the order given; unknown counts as nothing. */
    List<Speaker> withoutCommits(Path projectRoot, List<PendingWork> parts) {
        Path repository = repositoryOrNull(projectRoot);
        if (repository == null) return parts.stream().map(PendingWork::agentValue).toList();
        List<Speaker> idle = new ArrayList<>();
        for (PendingWork part : parts) {
            try {
                if (git.commitsSinceCreated(repository, worktree(part)).isEmpty()) idle.add(part.agentValue());
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

    /** Removes worktrees that never held any work: created for a turn that was stopped or closed before it began. */
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
