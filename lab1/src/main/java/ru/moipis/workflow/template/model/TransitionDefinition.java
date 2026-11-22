package ru.moipis.workflow.template.model;

import ru.moipis.workflow.condition.model.DnfCondition;

import java.util.List;

public record TransitionDefinition(
        String from,
        String action,
        String to,
        DnfCondition condition,
        List<EventEmission> emit
) {
    public TransitionDefinition {
        emit = emit == null ? List.of() : List.copyOf(emit);
    }

    public TransitionDefinition(String from, String action, String to, DnfCondition condition) {
        this(from, action, to, condition, List.of());
    }
}
