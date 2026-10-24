package ru.moipis.workflow.statemachine.exception;

public final class NoApplicableTransitionException extends IllegalStateException {

    public NoApplicableTransitionException(String message) {
        super(message);
    }
}
