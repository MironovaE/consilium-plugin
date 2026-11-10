package dev.katherine.consilium.work;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.katherine.consilium.work.TestRepositories.git;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisabledOnOs(OS.WINDOWS)
class LinkedWorktreeDirectoriesTest {
    @TempDir
    Path dir;

    @Test
    void aLinkedWorktreeNeedsTheCommonGitDirectory() throws IOException {
        Git git = TestRepositories.gitOrSkip();
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Path worktree = dir.resolve("wt");
        git(repo, "worktree", "add", "-q", "-b", "side", worktree.toString());

        // The worktree's own git dir is .git/worktrees/wt, inside the common one, so one root covers both.
        assertEquals(List.of(repo.resolve(".git")), LinkedWorktreeDirectories.outside(worktree.toRealPath(), git));
        assertEquals(List.of(repo.resolve(".git")),
                LinkedWorktreeDirectories.outside(Files.createDirectories(worktree.resolve("sub")).toRealPath(), git));
    }

    @Test
    void aRegularCheckoutOrNoRepositoryNeedsNothing() throws IOException {
        Git git = TestRepositories.gitOrSkip();
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        assertEquals(List.of(), LinkedWorktreeDirectories.outside(repo, git));
        assertEquals(List.of(), LinkedWorktreeDirectories.outside(Files.createDirectories(dir.resolve("plain")), git));
        assertEquals(List.of(), LinkedWorktreeDirectories.outside(dir.resolve("missing"), git));
    }
}
