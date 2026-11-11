package dev.katherine.consilium;

import dev.katherine.consilium.model.PendingWork;
import dev.katherine.consilium.model.Speaker;
import dev.katherine.consilium.work.TestRepositories;
import dev.katherine.consilium.work.Worktree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.katherine.consilium.work.TestRepositories.git;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ParallelWork} against real repositories: what is held back before a merge and what cleanup keeps. */
@DisabledOnOs(OS.WINDOWS)
class ParallelWorkTest {
    @TempDir
    Path dir;
    private Path repo;
    private ParallelWork work;

    @BeforeEach
    void setUp() throws IOException {
        work = new ParallelWork(TestRepositories.worktrees(dir.resolve("system/consilium-worktrees")));
        repo = TestRepositories.init(dir.resolve("repo"), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".husky/pre-commit", "web/.husky/_/husky.sh", ".githooks/pre-push", ".git-hooks/x", ".hooks/commit-msg",
            ".pre-commit-config.yaml", ".pre-commit-config.yml", ".pre-commit-hooks.yaml",
            "lefthook.yml", "lefthook.yaml", "lefthook.json", "lefthook.jsonc", "lefthook.toml", ".lefthook.toml",
            "lefthook-local.yml", ".lefthook-local.json", "lefthook-local.toml", ".config/lefthook.yml",
            ".simple-git-hooks.json", ".simple-git-hooks.cjs", "simple-git-hooks.js", ".overcommit.yml",
            ".huskyrc", ".huskyrc.json", ".huskyrc.js", "packages/app/.huskyrc.yaml", "husky.config.js",
            "package.json", "packages/app/package.json"})
    void hookConfigurationIsHeldAsHooks(String file) {
        assertEquals(ParallelWork.Review.HOOKS, ParallelWork.classify(file, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".claude/settings.json", ".claude/settings.local.json", ".claude/hooks/format.sh", ".claude/commands/x.md",
            ".mcp.json", "tools/.mcp.json", ".codex/config.toml"})
    void agentSettingsAreHeldAsAgentSettings(String file) {
        assertEquals(ParallelWork.Review.AGENT_SETTINGS, ParallelWork.classify(file, null));
    }

    @Test
    void gitmodulesIsHeldAsANestedRepository() {
        assertEquals(ParallelWork.Review.NESTED_REPOSITORY, ParallelWork.classify(".gitmodules", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/main.py", "README.md", "package-lock.json", "docs/husky.md", "my.husky/x", "claude/notes.md"})
    void ordinaryFilesAreNotHeld(String file) {
        assertNull(ParallelWork.classify(file, null));
    }

    @Test
    void namesDifferingOnlyInCaseAreHeldToo() {
        // On macOS and Windows these are the files Claude Code and git open.
        assertEquals(ParallelWork.Review.AGENT_SETTINGS, ParallelWork.classify(".MCP.json", null));
        assertEquals(ParallelWork.Review.AGENT_SETTINGS, ParallelWork.classify(".Claude/settings.json", null));
        assertEquals(ParallelWork.Review.HOOKS, ParallelWork.classify(".Husky/pre-commit", null));
        assertEquals(ParallelWork.Review.HOOKS, ParallelWork.classify("Tools/Hooks/pre-push", "tools/hooks"));
    }

    @Test
    void theConfiguredHooksPathIsHeldWhereverItIs() {
        assertEquals(ParallelWork.Review.HOOKS, ParallelWork.classify("tools/git-hooks/pre-push", "tools/git-hooks"));
        assertNull(ParallelWork.classify("tools/git-hooks-notes.md", "tools/git-hooks"));
        assertEquals(ParallelWork.Review.HOOKS, ParallelWork.classify("anything.txt", ""), "hooks at the top level");
    }

    @Test
    void aPartChangingTheRepositorysHooksPathIsHeld() throws IOException {
        git(repo, "config", "core.hooksPath", "tools/git-hooks");
        PendingWork part = part(Speaker.CODEX, "tools/git-hooks/pre-push", "#!/bin/sh\n");
        PendingWork plain = part(Speaker.CLAUDE, "src/main.py", "print()\n");

        List<ParallelWork.Held> held = work.needingReview(repo, List.of(part, plain));

        assertEquals(1, held.size());
        assertEquals(part.branch, held.getFirst().part().branch);
        assertEquals(ParallelWork.Review.HOOKS, held.getFirst().reason());
        assertEquals("tools/git-hooks/pre-push", held.getFirst().path());
    }

    @Test
    void aCopyWithANestedRepositoryIsNeitherCommittedNorMerged() throws IOException {
        Worktree worktree = TestRepositories.worktrees(dir.resolve("system/consilium-worktrees")).create(repo, "codex");
        PendingWork part = ParallelWork.pending(Speaker.CODEX, worktree);
        Files.writeString(worktree.path().resolve("part.txt"), "work");
        TestRepositories.init(Files.createDirectories(worktree.path().resolve("vendor/lib")), true);

        work.commitParts(List.of(part), Map.of());
        List<ParallelWork.Held> held = work.needingReview(repo, List.of(part));

        assertEquals(List.of(Speaker.CODEX), work.withoutCommits(repo, List.of(part)), "nothing was committed");
        assertEquals(1, held.size());
        assertEquals(ParallelWork.Review.NESTED_REPOSITORY, held.getFirst().reason());
        assertEquals("vendor/lib", held.getFirst().path());
    }

    @Test
    void aBranchThatAddsASubmoduleIsHeld() throws IOException {
        Worktree worktree = TestRepositories.worktrees(dir.resolve("system/consilium-worktrees")).create(repo, "claude");
        git(worktree.path(), "update-index", "--add", "--cacheinfo", "160000," + worktree.baseCommit() + ",vendor/lib");
        git(worktree.path(), "commit", "-q", "-m", "add a submodule");

        List<ParallelWork.Held> held = work.needingReview(repo, List.of(ParallelWork.pending(Speaker.CLAUDE, worktree)));

        assertEquals(ParallelWork.Review.NESTED_REPOSITORY, held.getFirst().reason());
        assertEquals("vendor/lib", held.getFirst().path());
    }

    @Test
    void everyPartIsUncheckedOutsideARepository() throws IOException {
        PendingWork part = part(Speaker.CLAUDE, "src/main.py", "print()\n");

        List<ParallelWork.Held> held = work.needingReview(Files.createDirectories(dir.resolve("plain")), List.of(part));

        assertEquals(ParallelWork.Review.UNCHECKED, held.getFirst().reason());
    }

    @Test
    void cleanUpKeepsTheBranchTheMergingAgentLeftUnmerged() throws IOException {
        PendingWork merged = part(Speaker.CLAUDE, "a.txt", "a");
        PendingWork forgotten = part(Speaker.CODEX, "b.txt", "b");
        // The merging agent reported success but brought in only Claude's part.
        git(repo, "merge", "-q", "--no-edit", merged.branch);

        List<PendingWork> kept = work.cleanUp(repo, List.of(merged, forgotten));

        assertEquals(List.of(forgotten.branch), kept.stream().map(p -> p.branch).toList(), "recorded as still pending");
        assertEquals("b", git(repo, "show", forgotten.branch + ":b.txt"), "its work is still on its branch");
        assertEquals("", git(repo, "branch", "--list", merged.branch), "the merged branch is gone");
        assertFalse(Files.exists(Path.of(merged.path)));
        assertEquals(List.of(forgotten.branch), work.stillThere(repo, List.of(merged, forgotten)).stream().map(p -> p.branch).toList());
        assertTrue(work.needingReview(repo, kept).isEmpty(), "a kept branch can still be checked once its folder is gone");
    }

    /** A part whose agent wrote one file, committed the way Consilium commits it. */
    private PendingWork part(Speaker agent, String file, String content) throws IOException {
        Worktree worktree = TestRepositories.worktrees(dir.resolve("system/consilium-worktrees"))
                .create(repo, agent.name().toLowerCase(Locale.ROOT));
        Path target = worktree.path().resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        PendingWork part = ParallelWork.pending(agent, worktree);
        work.commitParts(List.of(part), Map.of());
        return part;
    }
}
