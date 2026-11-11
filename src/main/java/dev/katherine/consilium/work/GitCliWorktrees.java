package dev.katherine.consilium.work;

import dev.katherine.consilium.ConsiliumBundle;

import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@link GitWorktrees} on the {@code git} CLI. Worktrees live under the IDE's system directory,
 * {@code consilium-worktrees/<repo>-<hash of its path>/<agent>-<timestamp>}: outside the project, so
 * the IDE neither indexes them nor shows them as untracked files, and grouped per repository so two
 * projects with the same folder name never share a directory.
 *
 * <p>Git in an agent's copy runs on the repository's own record of that copy (its directory under
 * {@code .git/worktrees}), never on the copy's {@code .git} file, which the agent could have rewritten.
 * The checks that need the copy's files (uncommitted changes, nested repositories) read them; the
 * checks that judge a finished part read the branch from the repository.
 *
 * <p>Thread rules: every method blocks on git (up to the timeout per call); call from a background
 * thread. {@link #create} is synchronized so that two agents starting at once never pick the same
 * name; git's own locks cover the rest.
 */
public final class GitCliWorktrees implements GitWorktrees {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int MAX_NAME_ATTEMPTS = 100;
    /**
     * {@code worktree add} checks out the whole tree and runs the project's smudge filters (Git LFS
     * downloads its objects there), so a large repository legitimately takes minutes.
     */
    static final Duration WORKTREE_ADD_TIMEOUT = Duration.ofMinutes(10);
    private static final String GITLINK_MODE = "160000";
    /** File in each repository's folder of copies naming the repository; see {@link #recordRepository}. */
    private static final String REPOSITORY_RECORD = ".repository";

    /**
     * Every window has its own instance, and two windows on one repository may start parallel work
     * in the same second: one lock for the whole IDE keeps their names, and the cleanup of a failed
     * add, from touching each other's copies.
     */
    private static final Object CREATE_LOCK = new Object();

    private final Path baseDirectory;
    private final Clock clock;
    private final Duration addTimeout;
    private @Nullable Git git;

    /** Production instance: worktrees under the IDE system directory, git from the login-shell PATH. */
    public GitCliWorktrees() {
        this(PathManager.getSystemDir().resolve("consilium-worktrees"));
    }

    /** @param baseDirectory where worktrees are created; must not be inside any repository it serves */
    public GitCliWorktrees(Path baseDirectory) {
        this(baseDirectory, Clock.systemDefaultZone(), null, WORKTREE_ADD_TIMEOUT);
    }

    /** Tests pin the clock to force name collisions. */
    GitCliWorktrees(Path baseDirectory, Clock clock, @Nullable Git git) {
        this(baseDirectory, clock, git, WORKTREE_ADD_TIMEOUT);
    }

    /** Tests shorten {@code worktree add}'s deadline to see what a timed-out one leaves behind. */
    GitCliWorktrees(Path baseDirectory, Clock clock, @Nullable Git git, Duration addTimeout) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.clock = clock;
        this.git = git;
        this.addTimeout = addTimeout;
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
    public Worktree create(Path repositoryRoot, String agentName) throws IOException {
        synchronized (CREATE_LOCK) {
            return createLocked(repositoryRoot, agentName);
        }
    }

    private Worktree createLocked(Path repositoryRoot, String agentName) throws IOException {
        Path repository = repositoryRoot.toRealPath();
        Git.Result head = git().run(repository, "rev-parse", "--verify", "--quiet", "HEAD^{commit}");
        if (!head.succeeded()) throw new IOException(ConsiliumBundle.message("error.worktree.no.commits", repository));
        String baseCommit = head.output().strip();

        // The real path: git records it in the worktree's metadata, and cleanup matches on it.
        Path parent = Files.createDirectories(baseDirectory.resolve(copiesFolderName(repository))).toRealPath();
        if (parent.startsWith(repository)) {
            throw new IOException(ConsiliumBundle.message("error.worktree.inside.repository", parent, repository));
        }
        recordRepository(parent, repository);

        String stem = slug(agentName) + "-" + LocalDateTime.now(clock).format(TIMESTAMP);
        for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
            String name = attempt == 1 ? stem : stem + "-" + attempt;
            String branch = "consilium/" + name;
            Path directory = parent.resolve(name);
            if (Files.exists(directory) || branchExists(repository, branch)) continue;
            addWorktree(repository, directory, branch, baseCommit);
            return new Worktree(directory, branch, baseCommit);
        }
        throw new IOException(ConsiliumBundle.message("error.worktree.no.name", stem));
    }

    /**
     * Runs {@code worktree add} and, if it fails or times out, removes what it got as far as making:
     * the folder, the registration under {@code .git/worktrees} and the branch. Left behind, each retry
     * would add another orphan the person never asked for. All three are known to be new: the caller
     * checked that neither the folder nor the branch existed, under {@link #CREATE_LOCK}.
     */
    private void addWorktree(Path repository, Path directory, String branch, String baseCommit) throws IOException {
        String[] arguments = {"worktree", "add", "-b", branch, "--", directory.toString(), baseCommit};
        IOException failure;
        try {
            Git.Result added = git().withTimeout(addTimeout).run(repository, arguments);
            if (added.succeeded()) return;
            failure = Git.failure(added, arguments);
        } catch (IOException exception) {
            failure = exception;
        }
        // Each step runs even if an earlier one failed: whatever can be removed is.
        attempt(failure, () -> deleteTree(directory));
        // A killed add leaves its registration locked ("initializing"), and prune skips locked
        // entries, so the registration is deleted directly; prune then tidies anything else.
        attempt(failure, () -> {
            for (Path registration : registrationsOf(repository, directory)) deleteTree(registration);
        });
        attempt(failure, () -> git().output(repository, "worktree", "prune"));
        attempt(failure, () -> {
            if (branchExists(repository, branch)) git().output(repository, "branch", "-D", "--", branch);
        });
        deleteParentIfUnused(directory.getParent());
        throw failure;
    }

    private interface CleanupStep {
        void run() throws IOException;
    }

    private static void attempt(IOException failure, CleanupStep step) {
        try {
            step.run();
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    @Override
    public List<String> commitsSinceCreated(Path repositoryRoot, Worktree worktree) throws IOException {
        // From the repository, not the copy: a kept branch outlives its worktree folder.
        String log = git().output(repositoryRoot, "log", "--reverse", "--format=%h %s",
                worktree.baseCommit() + "..refs/heads/" + worktree.branch(), "--");
        return log.isEmpty() ? List.of() : log.lines().toList();
    }

    @Override
    public List<String> changedFiles(Path repositoryRoot, Worktree worktree) throws IOException {
        // -z: names come raw, not C-quoted ("\"caf\\303\\251\"" would hide a .claude/ segment), and a
        // newline inside a name can't split it in two.
        String names = git().output(repositoryRoot, "diff", "--name-only", "-z",
                worktree.baseCommit() + "..refs/heads/" + worktree.branch(), "--");
        return java.util.Arrays.stream(names.split("\0")).filter(name -> !name.isEmpty()).toList();
    }

    @Override
    public boolean hasUncommittedChanges(Worktree worktree) throws IOException {
        if (!Files.isDirectory(worktree.path())) return false;
        // Submodules are not looked into: status would run in each under that repository's own config.
        return !git().output(worktree.path(), inAgentCopy(worktree,
                "status", "--porcelain=v1", "--untracked-files=normal", "--ignore-submodules=all")).isEmpty();
    }

    @Override
    public @Nullable String nestedRepository(Worktree worktree) throws IOException {
        Path root = worktree.path();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null;
        String[] found = {null};
        // Symbolic links aren't followed, as git doesn't follow them; an unreadable folder throws,
        // so a part that can't be checked is never taken for a clean one.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (directory.equals(root) || !Files.exists(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                    return FileVisitResult.CONTINUE;
                }
                found[0] = root.relativize(directory).toString().replace(File.separatorChar, '/');
                return FileVisitResult.TERMINATE;
            }
        });
        return found[0];
    }

    @Override
    public List<String> changedGitlinks(Path repositoryRoot, Worktree worktree) throws IOException {
        // Raw lines read ":<old mode> <new mode> <old id> <new id> <status>\t<path>".
        String raw = git().output(repositoryRoot, "-c", "core.quotePath=false", "diff", "--raw", "--no-renames", "--no-ext-diff",
                worktree.baseCommit() + "..refs/heads/" + worktree.branch(), "--");
        List<String> gitlinks = new ArrayList<>();
        for (String line : raw.lines().toList()) {
            int tab = line.indexOf('\t');
            String[] fields = (tab < 0 ? line : line.substring(0, tab)).split(" ");
            if (tab < 0 || fields.length < 2) throw new IOException("Unexpected git diff --raw line: " + line);
            if (fields[1].equals(GITLINK_MODE)) gitlinks.add(line.substring(tab + 1));
        }
        return gitlinks;
    }

    @Override
    public @Nullable String hooksPathInRepository(Path repositoryRoot) throws IOException {
        String[] arguments = {"config", "--type=path", "--get", "core.hooksPath"};
        Git.Result result = git().run(repositoryRoot, arguments);
        if (result.exitCode() == 1 && result.error().isEmpty()) return null;
        if (!result.succeeded()) throw Git.failure(result, arguments);
        String value = result.output().strip();
        if (value.isEmpty()) return null;
        // Git resolves a relative hooks path from the top of the work tree.
        Path repository = repositoryRoot.toRealPath();
        Path hooks = repository.resolve(value).normalize();
        // "" when the hooks are the repository's top level itself: then any file may be a hook.
        return hooks.startsWith(repository) ? repository.relativize(hooks).toString().replace(File.separatorChar, '/') : null;
    }

    @Override
    public boolean owns(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.equals(baseDirectory) && normalized.startsWith(baseDirectory)) return true;
        // Copies are recorded by their real path, and the base may sit behind a symbolic link
        // (macOS's /var is /private/var).
        try {
            Path real = baseDirectory.toRealPath();
            return !real.equals(baseDirectory) && !normalized.equals(real) && normalized.startsWith(real);
        } catch (IOException missing) {
            return false;
        }
    }

    /**
     * Git arguments for a command run inside an agent's copy, which holds files the agent wrote
     * outside any sandbox Consilium controls:
     * <ul>
     *   <li>{@code --git-dir}/{@code --work-tree} come from the repository's registration of the copy,
     *       not from the copy's {@code .git} file, which could point git at a repository the agent made;</li>
     *   <li>hooks point at an empty directory: a repository with a relative {@code core.hooksPath}
     *       (husky's {@code .husky}) takes its hooks from the copy, and {@code --no-verify} alone still
     *       runs post-commit;</li>
     *   <li>fsmonitor is off, commits aren't signed (a signing program is one more command to run, and
     *       the merging agent rewrites these commits anyway), and git doesn't recurse into submodules.</li>
     * </ul>
     */
    private String[] inAgentCopy(Worktree worktree, String... arguments) throws IOException {
        Path noHooks = Files.createDirectories(baseDirectory.resolve(".no-hooks"));
        List<String> full = new ArrayList<>(List.of(
                "--git-dir=" + registration(worktree),
                "--work-tree=" + worktree.path(),
                "-c", "core.hooksPath=" + noHooks,
                "-c", "core.fsmonitor=false",
                "-c", "commit.gpgSign=false",
                "-c", "submodule.recurse=false"));
        full.addAll(List.of(arguments));
        return full.toArray(String[]::new);
    }

    /**
     * The copy's directory under {@code .git/worktrees}, found from the repository's side: the
     * repository comes from the record {@link #create} keeps next to the copies, where no agent writes.
     */
    private Path registration(Worktree worktree) throws IOException {
        Path parent = worktree.path().toAbsolutePath().normalize().getParent();
        Path record = parent == null ? null : parent.resolve(REPOSITORY_RECORD);
        if (record == null || !Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("No repository is recorded for the worktree " + worktree.path());
        }
        Path repository = Path.of(Files.readString(record, StandardCharsets.UTF_8).strip());
        List<Path> found = registrationsOf(repository, worktree.path());
        if (found.size() != 1) {
            throw new IOException("The repository " + repository + " has " + found.size()
                    + " registrations of the worktree " + worktree.path());
        }
        return found.getFirst();
    }

    @Override
    public void adopt(Path repositoryRoot, Worktree worktree) throws IOException {
        Path parent = worktree.path().toAbsolutePath().normalize().getParent();
        if (parent == null || !owns(worktree.path())) return;
        if (Files.isRegularFile(parent.resolve(REPOSITORY_RECORD), LinkOption.NOFOLLOW_LINKS)) return;
        Path repository = repositoryRoot.toRealPath();
        // Only the folder create() would have made for this repository: its name carries the
        // repository's path hash, so a copy of another repository is never claimed by this one.
        if (!parent.getFileName().toString().equals(copiesFolderName(repository))) return;
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return;
        recordRepository(parent, repository);
    }

    private static String copiesFolderName(Path repository) {
        return slug(repository.getFileName() == null ? "repository" : repository.getFileName().toString())
                + "-" + shortHash(repository.toString());
    }

    /**
     * Records which repository the copies in {@code parent} belong to. The folder name carries only a
     * short hash of the repository's path, and the stored parts carry no repository at all, so this
     * record is how a later command finds the copy's registration without asking the copy.
     */
    private static void recordRepository(Path parent, Path repository) throws IOException {
        Path record = parent.resolve(REPOSITORY_RECORD);
        if (Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
            String recorded = Files.readString(record, StandardCharsets.UTF_8).strip();
            if (recorded.equals(repository.toString())) return;
            // Two repositories whose paths share the short hash: refuse rather than mix their copies.
            throw new IOException("The folder " + parent + " already holds copies of " + recorded);
        }
        Files.writeString(record, repository + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Registrations under {@code <common dir>/worktrees} whose {@code gitdir} file names
     * {@code directory/.git}. The registration's own name usually equals the folder's, but git adds a
     * number on a clash, so every entry is checked rather than guessed.
     */
    private List<Path> registrationsOf(Path repositoryRoot, Path directory) throws IOException {
        // Printed relative to the directory git ran in unless it lies elsewhere.
        Path common = repositoryRoot.resolve(git().output(repositoryRoot, "rev-parse", "--git-common-dir")).toRealPath();
        Path registrations = common.resolve("worktrees");
        if (!Files.isDirectory(registrations, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path expected = directory.toAbsolutePath().normalize().resolve(".git");
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(registrations)) {
            for (Path entry : entries) {
                Path gitdir = entry.resolve("gitdir");
                if (!Files.isRegularFile(gitdir, LinkOption.NOFOLLOW_LINKS)) continue;
                String recorded = Files.readString(gitdir, StandardCharsets.UTF_8).strip();
                // worktree.useRelativePaths (git 2.48+) records the path relative to the registration.
                if (!recorded.isEmpty() && entry.resolve(recorded).normalize().equals(expected)) found.add(entry);
            }
        }
        return found;
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
        deleteParentIfUnused(worktree.path().getParent());

        String ref = "refs/heads/" + worktree.branch();
        if (!branchExists(repositoryRoot, worktree.branch())) return false;
        Git.Result merged = git().run(repositoryRoot, "merge-base", "--is-ancestor", ref, "HEAD");
        if (merged.succeeded()) {
            git().output(repositoryRoot, "branch", "-d", "--", worktree.branch());
            return true;
        }
        if (merged.exitCode() != 1) throw Git.failure(merged, "merge-base", "--is-ancestor", ref, "HEAD");
        // The merging agent usually rewrites the part into well-described commits, so the branch is
        // no ancestor of HEAD although its content is all there. -D only when that is proven.
        if (!contentIsInHead(repositoryRoot, ref)) return false;
        git().output(repositoryRoot, "branch", "-D", "--", worktree.branch());
        return true;
    }

    /**
     * True when merging {@code ref} into HEAD would change nothing: the merge result's tree is
     * HEAD's own. Needs git 2.38+ for {@code merge-tree --write-tree}; an older git, a conflict or any
     * failure counts as "not merged", which keeps the branch.
     */
    private boolean contentIsInHead(Path repository, String ref) throws IOException {
        Git.Result merge = git().run(repository, "merge-tree", "--write-tree", "HEAD", ref);
        if (!merge.succeeded()) return false;
        String mergedTree = merge.output().lines().findFirst().orElse("").strip();
        String headTree = git().output(repository, "rev-parse", "HEAD^{tree}").strip();
        return !mergedTree.isEmpty() && mergedTree.equals(headTree);
    }

    @Override
    public boolean commitAll(Worktree worktree, String message) throws IOException {
        if (!Files.isDirectory(worktree.path())) return false;
        String nested = nestedRepository(worktree);
        if (nested != null) throw new NestedRepositoryException(nested);
        git().output(worktree.path(), inAgentCopy(worktree, "add", "-A"));
        Git.Result staged = git().run(worktree.path(), inAgentCopy(worktree,
                "diff", "--cached", "--quiet", "--no-ext-diff"));
        if (staged.exitCode() == 0) return false;
        if (staged.exitCode() != 1) throw Git.failure(staged, "diff", "--cached", "--quiet", "--no-ext-diff");
        // -m takes the next argument as the message even if it starts with a dash.
        git().output(worktree.path(), inAgentCopy(worktree, "commit", "-q", "--no-verify", "-m", message));
        return true;
    }

    @Override
    public boolean branchExists(Path repository, String branch) throws IOException {
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

    /** Deletes a folder and everything in it without following symbolic links out of it. */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Deletes a repository's folder of copies once only its record is left. Synchronized with
     * {@link #create}, so a copy being made never loses the record it needs.
     */
    private synchronized void deleteParentIfUnused(@Nullable Path parent) {
        if (parent == null || !owns(parent)) return;
        try (Stream<Path> entries = Files.list(parent)) {
            if (entries.anyMatch(entry -> !entry.getFileName().toString().equals(REPOSITORY_RECORD))) return;
            Files.deleteIfExists(parent.resolve(REPOSITORY_RECORD));
            Files.deleteIfExists(parent);
        } catch (DirectoryNotEmptyException ignored) {
            // Another copy of the same repository appeared meanwhile, from another IDE window.
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
