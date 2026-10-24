package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.StateMachine;
import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.statemachine.handler.EventHandler;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static ru.moipis.workflow.engine.DefaultWorkflowEngine.UPDATE_PARAMETER;
import static ru.moipis.workflow.engine.exception.WorkflowError.*;

final class ObjectRuntime implements AutoCloseable {
    private final DefaultWorkflowEngine engine;
    private final StateMachine machine;
    private final ProcessTemplate template;
    private final BusinessObject identity;
    private PreparedOperation active;

    ObjectRuntime(DefaultWorkflowEngine engine, StateMachine machine, ProcessTemplate template, BusinessObject identity) {
        this.engine = engine;
        this.machine = machine;
        this.template = template;
        this.identity = identity;
        machine.setExecutionHooks(this::prepare, this::commit);
        for (StateDefinition state : template.states()) {
            machine.registerHandler(state.id(), UPDATE_PARAMETER, event -> null);
            Set<String> registered = new HashSet<>();
            template.transitions().stream().filter(transition -> transition.from().equals(state.id())).forEach(transition -> {
                if (registered.add(transition.action())) {
                    machine.registerHandler(state.id(), transition.action(), this::handleAction);
                }
            });
        }
        machine.start();
    }

    CompletableFuture<EventResult> send(Event event) {
        return machine.send(event);
    }

    private Event prepare(Event event, StateDefinition state) {
        engine.workerEntered();
        active = null;
        EventHandler handler = engine.handler(event.eventType());
        StoreData store = engine.load();
        BusinessObject before = DefaultWorkflowEngine.object(store, identity.id());
        if (!Objects.equals(before.classId(), identity.classId())
                || !Objects.equals(before.templateId(), identity.templateId()) || !before.stateId().equals(state.id())) {
            throw new WorkflowException(CONCURRENT_MODIFICATION, "Object changed outside its state machine: " + identity.id());
        }
        var user = DefaultWorkflowEngine.user(store, event.initiator().userId());
        if (state.terminal()) {
            throw new WorkflowException(FINAL_STATE, "Object is in final state: " + state.id());
        }
        if (!DefaultWorkflowEngine.hasRole(user, state.roles())) {
            throw new WorkflowException(ACCESS_DENIED, "User has no role for state: " + state.id());
        }
        BusinessObject updated = null;
        String parameterId = null;
        if (UPDATE_PARAMETER.equals(event.eventType())) {
            if (!state.editParameters().enabled()) {
                throw new WorkflowException(ACCESS_DENIED, "Parameter editing is disabled in state: " + state.id());
            }
            parameterId = event.payload().path("parameterId").asText(null);
            JsonNode value = event.payload().get("value");
            ParameterValues.validate(parameterId, value, engine.parameters(before.classId()));
            if (!state.editParameters().allows(parameterId)) {
                throw new WorkflowException(ACCESS_DENIED, "Parameter cannot be edited in this state: " + parameterId);
            }
            Map<String, JsonNode> values = ParameterValues.copy(before.parameters());
            values.put(parameterId, value == null ? null : value.deepCopy());
            updated = new BusinessObject(before.id(), before.classId(), before.stateId(), values, before.templateId());
        } else if (template.transitions().stream().noneMatch(transition -> transition.from().equals(state.id())
                && transition.action().equals(event.eventType()))) {
            throw new WorkflowException(INVALID_ACTION, "No outgoing transition for action: " + event.eventType());
        }
        active = new PreparedOperation(before, updated, user.id(), parameterId, handler);
        return new Event(event.eventType(), event.payload(), event.initiator(), ConditionContext.from(before));
    }

    private JsonNode handleAction(Event event) {
        if (active.handler() == null) {
            return null;
        }
        try {
            return active.handler().handle(event);
        } catch (RuntimeException failure) {
            throw new WorkflowException(HANDLER_ERROR, "Handler failed for action: " + event.eventType(), failure);
        }
    }

    private void commit(Event event, EventResult result) {
        PreparedOperation operation = active;
        active = null;
        BusinessObject before = operation.before();
        if (UPDATE_PARAMETER.equals(event.eventType())) {
            engine.commit(ObjectChange.updateParameter(before, operation.updated(), operation.userId(), operation.parameterId()));
        } else {
            BusinessObject after = new BusinessObject(before.id(), before.classId(), result.currentStateId(),
                    before.parameters(), before.templateId());
            engine.commit(ObjectChange.action(before, after, operation.userId(), event.eventType()));
        }
    }

    @Override
    public void close() {
        machine.stop();
        active = null;
    }

    private record PreparedOperation(BusinessObject before, BusinessObject updated, String userId,
                                     String parameterId, EventHandler handler) {
    }
}
