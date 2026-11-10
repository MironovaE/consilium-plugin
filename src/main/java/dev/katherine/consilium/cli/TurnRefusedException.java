package dev.katherine.consilium.cli;

import java.io.IOException;

/**
 * The plugin itself declined to start a turn because it couldn't guarantee its access mode. The
 * message is complete and shown as is, not wrapped as a launch failure of the CLI.
 */
final class TurnRefusedException extends IOException {
    TurnRefusedException(String message) {
        super(message);
    }
}
