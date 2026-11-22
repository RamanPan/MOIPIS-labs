package ru.moipis.workflow.template.model;

import ru.moipis.workflow.condition.model.DnfCondition;

import java.util.List;

public record EventTransitionDefinition(String from, String event, String sourceLink, String to,
                                        DnfCondition condition, List<EventEmission> emit) {
    public EventTransitionDefinition {
        if (event == null || event.isBlank() || sourceLink == null || sourceLink.isBlank()) {
            throw new IllegalArgumentException("Event transition requires an event and a source link");
        }
        emit = emit == null ? List.of() : List.copyOf(emit);
    }
}
