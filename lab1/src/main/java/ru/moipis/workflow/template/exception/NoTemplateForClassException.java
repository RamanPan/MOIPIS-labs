package ru.moipis.workflow.template.exception;

/** A known class has no process template of its own or in its ancestors. */
public final class NoTemplateForClassException extends IllegalArgumentException {
    private final String classId;

    public NoTemplateForClassException(String classId) {
        super("No template for class or its ancestors: " + classId);
        this.classId = classId;
    }

    public String classId() { return classId; }
}
