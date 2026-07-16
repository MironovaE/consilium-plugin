package dev.katherine.consilium.cli;

import java.nio.file.Files;
import java.nio.file.Path;

final class ExecutableResolver {
    private ExecutableResolver() {
    }

    static String resolve(String name) {
        Path local = Path.of(System.getProperty("user.home"), ".local", "bin", name);
        if (Files.isExecutable(local)) {
            return local.toString();
        }
        return name;
    }
}
