package ru.moipis.workflow.statemachine.handler;

import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.template.model.StateDefinition;

@FunctionalInterface
public interface EventPreparer {
    Event prepare(Event event, StateDefinition currentState);
}
