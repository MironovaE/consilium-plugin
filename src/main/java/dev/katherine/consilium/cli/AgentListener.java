package dev.katherine.consilium.cli;

public interface AgentListener {
    void onSessionId(String sessionId);

    void onTextDelta(String text);

    void onCompleted();

    void onError(String message);
}
