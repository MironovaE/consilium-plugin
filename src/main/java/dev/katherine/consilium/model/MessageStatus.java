package dev.katherine.consilium.model;

/**
 * How a transcript entry ended. Kept apart from the text so that an error is never shown to the
 * other agent as something this agent said, and still renders as an error after a restart.
 */
public enum MessageStatus {
    /** A user message or a finished agent answer. */
    ANSWER,
    /** The part of an answer written before Stop. */
    STOPPED,
    /** The agent failed; the text is the error detail. Never sent to agents. */
    ERROR
}
