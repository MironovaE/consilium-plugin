package dev.katherine.consilium.cli;

import dev.katherine.consilium.work.TestRepositories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.katherine.consilium.work.TestRepositories.git;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the Codex flags for a write turn in a linked worktree. Checked live on codex-cli 0.167.0,
 * 2026-11-10, in an empty repository outside /tmp (which workspace-write allows anyway): without
 * {@code --add-dir} {@code git add} fails on {@code .git/worktrees/<name>/index.lock}; with it the
 * commit lands on the worktree's branch.
 */
@DisabledOnOs(OS.WINDOWS)
class CodexWorktreeArgumentsTest {
    private static final Path UNUSED_BINARY = Path.of("/nonexistent/codex");

    @TempDir
    Path dir;

    private static List<String> launch(Path root, AgentAccess access) throws IOException {
        return new CodexCliAgent().launchArguments(UNUSED_BINARY, root, null, access, Map.of(), new TurnFiles(), () -> false);
    }

    @Test
    void writeTurnInALinkedWorktreeCanWriteTheCommonGitDirectory() throws IOException {
        TestRepositories.gitOrSkip();
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Path worktree = dir.resolve("wt");
        git(repo, "worktree", "add", "-q", "-b", "side", worktree.toString());
        Path root = worktree.toRealPath();

        assertEquals(List.of("exec", "--json", "--sandbox", "workspace-write", "--add-dir", repo.resolve(".git").toString(),
                "--cd", root.toString(), "--", "-"), launch(root, AgentAccess.WRITE));
    }

    @Test
    void writeTurnInARegularCheckoutOrOutsideGitGetsNoExtraFlags() throws IOException {
        TestRepositories.gitOrSkip();
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Path plain = Files.createDirectories(dir.resolve("plain"));

        assertEquals(List.of("exec", "--json", "--sandbox", "workspace-write", "--cd", repo.toString(), "--", "-"),
                launch(repo, AgentAccess.WRITE));
        assertEquals(List.of("exec", "--json", "--sandbox", "workspace-write", "--cd", plain.toString(), "--", "-"),
                launch(plain, AgentAccess.WRITE));
    }
}
