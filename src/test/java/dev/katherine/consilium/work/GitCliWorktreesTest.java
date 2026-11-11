package dev.katherine.consilium.work;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import dev.katherine.consilium.cli.ExecutableResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static dev.katherine.consilium.work.TestRepositories.git;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(OS.WINDOWS)
class GitCliWorktreesTest {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-10T08:30:15Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;
    private Path base;
    private GitCliWorktrees worktrees;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(dir.resolve("system/consilium-worktrees"));
        worktrees = new GitCliWorktrees(base, FIXED, TestRepositories.gitOrSkip());
    }

    @Test
    void createsAWorktreeOutsideTheRepositoryOnANewBranchFromHead() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("my repo"), true);

        Worktree worktree = worktrees.create(repo, "Claude");

        assertEquals("consilium/claude-20261110-083015", worktree.branch());
        assertEquals(git(repo, "rev-parse", "HEAD"), worktree.baseCommit());
        assertTrue(Files.isDirectory(worktree.path()));
        assertFalse(worktree.path().startsWith(repo));
        assertTrue(worktree.path().startsWith(base.toRealPath()));
        assertTrue(worktree.path().getParent().getFileName().toString().matches("my-repo-[0-9a-f]{8}"));
        assertEquals(worktree.branch(), git(worktree.path(), "symbolic-ref", "--short", "HEAD"));
        // The main checkout doesn't see the worktree as untracked files.
        assertFalse(worktrees.hasUncommittedChanges(repo));
        assertEquals(List.of(), worktrees.commitsSinceCreated(repo, worktree));
    }

    @Test
    void reportsUncommittedChangesAndCommitsSinceCreation() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "codex");

        Files.writeString(worktree.path().resolve("a.txt"), "a");
        assertTrue(worktrees.hasUncommittedChanges(worktree), "an untracked file is an uncommitted change");
        git(worktree.path(), "add", "a.txt");
        git(worktree.path(), "commit", "-q", "-m", "first change");
        Files.writeString(worktree.path().resolve("b.txt"), "b");
        git(worktree.path(), "add", "b.txt");
        git(worktree.path(), "commit", "-q", "-m", "second change");
        // A commit on main after the worktree was made isn't the agent's.
        git(repo, "commit", "-q", "--allow-empty", "-m", "meanwhile on main");

        List<String> commits = worktrees.commitsSinceCreated(repo, worktree);
        assertEquals(2, commits.size());
        assertTrue(commits.get(0).matches("[0-9a-f]{7,} first change"), commits.get(0));
        assertTrue(commits.get(1).endsWith(" second change"), commits.get(1));
        assertFalse(worktrees.hasUncommittedChanges(worktree));
        assertFalse(worktrees.hasUncommittedChanges(repo));
    }

    @Test
    void removeDeletesAMergedBranch() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        Files.writeString(worktree.path().resolve("a.txt"), "a");
        git(worktree.path(), "add", "a.txt");
        git(worktree.path(), "commit", "-q", "-m", "change");
        git(repo, "merge", "-q", "--ff-only", worktree.branch());

        assertTrue(worktrees.remove(repo, worktree));

        assertFalse(Files.exists(worktree.path()));
        assertEquals("", git(repo, "branch", "--list", worktree.branch()));
        assertEquals(1, git(repo, "worktree", "list", "--porcelain").lines().filter(line -> line.startsWith("worktree ")).count());
    }

    @Test
    void commitAllNeverRunsAHookTheAgentWroteInItsCopy() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        // husky-style: hooks come from tracked files, so an agent's copy carries its own.
        git(repo, "config", "core.hooksPath", ".husky");
        Worktree worktree = worktrees.create(repo, "codex");
        Path pwned = dir.resolve("pwned");
        for (String hook : new String[] {"pre-commit", "commit-msg", "post-commit"}) {
            Path script = worktree.path().resolve(".husky").resolve(hook);
            Files.createDirectories(script.getParent());
            Files.writeString(script, "#!/bin/sh\necho " + hook + " >> '" + pwned + "'\n");
            assertTrue(script.toFile().setExecutable(true));
        }
        Files.writeString(worktree.path().resolve("part.txt"), "the agent's work");

        assertTrue(worktrees.commitAll(worktree, "chore(consilium): codex part of a parallel task"));

        assertFalse(Files.exists(pwned), "no hook from the agent's copy ran");
        assertEquals(List.of(), worktrees.commitsSinceCreated(repo, worktree).stream().filter(c -> !c.contains("codex part")).toList());
        assertEquals(1, worktrees.commitsSinceCreated(repo, worktree).size());
        assertFalse(worktrees.hasUncommittedChanges(worktree));
    }

    @Test
    void aKeptBranchStillReportsItsCommitsAndFilesAfterItsFolderIsGone() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "codex");
        Files.writeString(worktree.path().resolve("part.txt"), "work");
        assertTrue(worktrees.commitAll(worktree, "chore(consilium): codex part of a parallel task"));
        assertFalse(worktrees.remove(repo, worktree), "unmerged, so the branch is kept");

        assertEquals(1, worktrees.commitsSinceCreated(repo, worktree).size());
        assertEquals(List.of("part.txt"), worktrees.changedFiles(repo, worktree));
        assertFalse(worktrees.hasUncommittedChanges(worktree));
        assertFalse(worktrees.commitAll(worktree, "nothing to commit in a folder that's gone"));
    }

    @Test
    void ownsOnlyPathsInsideItsBaseDirectory() {
        assertTrue(worktrees.owns(base.resolve("repo-1234/claude-1")));
        assertFalse(worktrees.owns(base));
        assertFalse(worktrees.owns(dir.resolve("elsewhere")));
        assertFalse(worktrees.owns(base.resolve("../escape")));
    }

    @Test
    void removeDeletesABranchWhoseWorkWasRewrittenIntoHead() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        Files.writeString(worktree.path().resolve("a.txt"), "a");
        git(worktree.path(), "add", "a.txt");
        git(worktree.path(), "commit", "-q", "-m", "chore(consilium): claude part of a parallel task");
        // The merging agent brings the same content in as its own well-described commit.
        Files.writeString(repo.resolve("a.txt"), "a");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "feat: add a");

        assertTrue(worktrees.remove(repo, worktree));

        assertEquals("", git(repo, "branch", "--list", worktree.branch()));
    }

    @Test
    void removeKeepsABranchWithWorkThatIsOnlyPartlyInHead() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "codex");
        Files.writeString(worktree.path().resolve("a.txt"), "a");
        Files.writeString(worktree.path().resolve("b.txt"), "b");
        git(worktree.path(), "add", "-A");
        git(worktree.path(), "commit", "-q", "-m", "two files");
        Files.writeString(repo.resolve("a.txt"), "a");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "only one of them");

        assertFalse(worktrees.remove(repo, worktree));

        assertEquals("two files", git(repo, "log", "-1", "--format=%s", worktree.branch()));
    }

    @Test
    void removeKeepsAnUnmergedBranchAndDiscardsUncommittedFiles() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "codex");
        Files.writeString(worktree.path().resolve("a.txt"), "a");
        git(worktree.path(), "add", "a.txt");
        git(worktree.path(), "commit", "-q", "-m", "unmerged work");
        Files.writeString(worktree.path().resolve("left-over.txt"), "x");

        assertFalse(worktrees.remove(repo, worktree));

        assertFalse(Files.exists(worktree.path()));
        assertEquals(git(repo, "rev-parse", worktree.branch() + "^{commit}"),
                git(repo, "rev-parse", "refs/heads/" + worktree.branch()), "the branch and its commit are kept");
        assertEquals("unmerged work", git(repo, "log", "-1", "--format=%s", worktree.branch()));
    }

    @Test
    void removeToleratesADirectoryDeletedByHand() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        git(repo, "worktree", "remove", "--force", worktree.path().toString());

        // Nothing committed: the branch points at HEAD, so it counts as merged.
        assertTrue(worktrees.remove(repo, worktree));
        assertEquals("", git(repo, "branch", "--list", worktree.branch()));
    }

    @Test
    void twoWorktreesForTheSameAgentDontCollide() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);

        Worktree first = worktrees.create(repo, "claude");
        Worktree second = worktrees.create(repo, "claude");

        assertNotEquals(first.branch(), second.branch());
        assertNotEquals(first.path(), second.path());
        assertEquals("consilium/claude-20261110-083015-2", second.branch());
        assertTrue(Files.isDirectory(second.path()));
    }

    @Test
    void createFailsClearlyInAnEmptyRepository() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("empty"), false);

        IOException error = assertThrows(IOException.class, () -> worktrees.create(repo, "claude"));
        assertTrue(error.getMessage().contains("no commits"), error.getMessage());
        assertEquals("", git(repo, "branch", "--list"));
    }

    @Test
    void refusesABaseDirectoryInsideTheRepository() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        GitCliWorktrees inside = new GitCliWorktrees(repo.resolve(".worktrees"), FIXED, TestRepositories.gitOrSkip());

        assertThrows(IOException.class, () -> inside.create(repo, "claude"));
    }

    @Test
    void repositoryRootIsNullOutsideARepository() throws IOException {
        Path plain = Files.createDirectories(dir.resolve("plain"));
        assertNull(worktrees.repositoryRoot(plain));
    }

    @Test
    void repositoryRootIsTheTopLevelFromASubdirectory() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Path sub = Files.createDirectories(repo.resolve("src/main"));
        assertEquals(repo, worktrees.repositoryRoot(sub).toRealPath());
    }

    @Test
    void uncommittedChangesInTheMainCheckoutAreReported() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        assertFalse(worktrees.hasUncommittedChanges(repo));
        Files.writeString(repo.resolve("draft.txt"), "x");
        assertTrue(worktrees.hasUncommittedChanges(repo));
    }

    @Test
    void ownsTheCopiesItRecordsByTheirRealPath() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");

        // On macOS the temp directory is behind /var -> /private/var; the record holds the real path.
        assertTrue(worktrees.owns(worktree.path()));
    }

    @Test
    void removingTheLastCopyRemovesTheRepositoryFolder() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree first = worktrees.create(repo, "claude");
        Worktree second = worktrees.create(repo, "codex");
        Path parent = first.path().getParent();

        worktrees.remove(repo, first);
        assertTrue(Files.isDirectory(parent), "the other copy still needs the folder and its record");
        assertFalse(worktrees.hasUncommittedChanges(second));

        worktrees.remove(repo, second);
        assertFalse(Files.exists(parent));
    }

    @Test
    void commitAllUsesTheRepositorysRecordOfTheCopyNotTheCopysGitFile() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "codex");
        // The agent points its copy at a repository it made, whose config runs a filter on every file.
        Path evil = TestRepositories.init(dir.resolve("evil"), true);
        Path pwned = dir.resolve("pwned");
        git(evil, "config", "filter.pwn.clean", "sh -c 'echo clean >> \"" + pwned + "\"; cat'");
        Files.writeString(worktree.path().resolve(".git"), "gitdir: " + evil.resolve(".git") + "\n");
        Files.writeString(worktree.path().resolve(".gitattributes"), "* filter=pwn\n");
        Files.writeString(worktree.path().resolve("part.txt"), "work");

        assertTrue(worktrees.commitAll(worktree, "chore(consilium): codex part of a parallel task"));

        assertFalse(Files.exists(pwned), "no filter from the agent's repository ran");
        assertEquals(1, worktrees.commitsSinceCreated(repo, worktree).size(), "the commit is on the part's own branch");
        assertEquals(List.of(".gitattributes", "part.txt"), worktrees.changedFiles(repo, worktree));
        assertEquals("init", git(evil, "log", "-1", "--format=%s"), "the agent's repository got nothing");
    }

    @Test
    void commitAllRefusesACopyWithANestedRepository() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "codex");
        Files.writeString(worktree.path().resolve("part.txt"), "work");
        TestRepositories.init(Files.createDirectories(worktree.path().resolve("vendor/lib")), true);

        assertEquals("vendor/lib", worktrees.nestedRepository(worktree));
        assertThrows(NestedRepositoryException.class, () -> worktrees.commitAll(worktree, "part"));

        assertEquals(List.of(), worktrees.commitsSinceCreated(repo, worktree));
        assertEquals("", git(worktree.path(), "diff", "--cached", "--name-only"), "nothing was staged");
    }

    @Test
    void aCopyFromBeforeTheRepositoryRecordIsAdoptedAndCommitsAgain() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        Files.writeString(worktree.path().resolve("part.txt"), "work");
        // 0.4.0 kept no record next to its copies.
        Files.delete(worktree.path().getParent().resolve(".repository"));
        assertThrows(IOException.class, () -> worktrees.commitAll(worktree, "part"));

        worktrees.adopt(repo, worktree);

        assertTrue(worktrees.commitAll(worktree, "part"));
        assertEquals(1, worktrees.commitsSinceCreated(repo, worktree).size());
    }

    @Test
    void adoptNeverClaimsAnotherRepositorysCopies() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Path other = TestRepositories.init(dir.resolve("other"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        Path record = worktree.path().getParent().resolve(".repository");
        Files.delete(record);

        worktrees.adopt(other, worktree);

        assertFalse(Files.exists(record), "the folder's name says it holds copies of another repository");
    }

    @Test
    void changedFilesComeUnquoted() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        Path commands = Files.createDirectories(worktree.path().resolve(".claude/commands"));
        Files.writeString(commands.resolve("café.md"), "run me");
        worktrees.commitAll(worktree, "part");

        assertEquals(List.of(".claude/commands/café.md"), worktrees.changedFiles(repo, worktree));
    }

    @Test
    void aGitFileCountsAsANestedRepositoryToo() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        assertNull(worktrees.nestedRepository(worktree), "the copy's own .git file is not nested");

        Path module = Files.createDirectories(worktree.path().resolve("modules/a"));
        Files.writeString(module.resolve(".git"), "gitdir: ../../elsewhere\n");

        assertEquals("modules/a", worktrees.nestedRepository(worktree));
    }

    @Test
    void changedGitlinksListsASubmoduleTheBranchAdds() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        Files.writeString(worktree.path().resolve("plain.txt"), "x");
        git(worktree.path(), "add", "plain.txt");
        git(worktree.path(), "update-index", "--add", "--cacheinfo", "160000," + worktree.baseCommit() + ",vendor/lib");
        git(worktree.path(), "commit", "-q", "-m", "add a submodule");

        assertEquals(List.of("vendor/lib"), worktrees.changedGitlinks(repo, worktree));
        // The folder isn't there, so the copy holds no repository; the branch still records one.
        assertNull(worktrees.nestedRepository(worktree));
    }

    @Test
    void uncommittedChangesNeverLookInsideASubmodule() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        Worktree worktree = worktrees.create(repo, "claude");
        // A checked-out submodule whose own config runs a clean filter, and a file whose timestamp
        // changed, which a status inside the submodule would rehash through that filter. -c settings
        // reach the submodule's git too, but a filter driver doesn't need any.
        Path module = TestRepositories.init(Files.createDirectories(worktree.path().resolve("vendor/lib")), false);
        Files.writeString(module.resolve(".gitattributes"), "* filter=pwn\n");
        Files.writeString(module.resolve("a.txt"), "a");
        git(module, "add", "-A");
        git(module, "commit", "-q", "-m", "module");
        git(worktree.path(), "update-index", "--add", "--cacheinfo", "160000," + git(module, "rev-parse", "HEAD") + ",vendor/lib");
        git(worktree.path(), "commit", "-q", "-m", "add a submodule");
        Path pwned = dir.resolve("pwned");
        git(module, "config", "filter.pwn.clean", "sh -c 'echo clean >> \"" + pwned + "\"; cat'");
        Files.setLastModifiedTime(module.resolve("a.txt"), FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));

        worktrees.hasUncommittedChanges(worktree);

        assertFalse(Files.exists(pwned), "git didn't look inside the submodule");
    }

    @Test
    void hooksPathInRepositoryIsRelativeToTheRootOrNull() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        assertNull(worktrees.hooksPathInRepository(repo), "outside the repository (TestRepositories sets /dev/null)");

        git(repo, "config", "--unset", "core.hooksPath");
        assertNull(worktrees.hooksPathInRepository(repo), "unset");

        git(repo, "config", "core.hooksPath", ".husky");
        assertEquals(".husky", worktrees.hooksPathInRepository(repo));

        git(repo, "config", "core.hooksPath", repo.resolve("tools/git-hooks").toString());
        assertEquals("tools/git-hooks", worktrees.hooksPathInRepository(repo));

        git(repo, "config", "core.hooksPath", ".");
        assertEquals("", worktrees.hooksPathInRepository(repo), "the root itself: any file may be a hook");
    }

    @Test
    void aFailedWorktreeAddLeavesNothingBehind() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        // Fails the way a killed add does: its registration still locked as "initializing".
        GitCliWorktrees failing = new GitCliWorktrees(base, FIXED, gitThat(
                "for r in '" + repo + "'/.git/worktrees/*; do echo initializing > \"$r/locked\"; done; exit 1"),
                Duration.ofSeconds(30));

        assertThrows(IOException.class, () -> failing.create(repo, "claude"));

        assertNothingLeft(repo);
        // A retry gets the first name again: nothing of the failed attempt is in its way.
        assertEquals("consilium/claude-20261110-083015", worktrees.create(repo, "claude").branch());
    }

    @Test
    void aTimedOutWorktreeAddLeavesNothingBehind() throws IOException {
        Path repo = TestRepositories.init(dir.resolve("repo"), true);
        GitCliWorktrees slow = new GitCliWorktrees(base, FIXED, gitThat("sleep 30"), Duration.ofSeconds(1));

        IOException error = assertThrows(IOException.class, () -> slow.create(repo, "claude"));

        assertTrue(error.getMessage().contains("1 s"), error.getMessage());
        assertNothingLeft(repo);
    }

    /** A git that does a real {@code worktree add} and then runs {@code after}, standing in for a failure. */
    private Git gitThat(String after) throws IOException {
        Path real = ExecutableResolver.resolveOnPath("git", System.getenv());
        Path script = dir.resolve("fake-git");
        Files.writeString(script, "#!/bin/sh\n"
                + "case \" $* \" in\n"
                + "  *\" worktree add \"*) \"" + real + "\" \"$@\"; " + after + ";;\n"
                + "  *) exec \"" + real + "\" \"$@\";;\n"
                + "esac\n");
        assertTrue(script.toFile().setExecutable(true));
        return new Git(script, Duration.ofSeconds(60));
    }

    private void assertNothingLeft(Path repo) throws IOException {
        assertEquals("", git(repo, "branch", "--list", "consilium/*"), "no branch");
        assertEquals(1, git(repo, "worktree", "list", "--porcelain").lines().filter(line -> line.startsWith("worktree ")).count(),
                "no registration");
        Path registrations = repo.resolve(".git/worktrees");
        if (Files.exists(registrations)) {
            try (var entries = Files.list(registrations)) {
                assertEquals(List.of(), entries.toList(), "no metadata under .git/worktrees");
            }
        }
        try (var left = Files.list(base)) {
            assertEquals(List.of(), left.filter(path -> !path.getFileName().toString().startsWith(".")).toList(), "no folder");
        }
    }

    @Test
    void slugIsSafeForRefsAndFolders() {
        assertEquals("claude-code", GitCliWorktrees.slug("Claude Code"));
        assertEquals("a-b", GitCliWorktrees.slug("../a..b/"));
        assertEquals("agent", GitCliWorktrees.slug("…"));
    }
}
