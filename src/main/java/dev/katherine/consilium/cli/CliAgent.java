package dev.katherine.consilium.cli;

import dev.katherine.consilium.model.Speaker;

import java.nio.file.Path;

public interface CliAgent {
    Speaker speaker();

    RunningRequest start(Path projectRoot, String prompt, String sessionId, AgentAccess access, AgentListener listener);
}
