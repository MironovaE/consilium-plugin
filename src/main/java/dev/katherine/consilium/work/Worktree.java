package dev.katherine.consilium.work;

import java.nio.file.Path;

/**
 * A separate checkout where one agent does its part of a parallel task.
 *
 * @param baseCommit the full hash of the commit the worktree was created from; the agent's own
 *                   commits are the ones on {@code branch} that this commit can't reach
 */
public record Worktree(Path path, String branch, String baseCommit) {
}
