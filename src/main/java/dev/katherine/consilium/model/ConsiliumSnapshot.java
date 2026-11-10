package dev.katherine.consilium.model;

import java.util.ArrayList;
import java.util.List;

public final class ConsiliumSnapshot {
    public List<ChatMessage> messages = new ArrayList<>();
    public AgentSession claude = new AgentSession(null, 0);
    public AgentSession codex = new AgentSession(null, 0);
    public List<PendingWork> pendingWork = new ArrayList<>();
}
