package dev.katherine.consilium.work;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Git operations behind parallel work: each agent gets its own worktree and branch from the
 * current commit, so two agents never edit, build or test in the same files at once.
 * Every method blocks on a git process; never call it on the UI thread.
 */
public interface GitWorktrees {
    /** The repository's top-level directory, or null if {@code projectRoot} isn't inside a git work tree. */
    @Nullable Path repositoryRoot(Path projectRoot) throws IOException;

    /** True when the work tree has uncommitted changes, which a new worktree would not contain. */
    boolean hasUncommittedChanges(Path repositoryRoot) throws IOException;

    /** The checked-out branch name, or null on a detached HEAD. */
    @Nullable String currentBranch(Path repositoryRoot) throws IOException;

    /**
     * Creates a worktree on a new branch {@code consilium/<agent>-<timestamp>} from HEAD, in a
     * directory outside the project (so the IDE doesn't index it and it never shows as untracked).
     */
    Worktree create(Path repositoryRoot, String agentName) throws IOException;

    /** Commits on the worktree's branch since it was created, oldest first ("hash subject"); empty if none. */
    List<String> commitsSinceCreated(Worktree worktree) throws IOException;

    /** True when the worktree has changes its agent did not commit. */
    boolean hasUncommittedChanges(Worktree worktree) throws IOException;

    /**
     * Stages and commits everything in the worktree on its branch. Consilium commits an agent's part
     * itself, so the agent never needs write access to the repository's {@code .git}.
     *
     * @return false if there was nothing to commit
     */
    boolean commitAll(Worktree worktree, String message) throws IOException;

    /**
     * True if {@code path} lies inside the directory where this service creates worktrees. Stored
     * records come back from workspace.xml, and git must never be run in a folder they merely name.
     */
    boolean owns(Path path);

    /** True if the branch still exists; the person may have merged or deleted it by hand. */
    boolean branchExists(Path repositoryRoot, String branch) throws IOException;

    /**
     * Removes the worktree directory and its git metadata. Deletes the branch only if its work is in
     * the repository's current HEAD, as an ancestor or as rewritten commits with the same content; an
     * unmerged branch is kept so no work is lost.
     *
     * @return true if the branch was deleted
     */
    boolean remove(Path repositoryRoot, Worktree worktree) throws IOException;
}
