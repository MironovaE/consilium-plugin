package dev.katherine.consilium.work;

import java.io.IOException;

/**
 * An agent's copy holds another git repository, so Consilium won't stage it. Staging records it as
 * a gitlink, and later commands that look inside it run under that repository's own config, which
 * the agent wrote: an fsmonitor or a filter driver there is a command git would run as the person.
 */
public final class NestedRepositoryException extends IOException {
    // Only logged: the person learns about the part from the merge check, which finds the same repository.
    NestedRepositoryException(String path) {
        super("The agent's copy contains another git repository at " + path);
    }
}
