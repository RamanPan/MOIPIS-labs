package ru.moipis.workflow.statemachine.exception;

public final class AmbiguousTransitionException extends IllegalStateException {

    public AmbiguousTransitionException(String message) {
        super(message);
    }
}
