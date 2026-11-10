package dev.katherine.consilium.model;

/**
 * One agent's part of a parallel task that isn't merged yet: its own worktree and branch. Stored
 * with the transcript so "merge" still works after the IDE restarts. Plain fields for Gson.
 */
public final class PendingWork {
    public String agent;
    public String path;
    public String branch;
    public String baseCommit;

    @SuppressWarnings("unused")
    public PendingWork() {
    }

    public PendingWork(Speaker agent, String path, String branch, String baseCommit) {
        this.agent = agent.name();
        this.path = path;
        this.branch = branch;
        this.baseCommit = baseCommit;
    }

    public Speaker agentValue() {
        try {
            return Speaker.valueOf(agent);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Speaker.CLAUDE;
        }
    }
}
