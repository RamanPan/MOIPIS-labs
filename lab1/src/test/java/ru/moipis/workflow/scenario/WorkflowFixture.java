package ru.moipis.workflow.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.condition.ConditionEvaluator;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.engine.DefaultWorkflowEngine;
import ru.moipis.workflow.engine.WorkflowEngine;
import ru.moipis.workflow.engine.exception.WorkflowError;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.file.FileRepository;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.StateMachine;
import ru.moipis.workflow.statemachine.StateMachineLifecycle;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.statemachine.factory.DefaultStateMachineFactory;
import ru.moipis.workflow.statemachine.factory.StateMachineConfiguration;
import ru.moipis.workflow.statemachine.factory.StateMachineFactory;
import ru.moipis.workflow.template.TemplateCatalogService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowFixture implements AutoCloseable {
    static final String TEMPLATE_ID = "standard_order_supply";
    static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    static final List<String> MAIN_PATH = List.of("complete", "approve", "approve", "approve", "approve", "execute", "complete");
    static final List<String> MAIN_STATES = List.of("registration", "composition_approval", "price_approval",
            "delivery_approval", "payment_approval", "execution_decision", "execution", "completed");
    static final List<String> MAIN_USERS = List.of("user_manager", "user_manager", "user_procurement", "user_logistics",
            "user_finance", "user_head", "user_executor");

    final Path path;
    final FileRepository repository;
    final StoreData seed;
    final AtomicBoolean failWrites = new AtomicBoolean();
    final List<StateMachine> machines = new CopyOnWriteArrayList<>();
    final Map<String, StateMachine> objectMachines = new HashMap<>();
    volatile WriteGate writeGate;
    CatalogService catalog;
    TemplateCatalogService templates;
    WorkflowEngine engine;

    WorkflowFixture(Path directory) throws IOException {
        path = directory.resolve("store.json");
        repository = new FileRepository(path, Clock.fixed(NOW, ZoneOffset.UTC), (source, target) -> {
            WriteGate gate = writeGate;
            if (gate != null) {
                gate.entered.countDown();
                await(gate.release);
            }
            if (failWrites.get()) {
                throw new IOException("Injected atomic replacement failure");
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        seed = repository.loadOrInitialize();
        repository.save(new StoreData(seed.schemaVersion(), seed.classes(), seed.roles(), seed.users(), seed.templates(), List.of(), List.of()));
        open();
    }

    void restart() throws IOException {
        engine.close();
        assertTrue(machines.stream().allMatch(machine -> machine.getLifecycle() == StateMachineLifecycle.STOPPED));
        objectMachines.clear();
        open();
    }

    private void open() throws IOException {
        catalog = new CatalogService(repository.load().classes());
        templates = new TemplateCatalogService(catalog, repository);
        engine = new DefaultWorkflowEngine(catalog, templates, new DefaultConditionEvaluator(catalog), repository, new RecordingFactory());
    }

    Map<String, JsonNode> parameters(String classId) {
        String sourceId = classId.equals("office_order") ? "demo-office-valid" : "demo-standard-valid";
        Map<String, JsonNode> values = new LinkedHashMap<>();
        seed.objects().stream().filter(object -> object.id().equals(sourceId)).findFirst().orElseThrow()
                .parameters().forEach((id, value) -> values.put(id, value.deepCopy()));
        return values;
    }

    BusinessObject create(String userId, String classId, Map<String, JsonNode> parameters) throws Exception {
        StoreData before = repository.load();
        BusinessObject object = result(engine.createObject(userId, classId, parameters));
        assertEquals(classId, object.classId());
        assertEquals(parameters, object.parameters());
        assertEquals(templates.resolveTemplate(classId).id(), object.templateId());
        assertEquals(templates.getTemplate(object.templateId()).states().stream().filter(state -> state.initial())
                .findFirst().orElseThrow().id(), object.stateId());
        StoreData after = repository.load();
        HistoryEntry entry = audit(before, after, object.id(), userId, "CREATE");
        assertEquals(new ObjectMapper().createObjectNode().put("initialState", object.stateId())
                .set("parameters", new ObjectMapper().valueToTree(object.parameters())), entry.details());
        onlyObjectChanged(before, after, object);
        return object;
    }

    BusinessObject create(String classId) throws Exception {
        return create("user_manager", classId, parameters(classId));
    }

    BusinessObject atState(String stateId) throws Exception {
        BusinessObject object = create("standard_order");
        moveTo(object.id(), stateId);
        return engine.getObject(object.id());
    }

    void moveTo(String objectId, String stateId) throws Exception {
        if (stateId.equals("postponed_review")) {
            action("user_manager", objectId, "complete", "composition_approval");
            action("user_manager", objectId, "postpone", stateId);
            return;
        }
        int steps = stateId.equals("postponed_execution") ? 5 : MAIN_STATES.indexOf(stateId);
        assertTrue(steps >= 0, "Unknown target test state: " + stateId);
        for (int index = 0; index < steps; index++) {
            action(MAIN_USERS.get(index), objectId, MAIN_PATH.get(index), MAIN_STATES.get(index + 1));
        }
        if (stateId.equals("postponed_execution")) {
            action("user_head", objectId, "postpone_review", stateId);
        }
    }

    EventResult action(String userId, String objectId, String actionId, String target) throws Exception {
        StoreData before = repository.load();
        EventResult result = result(sendAction(userId, objectId, actionId));
        verifyAction(before, userId, objectId, actionId, target, result);
        return result;
    }

    void verifyAction(StoreData before, String userId, String objectId, String actionId, String target, EventResult result) throws IOException {
        BusinessObject previous = object(before, objectId);
        assertEquals(previous.stateId(), result.previousStateId());
        assertEquals(target, result.currentStateId());
        BusinessObject expected = new BusinessObject(previous.id(), previous.classId(), target, previous.parameters(), previous.templateId());
        StoreData after = repository.load();
        HistoryEntry entry = audit(before, after, objectId, userId, "ACTION");
        assertEquals(new ObjectMapper().createObjectNode().put("actionId", actionId)
                .put("oldState", previous.stateId()).put("newState", target), entry.details());
        onlyObjectChanged(before, after, expected);
    }

    EventResult update(String userId, String objectId, String parameterId, JsonNode value) throws Exception {
        StoreData before = repository.load();
        EventResult result = result(sendUpdate(userId, objectId, parameterId, value));
        BusinessObject previous = object(before, objectId);
        Map<String, JsonNode> parameters = new LinkedHashMap<>(previous.parameters());
        JsonNode next = value == null ? NullNode.instance : value;
        parameters.put(parameterId, next);
        assertEquals(previous.stateId(), result.previousStateId());
        assertEquals(previous.stateId(), result.currentStateId());
        StoreData after = repository.load();
        HistoryEntry entry = audit(before, after, objectId, userId, "UPDATE_PARAMETER");
        var details = new ObjectMapper().createObjectNode().put("parameterId", parameterId);
        details.set("oldValue", previous.parameters().getOrDefault(parameterId, NullNode.instance));
        details.set("newValue", next);
        assertEquals(details, entry.details());
        onlyObjectChanged(before, after, new BusinessObject(previous.id(), previous.classId(), previous.stateId(), parameters, previous.templateId()));
        return result;
    }

    CompletableFuture<EventResult> sendAction(String userId, String objectId, String actionId) {
        return bind(objectId, () -> engine.executeAction(userId, objectId, actionId));
    }

    CompletableFuture<EventResult> sendUpdate(String userId, String objectId, String parameterId, JsonNode value) {
        return bind(objectId, () -> engine.updateParameter(userId, objectId, parameterId, value));
    }

    private CompletableFuture<EventResult> bind(String objectId, Supplier<CompletableFuture<EventResult>> operation) {
        int count = machines.size();
        CompletableFuture<EventResult> future = operation.get();
        if (machines.size() > count) {
            objectMachines.put(objectId, machines.getLast());
        }
        return future;
    }

    WorkflowException reject(WorkflowError code, Supplier<? extends CompletableFuture<?>> operation) throws Exception {
        StoreData before = repository.load();
        byte[] bytes = Files.readAllBytes(path);
        WorkflowException failure = failure(code, operation.get());
        assertArrayEquals(bytes, Files.readAllBytes(path));
        unchanged(before);
        return failure;
    }

    void unchanged(StoreData before) throws IOException {
        assertEquals(before, repository.load());
        for (BusinessObject object : before.objects()) {
            assertEquals(object, engine.getObject(object.id()));
            assertEquals(before.history().stream().filter(entry -> entry.objectId().equals(object.id())).toList(), engine.getHistory(object.id()));
        }
        machinesMatch(before);
    }

    void machinesMatch(StoreData store) {
        objectMachines.forEach((id, machine) -> assertEquals(object(store, id).stateId(), machine.getCurrentState().id(), id));
    }

    private HistoryEntry audit(StoreData before, StoreData after, String objectId, String userId, String operation) {
        assertEquals(before.history().size() + 1, after.history().size());
        assertEquals(before.history(), after.history().subList(0, before.history().size()));
        HistoryEntry entry = after.history().getLast();
        assertNotNull(entry.id());
        assertFalse(entry.id().isBlank());
        assertTrue(before.history().stream().noneMatch(existing -> existing.id().equals(entry.id())));
        assertEquals(objectId, entry.objectId());
        assertEquals(userId, entry.userId());
        assertEquals(operation, entry.operation());
        assertEquals(NOW, entry.timestamp());
        return entry;
    }

    private void onlyObjectChanged(StoreData before, StoreData after, BusinessObject expected) {
        List<BusinessObject> objects = new ArrayList<>(before.objects());
        int index = -1;
        for (int candidate = 0; candidate < objects.size(); candidate++) {
            if (objects.get(candidate).id().equals(expected.id())) {
                index = candidate;
                break;
            }
        }
        if (index < 0) {
            objects.add(expected);
        } else {
            objects.set(index, expected);
        }
        assertEquals(new StoreData(before.schemaVersion(), before.classes(), before.roles(), before.users(), before.templates(),
                objects, after.history()), after);
        assertEquals(expected, engine.getObject(expected.id()));
        assertEquals(after.history().stream().filter(entry -> entry.objectId().equals(expected.id())).toList(), engine.getHistory(expected.id()));
        machinesMatch(after);
    }

    static BusinessObject object(StoreData store, String id) {
        return store.objects().stream().filter(object -> object.id().equals(id)).findFirst().orElseThrow();
    }

    static <Value> Value result(CompletableFuture<Value> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    static WorkflowException failure(WorkflowError code, CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result(future));
        WorkflowException cause = assertInstanceOf(WorkflowException.class, failure.getCause());
        assertEquals(code, cause.code());
        assertNotNull(cause.getMessage());
        assertFalse(cause.getMessage().isBlank());
        return cause;
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test coordination");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Override
    public void close() {
        if (writeGate != null) {
            writeGate.release.countDown();
        }
        engine.close();
        assertTrue(machines.stream().allMatch(machine -> machine.getLifecycle() == StateMachineLifecycle.STOPPED));
    }

    static final class WriteGate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private final class RecordingFactory implements StateMachineFactory {
        private final StateMachineFactory delegate = new DefaultStateMachineFactory();

        @Override
        public StateMachine create(String initialStateId) {
            StateMachine machine = delegate.create(initialStateId);
            machines.add(machine);
            return machine;
        }

        @Override
        public StateMachine create(StateMachineConfiguration configuration, ConditionEvaluator evaluator) {
            StateMachine machine = delegate.create(configuration, evaluator);
            machines.add(machine);
            return machine;
        }
    }
}
