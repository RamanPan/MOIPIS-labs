package ru.moipis.workflow.statemachine.handler;

import ru.moipis.workflow.statemachine.event.Event;

@FunctionalInterface
public interface TransitionGuard {

    boolean test(Event event);
}
