package dev.katherine.consilium.cli;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A CLI process with everything it spawned: MCP servers, shell commands, a dev server started with
 * {@code &}. Killing only the CLI leaves those running, and since they inherit its stdout the run
 * would never see end of stream.
 *
 * <p>Built on {@link ProcessHandle} rather than the platform's {@code OSProcessUtil.killProcessTree}
 * or {@code KillableProcessHandler}: the platform kill is SIGKILL-only on Unix, giving MCP servers
 * no chance to shut down cleanly, and a process handler would bring its own output pumping that this
 * run doesn't use. {@code ProcessHandle} offers SIGTERM, SIGKILL and exit notifications on any OS
 * and checks process start times, so a recycled pid is never signalled.
 *
 * <p>Descendants are recorded while the CLI is alive because once it exits, its children are
 * re-parented to init and can no longer be found from it. A process that daemonizes between two
 * polls escapes; Java offers no process groups to close that gap.
 */
final class ProcessTree {
    static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    // After SIGKILL only an unkillable process (stuck in the kernel) remains; stop waiting for it so
    // terminated() can't hang the panel.
    private static final Duration GIVE_UP_AFTER_KILL = Duration.ofSeconds(5);
    private static final Logger LOG = Logger.getInstance(ProcessTree.class);

    private final Process root;
    private final Set<ProcessHandle> seen = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CompletableFuture<Void> gone = new CompletableFuture<>();
    private final ScheduledFuture<?> poll;

    ProcessTree(Process root) {
        this.root = root;
        poll = scheduler().scheduleWithFixedDelay(this::recordDescendants, 0, POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        // Once the CLI itself has exited, whatever it left behind is an orphan: reap it right away.
        root.onExit().thenRun(this::stop);
    }

    /** Completes when the CLI and every recorded descendant have exited. */
    CompletableFuture<Void> gone() {
        return gone;
    }

    /** Remembers the current descendants; call it when the CLI is likely about to exit. */
    void recordDescendants() {
        if (!root.isAlive()) return;
        // Pruning keeps a long turn that runs thousands of short commands from growing the set.
        seen.removeIf(handle -> !handle.isAlive());
        root.descendants().forEach(seen::add);
    }

    /** Stops the tree after {@code grace} unless the CLI exits on its own first. */
    void stopAfter(Duration grace) {
        if (gone.isDone()) return;
        ScheduledFuture<?> timer = scheduler().schedule(this::stop, grace.toMillis(), TimeUnit.MILLISECONDS);
        gone.whenComplete((ignored, error) -> timer.cancel(false));
    }

    /**
     * Sends SIGTERM to the descendants and then the CLI, and SIGKILL to whatever is still alive
     * {@link #KILL_GRACE} later. Never blocks; repeated calls are no-ops.
     */
    void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        recordDescendants();
        poll.cancel(false);

        List<ProcessHandle> tree = new ArrayList<>();
        for (ProcessHandle handle : seen) {
            if (handle.isAlive()) tree.add(handle);
        }
        // Leaves first: a CLI that sees its tool die may report or restart it before it gets the
        // signal itself.
        tree.forEach(ProcessHandle::destroy);
        root.destroy();
        tree.add(root.toHandle());

        List<CompletableFuture<?>> exits = new ArrayList<>();
        exits.add(root.onExit());
        for (ProcessHandle handle : tree) exits.add(handle.onExit());
        CompletableFuture<Void> allExited = CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new));

        ScheduledFuture<?> kill = scheduler().schedule(() -> {
            for (ProcessHandle handle : tree) {
                if (handle.isAlive()) handle.destroyForcibly();
            }
        }, KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> giveUp = scheduler().schedule(() -> {
            if (gone.complete(null)) LOG.warn("CLI process tree still alive after SIGKILL; no longer waiting for it");
        }, KILL_GRACE.plus(GIVE_UP_AFTER_KILL).toMillis(), TimeUnit.MILLISECONDS);
        allExited.whenComplete((ignored, error) -> {
            kill.cancel(false);
            giveUp.cancel(false);
            gone.complete(null);
        });
    }

    private static ScheduledExecutorService scheduler() {
        return AppExecutorUtil.getAppScheduledExecutorService();
    }
}
