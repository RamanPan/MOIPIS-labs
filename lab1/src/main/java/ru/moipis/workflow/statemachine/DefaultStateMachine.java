package ru.moipis.workflow.statemachine;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.ConditionLiteral;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventEnvelope;
import ru.moipis.workflow.statemachine.event.EventProcessingResult;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.statemachine.exception.AmbiguousTransitionException;
import ru.moipis.workflow.statemachine.exception.NoApplicableTransitionException;
import ru.moipis.workflow.statemachine.handler.EventCommitter;
import ru.moipis.workflow.statemachine.handler.EventHandler;
import ru.moipis.workflow.statemachine.handler.EventPreparer;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiPredicate;

public final class DefaultStateMachine implements StateMachine {

    private final Object configurationLock = new Object();
    private final Object lifecycleLock = new Object();
    private final BlockingQueue<EventEnvelope> queue = new LinkedBlockingQueue<>();
    private final EventEnvelope stopMarker = new EventEnvelope(null, CompletableFuture.completedFuture(null));
    private final Thread worker;
    private final BiPredicate<DnfCondition, Event> conditionEvaluator;
    private final Map<String, StateDefinition> states = new LinkedHashMap<>();
    private final Map<StateEventKey, EventHandler> handlers = new LinkedHashMap<>();
    private final Map<StateEventKey, List<TransitionDefinition>> transitions = new LinkedHashMap<>();
    private volatile StateDefinition currentState;
    private volatile StateMachineLifecycle lifecycle = StateMachineLifecycle.STOPPED;
    private boolean closed;
    private EventPreparer eventPreparer = (event, state) -> event;
    private EventCommitter eventCommitter = (event, result) -> { };

    public DefaultStateMachine(String initialStateId) {
        this(new StateDefinition(initialStateId, initialStateId, true, false, Set.of(), Set.of()));
    }

    public DefaultStateMachine(StateDefinition initialState) {
        this(initialState, (condition, event) -> {
            throw new IllegalStateException("Condition evaluator is not configured");
        });
    }

    public DefaultStateMachine(StateDefinition initialState, BiPredicate<DnfCondition, Event> conditionEvaluator) {
        StateDefinition snapshot = copyState(initialState);
        this.conditionEvaluator = required(conditionEvaluator, "Condition evaluator");
        states.put(snapshot.id(), snapshot);
        currentState = snapshot;
        worker = Thread.ofPlatform().name("state-machine-worker").unstarted(this::runWorker);
    }

