package dev.katherine.consilium.cli;

/** What an agent may do in a turn, and whose settings it runs with. */
public enum AgentAccess {
    /** Discussion: read-only tools, no project settings. */
    READ_ONLY,
    /** Edits files and loads the project's own agent settings (hooks, MCP servers). */
    WRITE,
    /**
     * Edits files but loads only the person's own settings: for an agent's copy, where another
     * agent may have written the project settings, and for a project IntelliJ doesn't trust.
     */
    WRITE_ISOLATED;

    public boolean writes() {
        return this != READ_ONLY;
    }
}
