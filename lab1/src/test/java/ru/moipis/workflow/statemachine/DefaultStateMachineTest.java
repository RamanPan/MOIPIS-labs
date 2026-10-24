package ru.moipis.workflow.statemachine;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.moipis.workflow.condition.model.ConditionLiteral;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventProcessingResult;
import ru.moipis.workflow.statemachine.factory.DefaultStateMachineFactory;
import ru.moipis.workflow.statemachine.factory.StateMachineFactory;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStateMachineTest {

    private final List<StateMachine> machines = new ArrayList<>();

    @AfterEach
    void stopMachines() {
        machines.forEach(StateMachine::stop);
    }

    @Test
    void registersInitialAndAdditionalStatesWithoutChangingCurrentState() {
        StateMachine machine = started(new DefaultStateMachine(state("start")));
        machine.addState(state("next"));

        assertEquals("start", machine.getCurrentState().id());
        machine.addTransition(transition("start", "advance", "next"));
        machine.processEvent(event("advance"));
        assertEquals("next", machine.currentStateId());
    }

    @Test
    void eachFactoryInstanceHasIndependentStateAndConfiguration() {
        StateMachineFactory factory = new DefaultStateMachineFactory();
        StateMachine first = started(factory.create("start"));
        StateMachine second = started(factory.create("start"));
        first.registerState("next");
        first.registerTransition("start", "advance", "next");
        first.processEvent(event("advance"));

        assertEquals("next", first.currentStateId());
        assertEquals("start", second.currentStateId());
        assertThrows(IllegalArgumentException.class, () -> second.processEvent(event("advance")));
    }

    @Test
    void handlerReturnsAResultWithoutAStateChange() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerHandler("start", "inspect", event -> event.payload());
        Event input = new Event("inspect", TextNode.valueOf("result"), null);

        EventProcessingResult result = machine.processEvent(input);

        assertEquals("start", result.previousStateId());
        assertEquals("start", result.currentStateId());
        assertEquals(TextNode.valueOf("result"), result.handlerResult());
        assertEquals("start", machine.currentStateId());
    }

    @Test
    void runsHandlerBeforeCommittingTheConfiguredTransition() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerState("next");
        machine.registerTransition("start", "advance", "next");
        machine.registerHandler("start", "advance", event -> TextNode.valueOf(machine.currentStateId()));

        EventProcessingResult result = machine.processEvent(event("advance"));

        assertEquals("start", result.previousStateId());
        assertEquals("next", result.currentStateId());
        assertEquals(TextNode.valueOf("start"), result.handlerResult());
        assertEquals("next", machine.currentStateId());
    }

    @Test
    void supportsTransitionWithoutHandlerAndSelfTransition() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerTransition("start", "repeat", "start");

        EventProcessingResult result = machine.processEvent(event("repeat"));

        assertEquals("start", result.currentStateId());
        assertNull(result.handlerResult());
    }

    @Test
    void rejectsInvalidEventAndKeepsState() {
        StateMachine machine = started(new DefaultStateMachine("start"));

        assertThrows(IllegalArgumentException.class, () -> machine.processEvent(event("unknown")));
        assertEquals("start", machine.currentStateId());
    }

    @Test
    void handlerExceptionDoesNotChangeStateAndProcessingCanContinue() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerState("next");
        machine.registerTransition("start", "fail", "next");
        IllegalStateException failure = new IllegalStateException("Handler failed");
        machine.registerHandler("start", "fail", event -> { throw failure; });

        assertSame(failure, assertThrows(IllegalStateException.class, () -> machine.processEvent(event("fail"))));
        assertEquals("start", machine.currentStateId());
        machine.registerTransition("start", "recover", "next");
        machine.processEvent(event("recover"));
        assertEquals("next", machine.currentStateId());
    }

    @Test
    void supportsDynamicRegistrationAfterProcessingHasStarted() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerHandler("start", "inspect", event -> null);
        machine.processEvent(event("inspect"));

        machine.registerState("new_state");
        machine.registerHandler("start", "new_event", event -> TextNode.valueOf("new handler"));
        machine.registerTransition("start", "new_event", "new_state");

        assertEquals(TextNode.valueOf("new handler"), machine.processEvent(event("new_event")).handlerResult());
        assertEquals("new_state", machine.currentStateId());
    }

    @Test
    void handlersAreScopedToTheirStateAndEvent() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerState("next");
        machine.registerHandler("start", "inspect", event -> TextNode.valueOf("first"));
        machine.registerHandler("next", "inspect", event -> TextNode.valueOf("second"));
        machine.registerTransition("start", "advance", "next");

        assertEquals(TextNode.valueOf("first"), machine.processEvent(event("inspect")).handlerResult());
        machine.processEvent(event("advance"));
        assertEquals(TextNode.valueOf("second"), machine.processEvent(event("inspect")).handlerResult());
    }

    @Test
    void retainsMultipleConditionalTransitionsForOneKey() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerState("first");
        machine.registerState("second");
        TransitionDefinition first = new TransitionDefinition("start", "choose", "first", condition("first_condition"));
        TransitionDefinition second = new TransitionDefinition("start", "choose", "second", condition("second_condition"));
        machine.addTransition(first);
        machine.addTransition(second);

        assertEquals(List.of(first, second), machine.getTransitions("start", "choose"));
        assertThrows(IllegalStateException.class, () -> machine.processEvent(event("choose")));
        assertEquals("start", machine.currentStateId());
        assertEquals(2, machine.getTransitions("start", "choose").size());
    }

    @Test
    void rejectsAmbiguousUnconditionalTransitionsBeforeCallingHandler() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerState("first");
        machine.registerState("second");
        machine.registerTransition("start", "choose", "first");
        machine.registerTransition("start", "choose", "second");
        machine.registerHandler("start", "choose", event -> { throw new AssertionError("Must not run"); });

        assertThrows(IllegalStateException.class, () -> machine.processEvent(event("choose")));
        assertEquals("start", machine.currentStateId());
    }

    @Test
    void rejectsConditionalTransitionsWhenNoEvaluatorIsConfigured() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerState("next");
        machine.addTransition(new TransitionDefinition("start", "advance", "next", condition("predicate")));

        assertThrows(IllegalStateException.class, () -> machine.processEvent(event("advance")));
        assertEquals("start", machine.currentStateId());
    }

    @Test
    void rejectsDuplicateStatesHandlersAndUnknownTransitionReferences() {
        StateMachine machine = started(new DefaultStateMachine("start"));

        assertThrows(IllegalArgumentException.class, () -> machine.registerState("start"));
        assertThrows(IllegalArgumentException.class, () -> machine.registerHandler("missing", "event", event -> null));
        assertThrows(IllegalArgumentException.class, () -> machine.registerTransition("start", "event", "missing"));
        assertThrows(IllegalArgumentException.class, () -> machine.registerTransition("missing", "event", "start"));
        machine.registerHandler("start", "event", event -> null);
        assertThrows(IllegalArgumentException.class, () -> machine.registerHandler("start", "event", event -> null));
    }

    @Test
    void rejectsNullAndBlankInputs() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultStateMachine(" "));
        assertThrows(IllegalArgumentException.class, () -> new DefaultStateMachine((StateDefinition) null));
        StateMachine machine = started(new DefaultStateMachine("start"));

        assertThrows(IllegalArgumentException.class, () -> machine.addState(null));
        assertThrows(IllegalArgumentException.class, () -> machine.addTransition(null));
        assertThrows(IllegalArgumentException.class, () -> machine.registerHandler("start", "event", null));
        assertThrows(IllegalArgumentException.class, () -> machine.registerHandler("start", " ", event -> null));
        assertThrows(IllegalArgumentException.class, () -> machine.processEvent(null));
        assertThrows(IllegalArgumentException.class, () -> machine.processEvent(event(" ")));
    }

    @Test
    void snapshotsStateCollectionsAndTransitionConditions() {
        Set<String> roles = new HashSet<>(Set.of("metadata"));
        StateMachine machine = started(new DefaultStateMachine(
                new StateDefinition("start", "Start", true, false, roles, Set.of())));
        roles.clear();
        machine.registerState("next");
        List<ConditionLiteral> group = new ArrayList<>(List.of(new ConditionLiteral("predicate", false)));
        List<List<ConditionLiteral>> groups = new ArrayList<>(List.of(group));
        TransitionDefinition transition = new TransitionDefinition("start", "advance", "next", new DnfCondition(groups));
        machine.addTransition(transition);
        group.clear();
        groups.clear();

        assertEquals(Set.of("metadata"), machine.getCurrentState().roles());
        assertEquals(condition("predicate"), machine.getTransitions("start", "advance").getFirst().condition());
        assertNotSame(transition, machine.getTransitions("start", "advance").getFirst());
        assertThrows(UnsupportedOperationException.class, () -> machine.getTransitions("start", "advance").clear());
        assertThrows(UnsupportedOperationException.class,
                () -> machine.getTransitions("start", "advance").getFirst().condition().groups().getFirst().clear());
    }

    @Test
    void doesNotHoldConfigurationLockDuringHandlerAndUsesAnEventSnapshot() throws Exception {
        StateMachine machine = started(new DefaultStateMachine("start"));
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        machine.registerHandler("start", "event", event -> {
            handlerEntered.countDown();
            await(releaseHandler);
            return TextNode.valueOf("handled");
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var processing = executor.submit(() -> machine.processEvent(event("event")));
            try {
                assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
                executor.submit(() -> {
                    machine.registerState("next");
                    machine.registerHandler("next", "inspect", event -> TextNode.valueOf("registered"));
                    machine.registerTransition("start", "event", "next");
                    return machine.getTransitions("start", "event");
                }).get(5, TimeUnit.SECONDS);
                assertEquals("start", machine.currentStateId());
            } finally {
                releaseHandler.countDown();
            }
            assertEquals("start", processing.get(5, TimeUnit.SECONDS).currentStateId());
            assertEquals("next", machine.processEvent(event("event")).currentStateId());
            assertEquals(TextNode.valueOf("registered"), machine.processEvent(event("inspect")).handlerResult());
        }
    }

    @Test
    void serializesConcurrentEventsWithoutLosingStateChanges() throws Exception {
        StateMachine machine = started(new DefaultStateMachine("first"));
        machine.registerState("second");
        machine.registerState("third");
        machine.registerTransition("first", "advance", "second");
        machine.registerTransition("second", "advance", "third");
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        machine.registerHandler("first", "advance", event -> {
            invocations.incrementAndGet();
            handlerEntered.countDown();
            await(releaseHandler);
            return null;
        });
        machine.registerHandler("second", "advance", event -> TextNode.valueOf("second handler"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> machine.processEvent(event("advance")));
            try {
                assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
                CountDownLatch secondSubmitted = new CountDownLatch(1);
                var second = executor.submit(() -> {
                    secondSubmitted.countDown();
                    return machine.processEvent(event("advance"));
                });
                assertTrue(secondSubmitted.await(5, TimeUnit.SECONDS));
                releaseHandler.countDown();
                assertEquals("second", first.get(5, TimeUnit.SECONDS).currentStateId());
                EventProcessingResult result = second.get(5, TimeUnit.SECONDS);
                assertEquals("third", result.currentStateId());
                assertEquals(TextNode.valueOf("second handler"), result.handlerResult());
            } finally {
                releaseHandler.countDown();
            }
        }
        assertEquals(1, invocations.get());
        assertEquals("third", machine.currentStateId());
    }

    @Test
    void forbidsReentrantEventProcessingSoAFailedHandlerCannotChangeState() {
        StateMachine machine = started(new DefaultStateMachine("start"));
        machine.registerState("next");
        machine.registerTransition("start", "nested", "next");
        machine.registerHandler("start", "outer", event -> {
            machine.processEvent(event("nested"));
            throw new AssertionError("Reentrant processing must fail");
        });

        assertThrows(IllegalStateException.class, () -> machine.processEvent(event("outer")));
        assertEquals("start", machine.currentStateId());
        machine.processEvent(event("nested"));
        assertEquals("next", machine.currentStateId());
    }

    private StateMachine started(StateMachine machine) {
        machines.add(machine);
        machine.start();
        return machine;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static StateDefinition state(String id) {
        return new StateDefinition(id, id, false, false, Set.of(), Set.of());
    }

    private static TransitionDefinition transition(String from, String eventType, String to) {
        return new TransitionDefinition(from, eventType, to, null);
    }

    private static Event event(String type) {
        return new Event(type, null, null);
    }

    private static DnfCondition condition(String predicateId) {
        return new DnfCondition(List.of(List.of(new ConditionLiteral(predicateId, false))));
    }
}
