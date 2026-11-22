package ru.moipis.workflow.template.model;

import ru.moipis.workflow.condition.model.PredicateDefinition;

import java.util.List;
import java.util.Set;

public record ProcessTemplate(
        String id,
        String title,
        String classId,
        Set<String> createRoles,
        List<ActionDefinition> actions,
        List<PredicateDefinition> predicates,
        List<StateDefinition> states,
        List<TransitionDefinition> transitions,
        List<EventTransitionDefinition> eventTransitions
) {
    public ProcessTemplate {
        eventTransitions = eventTransitions == null ? List.of() : List.copyOf(eventTransitions);
    }

    public ProcessTemplate(String id, String title, String classId, Set<String> createRoles,
                           List<ActionDefinition> actions, List<PredicateDefinition> predicates,
                           List<StateDefinition> states, List<TransitionDefinition> transitions) {
        this(id, title, classId, createRoles, actions, predicates, states, transitions, List.of());
    }
}
