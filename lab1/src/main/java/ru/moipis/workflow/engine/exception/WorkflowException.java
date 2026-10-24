package ru.moipis.workflow.engine.exception;

public final class WorkflowException extends RuntimeException {
    private final WorkflowError code;

    public WorkflowException(WorkflowError code, String message) {
        this(code, message, null);
    }

    public WorkflowException(WorkflowError code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public WorkflowError code() {
        return code;
    }
}
