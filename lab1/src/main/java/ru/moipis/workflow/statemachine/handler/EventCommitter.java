package ru.moipis.workflow.statemachine.handler;

import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventResult;

@FunctionalInterface
public interface EventCommitter {
    void commit(Event event, EventResult result);
}
