package dev.katherine.consilium.work;

import dev.katherine.consilium.ConsiliumBundle;

import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * {@link GitWorktrees} on the {@code git} CLI. Worktrees live under the IDE's system directory,
 * {@code consilium-worktrees/<repo>-<hash of its path>/<agent>-<timestamp>}: outside the project, so
 * the IDE neither indexes them nor shows them as untracked files, and grouped per repository so two
 * projects with the same folder name never share a directory.
 *
 * <p>Thread rules: every method blocks on git (up to the timeout per call); call from a background
 * thread. {@link #create} is synchronized so that two agents starting at once never pick the same
 * name; git's own locks cover the rest.
 */
public final class GitCliWorktrees implements GitWorktrees {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int MAX_NAME_ATTEMPTS = 100;

    private final Path baseDirectory;
    private final Clock clock;
    private @Nullable Git git;

    /** Production instance: worktrees under the IDE system directory, git from the login-shell PATH. */
    public GitCliWorktrees() {
        this(PathManager.getSystemDir().resolve("consilium-worktrees"));
    }

    /** @param baseDirectory where worktrees are created; must not be inside any repository it serves */
    public GitCliWorktrees(Path baseDirectory) {
        this(baseDirectory, Clock.systemDefaultZone(), null);
    }

    /** Tests pin the clock to force name collisions. */
    GitCliWorktrees(Path baseDirectory, Clock clock, @Nullable Git git) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.clock = clock;
        this.git = git;
    }

    @Override
    public @Nullable Path repositoryRoot(Path projectRoot) throws IOException {
        Git.Result result = git().run(projectRoot, "rev-parse", "--show-toplevel");
        // Outside a work tree (no repository, a bare one, inside .git) git exits non-zero.
        if (!result.succeeded()) return null;
        String top = result.output().stripTrailing();
        return top.isEmpty() ? null : Path.of(top);
    }

    @Override
    public boolean hasUncommittedChanges(Path repositoryRoot) throws IOException {
        // Untracked files count: a worktree from HEAD wouldn't have them either.
        return !git().output(repositoryRoot, "status", "--porcelain=v1", "--untracked-files=normal").isEmpty();
    }

    @Override
    public @Nullable String currentBranch(Path repositoryRoot) throws IOException {
        Git.Result result = git().run(repositoryRoot, "symbolic-ref", "--quiet", "--short", "HEAD");
        if (result.exitCode() == 1 && result.error().isEmpty()) return null;
        if (!result.succeeded()) throw Git.failure(result, "symbolic-ref", "--quiet", "--short", "HEAD");
        return result.output().stripTrailing();
    }

    @Override
    public synchronized Worktree create(Path repositoryRoot, String agentName) throws IOException {
        Path repository = repositoryRoot.toRealPath();
        Git.Result head = git().run(repository, "rev-parse", "--verify", "--quiet", "HEAD^{commit}");
        if (!head.succeeded()) throw new IOException(ConsiliumBundle.message("error.worktree.no.commits", repository));
        String baseCommit = head.output().strip();

        Path parent = baseDirectory.resolve(slug(repository.getFileName() == null ? "repository" : repository.getFileName().toString())
                + "-" + shortHash(repository.toString()));
        Files.createDirectories(parent);
        if (parent.toRealPath().startsWith(repository)) {
            throw new IOException(ConsiliumBundle.message("error.worktree.inside.repository", parent, repository));
        }

        String stem = slug(agentName) + "-" + LocalDateTime.now(clock).format(TIMESTAMP);
        for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
            String name = attempt == 1 ? stem : stem + "-" + attempt;
            String branch = "consilium/" + name;
            Path directory = parent.resolve(name);
            if (Files.exists(directory) || branchExists(repository, branch)) continue;
            git().output(repository, "worktree", "add", "-b", branch, "--", directory.toString(), baseCommit);
            return new Worktree(directory.toRealPath(), branch, baseCommit);
        }
        throw new IOException(ConsiliumBundle.message("error.worktree.no.name", stem));
    }

    @Override
    public List<String> commitsSinceCreated(Worktree worktree) throws IOException {
        String log = git().output(worktree.path(), "log", "--reverse", "--format=%h %s",
                worktree.baseCommit() + "..refs/heads/" + worktree.branch(), "--");
        return log.isEmpty() ? List.of() : log.lines().toList();
    }

    @Override
    public boolean hasUncommittedChanges(Worktree worktree) throws IOException {
        return hasUncommittedChanges(worktree.path());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@code git worktree remove --force}, which discards anything the agent left
     * uncommitted. The caller decides that loss is acceptable: check
     * {@link #hasUncommittedChanges(Worktree)} first and ask the person if it's true.
     */
    @Override
    public boolean remove(Path repositoryRoot, Worktree worktree) throws IOException {
        // A directory the person already deleted by hand is no error: prune drops its metadata.
        if (Files.exists(worktree.path())) {
            git().output(repositoryRoot, "worktree", "remove", "--force", "--", worktree.path().toString());
        }
        git().output(repositoryRoot, "worktree", "prune");
        deleteIfEmpty(worktree.path().getParent());

        String ref = "refs/heads/" + worktree.branch();
        if (!branchExists(repositoryRoot, worktree.branch())) return false;
        Git.Result merged = git().run(repositoryRoot, "merge-base", "--is-ancestor", ref, "HEAD");
        if (merged.exitCode() == 1) return false;
        if (!merged.succeeded()) throw Git.failure(merged, "merge-base", "--is-ancestor", ref, "HEAD");
        git().output(repositoryRoot, "branch", "-d", "--", worktree.branch());
        return true;
    }

    private boolean branchExists(Path repository, String branch) throws IOException {
        Git.Result result = git().run(repository, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        if (result.exitCode() == 1 && result.error().isEmpty()) return false;
        if (!result.succeeded()) throw Git.failure(result, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        return true;
    }

    // Resolved on first use, on the caller's background thread: reading the login-shell
    // environment can block, and constructing the service must stay cheap.
    private synchronized Git git() throws IOException {
        if (git == null) git = Git.fromLoginShell(Git.DEFAULT_TIMEOUT);
        return git;
    }

    private static void deleteIfEmpty(@Nullable Path directory) {
        if (directory == null) return;
        try {
            Files.deleteIfExists(directory);
        } catch (DirectoryNotEmptyException ignored) {
            // Another agent's worktree of the same repository is still there.
        } catch (IOException ignored) {
            // Only tidiness: an empty folder in the IDE system directory harms nothing.
        }
    }

    /** A name that is safe both as a directory and as part of a ref, whatever the agent is called. */
    static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "agent" : slug;
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", exception);
        }
    }
}