    public DefaultStateMachine(String initialStateId, BiPredicate<DnfCondition, Event> conditionEvaluator) {
        this(new StateDefinition(initialStateId, initialStateId, true, false, Set.of(), Set.of()), conditionEvaluator);
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (lifecycle == StateMachineLifecycle.STARTED) {
                return;
            }
            if (closed) {
                throw new IllegalStateException("Stopped state machine cannot be restarted");
            }
            lifecycle = StateMachineLifecycle.STARTED;
            try {
                worker.start();
            } catch (RuntimeException | Error failure) {
                closed = true;
                lifecycle = StateMachineLifecycle.STOPPED;
                throw failure;
            }
        }
    }

    @Override
    public CompletableFuture<EventResult> send(Event event) {
        Event snapshot;
        try {
            required(event, "Event");
            requireId(event.eventType(), "Event type");
            snapshot = new Event(event.eventType(), event.payload() == null ? null : event.payload().deepCopy(),
                    event.initiator(), event.context() == null ? null
                            : new ConditionContext(event.context().classId(), event.context().parameters()));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        synchronized (lifecycleLock) {
            if (lifecycle != StateMachineLifecycle.STARTED) {
                return CompletableFuture.failedFuture(new IllegalStateException("State machine is not started: " + lifecycle));
            }
            CompletableFuture<EventResult> future = new CompletableFuture<>();
            queue.add(new EventEnvelope(snapshot, future));
            return future;
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            if (!closed) {
                closed = true;
                if (lifecycle == StateMachineLifecycle.STARTED) {
                    lifecycle = StateMachineLifecycle.STOPPING;
                    queue.add(stopMarker);
                }
            }
        }
        if (Thread.currentThread() != worker) {
            awaitWorkerTermination();
        }
    }

    @Override
    public StateMachineLifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public StateDefinition getCurrentState() {
        return currentState;
    }

    @Override
    public void addState(StateDefinition state) {
        StateDefinition snapshot = copyState(state);
        synchronized (configurationLock) {
            if (states.putIfAbsent(snapshot.id(), snapshot) != null) {
                throw new IllegalArgumentException("Duplicate state: " + snapshot.id());
            }
        }
    }

    @Override
    public void registerHandler(String stateId, String eventType, EventHandler handler) {
        requireId(stateId, "State");
        requireId(eventType, "Event type");
        required(handler, "Handler");
        synchronized (configurationLock) {
            requireState(stateId);
            StateEventKey key = new StateEventKey(stateId, eventType);
            if (handlers.putIfAbsent(key, handler) != null) {
                throw new IllegalArgumentException("Handler already registered for " + key);
            }
        }
    }

    @Override
    public void setExecutionHooks(EventPreparer preparer, EventCommitter committer) {
        required(preparer, "Event preparer");
        required(committer, "Event committer");
        synchronized (configurationLock) {
            eventPreparer = preparer;
            eventCommitter = committer;
        }
    }

    @Override
    public void addTransition(TransitionDefinition transition) {
        required(transition, "Transition");
        requireId(transition.from(), "Source state");
        requireId(transition.action(), "Event type");
        requireId(transition.to(), "Target state");
        TransitionDefinition snapshot = new TransitionDefinition(transition.from(), transition.action(),
                transition.to(), copyCondition(transition.condition()));
        synchronized (configurationLock) {
            requireState(snapshot.from());
            requireState(snapshot.to());
            StateEventKey key = new StateEventKey(snapshot.from(), snapshot.action());
            transitions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(snapshot);
        }
    }

    @Override
    public List<TransitionDefinition> getTransitions(String stateId, String eventType) {
        requireId(stateId, "State");
        requireId(eventType, "Event type");
        synchronized (configurationLock) {
            requireState(stateId);
            return List.copyOf(transitions.getOrDefault(new StateEventKey(stateId, eventType), List.of()));
        }
    }

    @Override
    public EventProcessingResult processEvent(Event event) {
        if (Thread.currentThread() == worker) {
            throw new IllegalStateException("Worker cannot wait for its own queued event");
        }
        try {
            EventResult result = send(event).join();
            return new EventProcessingResult(result.previousStateId(), result.currentStateId(), result.handlerResult());
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Event processing failed", failure.getCause());
        }
    }

    private EventResult executeEvent(Event event) {
        EventSnapshot snapshot = snapshotEvent(event.eventType());
        Event prepared = required(snapshot.preparer().prepare(event, snapshot.source()), "Prepared event");
        if (!event.eventType().equals(prepared.eventType())) {
            throw new IllegalArgumentException("Event preparer cannot change the event type");
        }
        EventPlan plan = planEvent(prepared, snapshot);
        JsonNode handlerResult = plan.handler() == null ? null : plan.handler().handle(prepared);
        EventResult result = new EventResult(plan.source().id(), plan.target().id(), handlerResult);
        snapshot.committer().commit(prepared, result);
        currentState = plan.target();
        return result;
    }

    private void runWorker() {
        EventEnvelope active = null;
        Throwable terminalFailure = new IllegalStateException("Worker stopped before processing the event");
        try {
            while (true) {
                active = queue.take();
                if (active == stopMarker) {
                    break;
                }
                try {
                    active.future().complete(executeEvent(active.event()));
                } catch (Throwable failure) {
                    active.future().completeExceptionally(failure);
                }
                active = null;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            terminalFailure = new IllegalStateException("State machine worker was interrupted", failure);
        } catch (Throwable failure) {
            terminalFailure = failure;
        } finally {
            List<EventEnvelope> pending = new ArrayList<>();
            synchronized (lifecycleLock) {
                closed = true;
                lifecycle = StateMachineLifecycle.STOPPED;
                queue.drainTo(pending);
            }
            if (active != null && active != stopMarker) {
                active.future().completeExceptionally(terminalFailure);
            }
            for (EventEnvelope envelope : pending) {
                if (envelope != stopMarker) {
                    envelope.future().completeExceptionally(terminalFailure);
                }
            }
        }
    }

    private void awaitWorkerTermination() {
        boolean interrupted = false;
        while (true) {
            try {
                worker.join();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private EventPlan planEvent(Event event, EventSnapshot snapshot) {
        if (snapshot.transitions().isEmpty()) {
            if (snapshot.handler() == null) {
                throw new IllegalArgumentException("No handler or transition for state '%s' and event '%s'"
                        .formatted(snapshot.source().id(), event.eventType()));
            }
            return new EventPlan(snapshot.source(), snapshot.source(), snapshot.handler());
        }
        List<TransitionCandidate> matches = new ArrayList<>();
        for (TransitionCandidate candidate : snapshot.transitions()) {
            DnfCondition condition = candidate.definition().condition();
            if (condition == null || conditionEvaluator.test(condition, event)) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            throw new NoApplicableTransitionException("No applicable transition for state '%s' and event '%s'"
                    .formatted(snapshot.source().id(), event.eventType()));
        }
        if (matches.size() > 1) {
            throw new AmbiguousTransitionException("Multiple applicable transitions for state '%s' and event '%s'"
                    .formatted(snapshot.source().id(), event.eventType()));
        }
        return new EventPlan(snapshot.source(), matches.getFirst().target(), snapshot.handler());
    }

    private EventSnapshot snapshotEvent(String eventType) {
        synchronized (configurationLock) {
            StateDefinition source = currentState;
            StateEventKey key = new StateEventKey(source.id(), eventType);
            List<TransitionCandidate> candidates = transitions.getOrDefault(key, List.of()).stream()
                    .map(transition -> new TransitionCandidate(transition, requireState(transition.to()))).toList();
            return new EventSnapshot(source, handlers.get(key), candidates, eventPreparer, eventCommitter);
        }
    }

    private StateDefinition requireState(String stateId) {
        StateDefinition state = states.get(stateId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown state: " + stateId);
        }
        return state;
    }

    private static StateDefinition copyState(StateDefinition state) {
        required(state, "State");
        requireId(state.id(), "State");
        return new StateDefinition(state.id(), state.title(), state.initial(), state.terminal(),
                state.roles() == null ? Set.of() : Set.copyOf(state.roles()),
                state.editParameters());
    }

    private static DnfCondition copyCondition(DnfCondition condition) {
        if (condition == null) {
            return null;
        }
        return new DnfCondition(required(condition.groups(), "Condition groups").stream()
                .map(group -> required(group, "Condition group").stream()
                        .map(literal -> copyLiteral(required(literal, "Condition literal"))).toList()).toList());
    }

    private static ConditionLiteral copyLiteral(ConditionLiteral literal) {
        return new ConditionLiteral(literal.predicateId(), literal.negated());
    }

    private static <Value> Value required(Value value, String description) {
        if (value == null) {
            throw new IllegalArgumentException(description + " must not be null");
        }
        return value;
    }

    private static void requireId(String id, String description) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(description + " must have a non-blank id");
        }
    }

    private record EventPlan(StateDefinition source, StateDefinition target, EventHandler handler) {
    }

    private record TransitionCandidate(TransitionDefinition definition, StateDefinition target) {
    }

    private record EventSnapshot(StateDefinition source, EventHandler handler, List<TransitionCandidate> transitions,
                                 EventPreparer preparer, EventCommitter committer) {
    }
}
