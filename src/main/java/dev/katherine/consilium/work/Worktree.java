package dev.katherine.consilium.work;

import java.nio.file.Path;

/** A separate checkout where one agent does its part of a parallel task. */
public record Worktree(Path path, String branch) {
}
