package dev.katherine.consilium.model;

public final class AgentSession {
    public String sessionId;
    public int seenThrough;

    @SuppressWarnings("unused")
    public AgentSession() {
    }

    public AgentSession(String sessionId, int seenThrough) {
        this.sessionId = sessionId;
        this.seenThrough = seenThrough;
    }
}
