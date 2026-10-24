package ru.moipis.workflow.template.model;

import ru.moipis.workflow.condition.model.DnfCondition;

public record TransitionDefinition(
        String from,
        String action,
        String to,
        DnfCondition condition
) {
}
