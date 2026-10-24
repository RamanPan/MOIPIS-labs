package ru.moipis.workflow.statemachine;

import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventProcessingResult;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.statemachine.handler.EventCommitter;
import ru.moipis.workflow.statemachine.handler.EventHandler;
import ru.moipis.workflow.statemachine.handler.EventPreparer;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface StateMachine extends AutoCloseable {

    void start();

    CompletableFuture<EventResult> send(Event event);

    void stop();

    StateMachineLifecycle getLifecycle();

    @Override
    default void close() {
        stop();
    }

    StateDefinition getCurrentState();

    default String currentStateId() {
        return getCurrentState().id();
    }

    void addState(StateDefinition state);

    default void registerState(String stateId) {
        addState(new StateDefinition(stateId, stateId, false, false, Set.of(), Set.of()));
    }

    void registerHandler(String stateId, String eventType, EventHandler handler);

    void setExecutionHooks(EventPreparer preparer, EventCommitter committer);

    void addTransition(TransitionDefinition transition);

    default void registerTransition(String from, String eventType, String to) {
        addTransition(new TransitionDefinition(from, eventType, to, null));
    }

    List<TransitionDefinition> getTransitions(String stateId, String eventType);

    EventProcessingResult processEvent(Event event);
}
