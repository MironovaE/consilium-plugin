package dev.katherine.consilium.work;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Where git keeps a linked worktree's metadata. A linked worktree's {@code .git} is a file pointing
 * to {@code <main repo>/.git/worktrees/<name>}, and its objects and refs live in the main
 * repository's {@code .git}; a sandbox that only allows writes under the worktree (Codex
 * {@code workspace-write}) therefore can't commit there: {@code git add} fails on {@code index.lock}.
 */
public final class LinkedWorktreeDirectories {
    private static final Logger LOG = Logger.getInstance(LinkedWorktreeDirectories.class);
    // Runs before an agent's turn starts, so a stuck git must not hold the turn for long.
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private LinkedWorktreeDirectories() {
    }

    /**
     * The git directories outside {@code workingDirectory} that a commit from it writes to: the
     * common directory and, if it isn't inside that, the worktree's own git directory. Empty for a
     * regular checkout, outside a repository, or when git is missing or fails: the turn then runs as
     * before, it just can't commit from a linked worktree. Blocks on git.
     */
    public static List<Path> outside(Path workingDirectory) {
        try {
            return outside(workingDirectory, Git.fromLoginShell(TIMEOUT));
        } catch (IOException exception) {
            LOG.info("No git for " + workingDirectory + ": " + exception.getMessage());
            return List.of();
        }
    }

    static List<Path> outside(Path workingDirectory, Git git) {
        try {
            Git.Result result = git.run(workingDirectory, "rev-parse", "--git-dir", "--git-common-dir");
            if (!result.succeeded()) return List.of();
            List<String> lines = result.output().lines().toList();
            if (lines.size() != 2) return List.of();
            // Both may be printed relative to the working directory.
            Path gitDir = workingDirectory.resolve(lines.get(0)).toRealPath();
            Path commonDir = workingDirectory.resolve(lines.get(1)).toRealPath();
            if (gitDir.equals(commonDir)) return List.of();
            Path work = workingDirectory.toRealPath();
            List<Path> directories = new ArrayList<>();
            if (!commonDir.startsWith(work)) directories.add(commonDir);
            if (!gitDir.startsWith(commonDir) && !gitDir.startsWith(work)) directories.add(gitDir);
            return directories;
        } catch (IOException exception) {
            LOG.info("Couldn't read git directories of " + workingDirectory + ": " + exception.getMessage());
            return List.of();
        }
    }
}
