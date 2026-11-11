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

    /** True when the agent is one that can do a part: Claude or Codex. */
    public boolean hasKnownAgent() {
        return Speaker.CLAUDE.name().equals(agent) || Speaker.CODEX.name().equals(agent);
    }

    /**
     * Valid only for a record with a {@linkplain #hasKnownAgent() known agent}; records are checked
     * when they are loaded. Mapping an unknown name to some agent would hand that agent's branch
     * and report to the wrong one.
     */
    public Speaker agentValue() {
        if (!hasKnownAgent()) throw new IllegalStateException("Not an agent of a parallel part: " + agent);
        return Speaker.valueOf(agent);
    }
}
