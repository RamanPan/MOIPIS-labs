package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.ParameterDefinition;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.condition.ConditionEvaluator;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.engine.exception.WorkflowError;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.statemachine.event.InitiatorContext;
import ru.moipis.workflow.statemachine.exception.AmbiguousTransitionException;
import ru.moipis.workflow.statemachine.exception.NoApplicableTransitionException;
import ru.moipis.workflow.statemachine.factory.DefaultStateMachineFactory;
import ru.moipis.workflow.statemachine.factory.StateMachineConfiguration;
import ru.moipis.workflow.statemachine.factory.StateMachineFactory;
import ru.moipis.workflow.statemachine.handler.EventHandler;
import ru.moipis.workflow.template.TemplateCatalogService;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static ru.moipis.workflow.engine.exception.WorkflowError.*;

public final class DefaultWorkflowEngine implements WorkflowEngine {
    static final String UPDATE_PARAMETER = "update_parameter";
    private final Object lifecycleLock = new Object();
    private final Repository repository;
    private final CatalogService catalog;
    private final TemplateCatalogService templates;
    private final ConditionEvaluator evaluator;
    private final StateMachineFactory factory;
    private final Map<String, ObjectRuntime> runtimes = new LinkedHashMap<>();
    private final Map<String, EventHandler> handlers = new ConcurrentHashMap<>();
    private final Set<Thread> ownedThreads = ConcurrentHashMap.newKeySet();
    private final ExecutorService creations = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .name("workflow-create-worker").unstarted(() -> {
                ownedThreads.add(Thread.currentThread());
                try {
                    task.run();
                } finally {
                    ownedThreads.remove(Thread.currentThread());
                }
            }));
    private boolean closed;

    public DefaultWorkflowEngine(Repository repository) throws IOException {
        this.repository = Objects.requireNonNull(repository, "Repository");
        catalog = new CatalogService(repository.loadOrInitialize().classes());
        templates = new TemplateCatalogService(catalog, repository);
        evaluator = new DefaultConditionEvaluator(catalog);
        factory = new DefaultStateMachineFactory();
    }

    public DefaultWorkflowEngine(CatalogService catalog, TemplateCatalogService templates,
                                 ConditionEvaluator evaluator, Repository repository, StateMachineFactory factory) {
        this.catalog = Objects.requireNonNull(catalog, "Class catalog");
        this.templates = Objects.requireNonNull(templates, "Template catalog");
        this.evaluator = Objects.requireNonNull(evaluator, "Condition evaluator");
        this.repository = Objects.requireNonNull(repository, "Repository");
        this.factory = Objects.requireNonNull(factory, "State machine factory");
    }

    @Override
    public CompletableFuture<BusinessObject> createObject(String userId, String classId, Map<String, JsonNode> parameters) {
        synchronized (lifecycleLock) {
            try {
                requireOpen();
                Map<String, JsonNode> snapshot = ParameterValues.copy(parameters);
                return translate(CompletableFuture.supplyAsync(() -> create(userId, classId, snapshot), creations));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(classify(failure));
            }
        }
    }

    @Override
    public CompletableFuture<EventResult> executeAction(String userId, String objectId, String actionId) {
        if (UPDATE_PARAMETER.equals(actionId)) {
            return CompletableFuture.failedFuture(new WorkflowException(INVALID_ACTION, "Reserved internal event: " + actionId));
        }
        return send(objectId, new Event(actionId, null, new InitiatorContext(userId)));
    }

    @Override
    public CompletableFuture<EventResult> updateParameter(String userId, String objectId, String parameterId, JsonNode value) {
        var payload = JsonNodeFactory.instance.objectNode();
        payload.put("parameterId", parameterId);
        payload.set("value", value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy());
        return send(objectId, new Event(UPDATE_PARAMETER, payload, new InitiatorContext(userId)));
    }

    @Override
    public BusinessObject getObject(String objectId) {
        return object(load(), objectId);
    }

    @Override
    public List<String> getAvailableActions(String userId, String objectId) {
        StoreData store = load();
        BusinessObject object = object(store, objectId);
        UserDefinition user = user(store, userId);
        ProcessTemplate template = template(object);
        StateDefinition state = state(template, object.stateId());
        if (state.terminal() || !hasRole(user, state.roles())) {
            return List.of();
        }
        return template.transitions().stream().filter(transition -> transition.from().equals(state.id()))
                .map(TransitionDefinition::action).distinct().toList();
    }

    @Override
    public List<HistoryEntry> getHistory(String objectId) {
        StoreData store = load();
        object(store, objectId);
        return store.history().stream().filter(entry -> entry.objectId().equals(objectId)).toList();
    }

    @Override
    public void registerHandler(String actionId, EventHandler handler) {
        synchronized (lifecycleLock) {
            requireOpen();
            if (actionId == null || actionId.isBlank() || UPDATE_PARAMETER.equals(actionId)) {
                throw new WorkflowException(INVALID_ACTION, "Invalid or reserved action id: " + actionId);
            }
            handlers.put(actionId, Objects.requireNonNull(handler, "Event handler"));
        }
    }

    @Override
    public void close() {
        if (ownedThreads.contains(Thread.currentThread())) {
            throw new IllegalStateException("WorkflowEngine.close must be called outside its workers and completion callbacks");
        }
        List<ObjectRuntime> active;
        synchronized (lifecycleLock) {
            closed = true;
            creations.shutdown();
            active = new ArrayList<>(runtimes.values());
        }
        active.forEach(ObjectRuntime::close);
        boolean interrupted = false;
        while (true) {
            try {
                if (creations.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        synchronized (lifecycleLock) {
            runtimes.clear();
            handlers.clear();
            ownedThreads.clear();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private CompletableFuture<EventResult> send(String objectId, Event event) {
        synchronized (lifecycleLock) {
            try {
                requireOpen();
                if (event.eventType() == null || event.eventType().isBlank()) {
                    throw new WorkflowException(INVALID_ACTION, "Action id must not be blank");
                }
                ObjectRuntime runtime = runtimes.get(objectId);
                if (runtime == null) {
                    BusinessObject object = getObject(objectId);
                    ProcessTemplate template = template(object);
                    StateMachineConfiguration configuration = new StateMachineConfiguration(object.classId(),
                            object.stateId(), template, catalog.getEffectiveParameters(object.classId()));
                    runtime = new ObjectRuntime(this, factory.create(configuration, evaluator), template, object);
                    runtimes.put(objectId, runtime);
                }
                return translate(runtime.send(event));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(classify(failure));
            }
        }
    }

    private BusinessObject create(String userId, String classId, Map<String, JsonNode> values) {
        try {
            catalog.getClass(classId);
        } catch (IllegalArgumentException failure) {
            throw new WorkflowException(NOT_FOUND, "Unknown class: " + classId, failure);
        }
        StateMachineConfiguration configuration;
        try {
            configuration = templates.prepareStateMachineConfiguration(classId);
        } catch (IOException failure) {
            throw persistence(failure);
        } catch (IllegalArgumentException failure) {
            throw new WorkflowException(TEMPLATE_ERROR, failure.getMessage(), failure);
        }
        rejectReservedAction(configuration.template());
        UserDefinition user = user(load(), userId);
        if (!hasRole(user, configuration.template().createRoles())) {
            throw new WorkflowException(ACCESS_DENIED, "User has no creation role for class: " + classId);
        }
        values.forEach((id, value) -> ParameterValues.validate(id, value, configuration.effectiveParameters()));
        BusinessObject object = new BusinessObject(UUID.randomUUID().toString(), classId,
                configuration.initialStateId(), values, configuration.template().id());
        commit(ObjectChange.create(object, userId));
        return object;
    }

    StoreData load() {
        try {
            return repository.load();
        } catch (IOException failure) {
            throw persistence(failure);
        }
    }

    void commit(ObjectChange change) {
        try {
            repository.commit(change);
        } catch (IOException failure) {
            throw persistence(failure);
        }
    }

    void workerEntered() {
        ownedThreads.add(Thread.currentThread());
    }

    EventHandler handler(String actionId) {
        return handlers.get(actionId);
    }

    Map<String, ParameterDefinition> parameters(String classId) {
        return catalog.getEffectiveParameters(classId);
    }

    private ProcessTemplate template(BusinessObject object) {
        try {
            ProcessTemplate template = object.templateId() == null ? templates.resolveTemplate(object.classId())
                    : templates.getTemplate(object.templateId());
            if (!catalog.isInstanceOf(object.classId(), template.classId())) {
                throw new WorkflowException(TEMPLATE_ERROR, "Template does not apply to class: " + object.classId());
            }
            rejectReservedAction(template);
            return template;
        } catch (IOException failure) {
            throw persistence(failure);
        } catch (IllegalArgumentException failure) {
            throw new WorkflowException(TEMPLATE_ERROR, failure.getMessage(), failure);
        }
    }

    static BusinessObject object(StoreData store, String objectId) {
        return store.objects().stream().filter(object -> object.id().equals(objectId)).findFirst()
                .orElseThrow(() -> new WorkflowException(NOT_FOUND, "Unknown object: " + objectId));
    }

    static UserDefinition user(StoreData store, String userId) {
        return store.users().stream().filter(user -> user.id().equals(userId)).findFirst()
                .orElseThrow(() -> new WorkflowException(NOT_FOUND, "Unknown user: " + userId));
    }

    static StateDefinition state(ProcessTemplate template, String stateId) {
        return template.states().stream().filter(state -> state.id().equals(stateId)).findFirst()
                .orElseThrow(() -> new WorkflowException(TEMPLATE_ERROR, "Unknown saved state: " + stateId));
    }

    static boolean hasRole(UserDefinition user, Set<String> roles) {
        return user.roles() != null && roles != null && user.roles().stream().anyMatch(roles::contains);
    }

    private static void rejectReservedAction(ProcessTemplate template) {
        if (template.actions().stream().anyMatch(action -> UPDATE_PARAMETER.equals(action.id()))) {
            throw new WorkflowException(TEMPLATE_ERROR, "Template uses reserved action: " + UPDATE_PARAMETER);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new WorkflowException(ENGINE_CLOSED, "Workflow engine is closed");
        }
    }

    private static WorkflowException persistence(IOException failure) {
        return new WorkflowException(PERSISTENCE_ERROR, "Repository operation failed: " + failure.getMessage(), failure);
    }

    private static RuntimeException classify(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof WorkflowException workflowFailure) {
            return workflowFailure;
        }
        WorkflowError code = switch (failure) {
            case NoApplicableTransitionException ignored -> CONDITION_NOT_MET;
            case AmbiguousTransitionException ignored -> TEMPLATE_ERROR;
            case ConcurrentModificationException ignored -> CONCURRENT_MODIFICATION;
            default -> TEMPLATE_ERROR;
        };
        return new WorkflowException(code, failure.getMessage(), failure);
    }

    private static <Value> CompletableFuture<Value> translate(CompletableFuture<Value> future) {
        return future.handle((result, failure) -> {
            if (failure != null) {
                throw classify(failure);
            }
            return result;
        });
    }
}
