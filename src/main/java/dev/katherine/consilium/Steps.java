package dev.katherine.consilium;

import com.intellij.openapi.diagnostic.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Work that keeps the room busy without an agent running: reading the message, preparing,
 * committing or removing worktrees. Each step has its own token; Stop forgets them all, so a late
 * result finds its token gone and is dropped instead of freeing the room for a step that is still
 * running, or starting work nobody wants any more.
 *
 * <p>Confined to the UI thread, like {@link ChatSession}; only the work handed to {@link #run}
 * runs elsewhere.
 */
final class Steps {
    private static final Logger LOG = Logger.getInstance(Steps.class);
    private final Set<Object> active = new HashSet<>();
    private final Executor background;
    private final Executor ui;
    private final Runnable changed;

    /**
     * @param ui      hands a result back to the UI thread; drops it once the session is closed
     * @param changed announces the room's state after a step's result was handled
     */
    Steps(Executor background, Executor ui, Runnable changed) {
        this.background = background;
        this.ui = ui;
        this.changed = changed;
    }

    Object begin() {
        Object step = new Object();
        active.add(step);
        return step;
    }

    /** False when the step was forgotten by Stop: its result must be dropped. */
    boolean end(Object step) {
        return active.remove(step);
    }

    boolean isEmpty() {
        return active.isEmpty();
    }

    /** Forgets every step; true when any was running. */
    boolean forgetAll() {
        boolean any = !active.isEmpty();
        active.clear();
        return any;
    }

    /** {@link #run(Supplier, Consumer, Consumer)} for work whose result is simply dropped after Stop. */
    <T> void run(Supplier<T> work, Consumer<T> done) {
        run(work, done, ignored -> {
        });
    }

    /**
     * Runs blocking work in the background as one step, then hands its result to the UI thread:
     * to {@code done} while the step is still wanted, to {@code dropped} after Stop forgot it.
     * Either way the room's state is announced afterwards.
     */
    <T> void run(Supplier<T> work, Consumer<T> done, Consumer<T> dropped) {
        Object step = begin();
        background.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException exception) {
                // Without this the step never ends and the room stays busy until Stop.
                LOG.warn("A Consilium step failed", exception);
                ui.execute(() -> {
                    end(step);
                    changed.run();
                });
                return;
            }
            ui.execute(() -> {
                if (end(step)) {
                    done.accept(result);
                } else {
                    dropped.accept(result);
                }
                changed.run();
            });
        });
    }
}
