package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.RoleDefinition;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.condition.ConditionEvaluator;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.engine.exception.WorkflowError;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.repository.file.FileRepository;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.StateMachine;
import ru.moipis.workflow.statemachine.StateMachineLifecycle;
import ru.moipis.workflow.statemachine.factory.DefaultStateMachineFactory;
import ru.moipis.workflow.statemachine.factory.StateMachineConfiguration;
import ru.moipis.workflow.statemachine.factory.StateMachineFactory;
import ru.moipis.workflow.template.TemplateCatalogService;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static ru.moipis.workflow.engine.exception.WorkflowError.*;

class WorkflowEngineTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);
    private final List<DefaultWorkflowEngine> engines = new ArrayList<>();
    private final List<StateMachine> machines = new CopyOnWriteArrayList<>();
    private FileRepository repository;
    private DefaultWorkflowEngine engine;

    @BeforeEach
    void setup() throws Exception {
        repository = new FileRepository(directory.resolve("store.json"), clock);
        StoreData seed = repository.loadOrInitialize();
        ProcessTemplate template;
        try (var input = getClass().getResourceAsStream("/templates/engine-template.json")) {
            template = new ObjectMapper().readValue(input, ProcessTemplate.class);
        }
        repository.save(new StoreData(1, seed.classes(),
                List.of(new RoleDefinition("creator", "Creator"), new RoleDefinition("operator", "Operator"),
                        new RoleDefinition("manager", "Manager"), new RoleDefinition("viewer", "Viewer")),
                List.of(new UserDefinition("creator", "Creator", Set.of("creator")),
                        new UserDefinition("operator", "Operator", Set.of("viewer", "operator")),
                        new UserDefinition("manager", "Manager", Set.of("manager")),
                        new UserDefinition("viewer", "Viewer", Set.of("viewer"))),
                List.of(template), List.of(), List.of()));
        engine = open(repository);
    }

    @AfterEach
    void close() {
        engines.forEach(DefaultWorkflowEngine::close);
    }

    @Test
    void createsInheritedObjectWithoutCheckingRangesAndWritesOneHistoryEntry() throws Exception {
        BusinessObject object = create(0);
        assertEquals("office_order", object.classId());
        assertEquals("standard-process", object.templateId());
        assertEquals("draft", object.stateId());
        assertEquals(0, object.parameters().get("quantity").intValue());
        var history = engine.getHistory(object.id());
        assertEquals(1, history.size());
        assertEquals("CREATE", history.getFirst().operation());
        assertEquals("creator", history.getFirst().userId());
        assertEquals(clock.instant(), history.getFirst().timestamp());
        assertEquals("draft", history.getFirst().details().get("initialState").asText());
        assertTrue(machines.isEmpty(), "State machines are created lazily");
    }

    @Test
    void creationRejectsUnknownClassTemplateUserRoleParameterAndTypeWithoutWriting() throws Exception {
        error(NOT_FOUND, engine.createObject("creator", "missing", Map.of()));
        error(TEMPLATE_ERROR, engine.createObject("creator", "order", Map.of()));
        error(ACCESS_DENIED, engine.createObject("viewer", "office_order", Map.of()));
        error(UNKNOWN_PARAMETER, engine.createObject("creator", "office_order", Map.of("missing", IntNode.valueOf(1))));
        error(INVALID_PARAMETER_TYPE, engine.createObject("creator", "office_order", Map.of("quantity", TextNode.valueOf("1"))));
        error(NOT_FOUND, engine.createObject("missing", "office_order", Map.of()));
        assertTrue(repository.load().objects().isEmpty());
        assertTrue(repository.load().history().isEmpty());
    }

    @Test
    void actionsCheckRightsConditionsAndCallDynamicHandlerOnlyAfterValidation() throws Exception {
        BusinessObject object = create(0);
        AtomicInteger calls = new AtomicInteger();
        engine.registerHandler("submit", event -> { calls.incrementAndGet(); return TextNode.valueOf("submitted"); });
        assertEquals(List.of("submit", "hold"), engine.getAvailableActions("operator", object.id()));
        assertTrue(engine.getAvailableActions("viewer", object.id()).isEmpty());
        error(ACCESS_DENIED, engine.executeAction("viewer", object.id(), "submit"));
        error(INVALID_ACTION, engine.executeAction("operator", object.id(), "missing"));
        error(CONDITION_NOT_MET, engine.executeAction("operator", object.id(), "submit"));
        assertEquals(0, calls.get());
        result(engine.updateParameter("operator", object.id(), "quantity", IntNode.valueOf(10)));
        var action = result(engine.executeAction("operator", object.id(), "submit"));
        assertEquals("submitted", action.handlerResult().asText());
        assertEquals("review", engine.getObject(object.id()).stateId());
        assertEquals("review", machines.getFirst().getCurrentState().id());
        assertEquals(1, calls.get());
        assertEquals(List.of("CREATE", "UPDATE_PARAMETER", "ACTION"),
                engine.getHistory(object.id()).stream().map(entry -> entry.operation()).toList());
    }

    @Test
    void editingChecksRightsTypeAndPolicyButNotRanges() throws Exception {
        BusinessObject object = create(10);
        error(ACCESS_DENIED, engine.updateParameter("viewer", object.id(), "quantity", IntNode.valueOf(2)));
        error(UNKNOWN_PARAMETER, engine.updateParameter("operator", object.id(), "missing", IntNode.valueOf(2)));
        error(INVALID_PARAMETER_TYPE, engine.updateParameter("operator", object.id(), "quantity", TextNode.valueOf("2")));
        result(engine.updateParameter("operator", object.id(), "quantity", IntNode.valueOf(501)));
        error(CONDITION_NOT_MET, engine.executeAction("operator", object.id(), "submit"));
        result(engine.updateParameter("operator", object.id(), "office_location", TextNode.valueOf("Moscow")));
        result(engine.updateParameter("operator", object.id(), "quantity", IntNode.valueOf(2)));
        result(engine.executeAction("operator", object.id(), "submit"));
        error(ACCESS_DENIED, engine.updateParameter("manager", object.id(), "quantity", IntNode.valueOf(2)));
        assertEquals(5, engine.getHistory(object.id()).size());
        var update = engine.getHistory(object.id()).get(1);
        assertEquals(10, update.details().get("oldValue").intValue());
        assertEquals(501, update.details().get("newValue").intValue());
    }

    @Test
    void queuedActionUsesParametersFromPrecedingUpdate() throws Exception {
        BusinessObject object = create(10);
        var update = engine.updateParameter("operator", object.id(), "quantity", IntNode.valueOf(600));
        var action = engine.executeAction("operator", object.id(), "submit");
        result(update);
        error(CONDITION_NOT_MET, action);
        assertEquals("draft", engine.getObject(object.id()).stateId());
        assertEquals(2, engine.getHistory(object.id()).size());
    }

    @Test
    void finalStateForbidsActionsAndEditing() throws Exception {
        BusinessObject object = create(10);
        result(engine.executeAction("operator", object.id(), "submit"));
        error(ACCESS_DENIED, engine.executeAction("operator", object.id(), "approve"));
        result(engine.executeAction("manager", object.id(), "approve"));
        error(FINAL_STATE, engine.executeAction("manager", object.id(), "approve"));
        error(FINAL_STATE, engine.updateParameter("operator", object.id(), "quantity", IntNode.valueOf(2)));
        assertTrue(engine.getAvailableActions("manager", object.id()).isEmpty());
        assertEquals(3, engine.getHistory(object.id()).size());
    }

    @Test
    void handlerFailureDoesNotCommitAndWorkerAcceptsReplacementHandler() throws Exception {
        BusinessObject object = create(10);
        engine.registerHandler("submit", event -> { throw new IllegalArgumentException("handler failed"); });
        error(HANDLER_ERROR, engine.executeAction("operator", object.id(), "submit"));
        assertEquals("draft", engine.getObject(object.id()).stateId());
        assertEquals(1, engine.getHistory(object.id()).size());
        assertEquals("draft", machines.getFirst().getCurrentState().id());
        engine.registerHandler("submit", event -> null);
        result(engine.executeAction("operator", object.id(), "submit"));
        assertEquals("review", engine.getObject(object.id()).stateId());
    }

    @Test
    void failedSaveLeavesBothMachineAndRepositoryUnchangedAndAllowsRetry() throws Exception {
        BusinessObject object = create(10);
        AtomicBoolean fail = new AtomicBoolean(true);
        FileRepository failing = new FileRepository(directory.resolve("store.json"), clock, (source, target) -> {
            if (fail.get()) { throw new IOException("disk failure"); }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        DefaultWorkflowEngine other = open(failing);
        byte[] before = Files.readAllBytes(directory.resolve("store.json"));
        error(PERSISTENCE_ERROR, other.executeAction("operator", object.id(), "submit"));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("store.json")));
        assertEquals("draft", other.getObject(object.id()).stateId());
        assertEquals(1, other.getHistory(object.id()).size());
        assertEquals("draft", machines.getFirst().getCurrentState().id());
        fail.set(false);
        result(other.executeAction("operator", object.id(), "submit"));
        assertEquals("review", other.getObject(object.id()).stateId());
        assertEquals("review", machines.getFirst().getCurrentState().id());
    }

    @Test
    void restartRestoresPersistedStateRatherThanInitialState() throws Exception {
        BusinessObject object = create(10);
        result(engine.executeAction("operator", object.id(), "submit"));
        engine.close();
        DefaultWorkflowEngine restarted = open(new FileRepository(directory.resolve("store.json"), clock));
        assertEquals(List.of("approve"), restarted.getAvailableActions("manager", object.id()));
        result(restarted.executeAction("manager", object.id(), "approve"));
        assertEquals("done", restarted.getObject(object.id()).stateId());
        assertEquals(3, restarted.getHistory(object.id()).size());
        assertEquals("done", machines.getLast().getCurrentState().id());
    }

    @Test
    void objectsHaveIndependentWorkersAndCloseDrainsAcceptedEvents() throws Exception {
        BusinessObject first = create(10);
        BusinessObject second = create(20);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        engine.registerHandler("hold", event -> {
            if (event.context().parameters().get("quantity").intValue() == 10) {
                entered.countDown();
                await(release);
            }
            return null;
        });
        var blocked = engine.executeAction("operator", first.id(), "hold");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var queued = engine.updateParameter("operator", first.id(), "quantity", IntNode.valueOf(30));
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                result(engine.executeAction("operator", second.id(), "hold"));
                var closing = executor.submit(engine::close);
                release.countDown();
                result(blocked);
                result(queued);
                closing.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertEquals(30, engine.getObject(first.id()).parameters().get("quantity").intValue());
        error(ENGINE_CLOSED, engine.executeAction("operator", first.id(), "hold"));
        error(ENGINE_CLOSED, engine.createObject("creator", "office_order", Map.of()));
        assertEquals(2, machines.size());
        assertTrue(machines.stream().allMatch(machine -> machine.getLifecycle() == StateMachineLifecycle.STOPPED));
    }

    @Test
    void ambiguousTemplateRejectsOperationBeforeCallingHandler() throws Exception {
        var store = repository.load();
        var template = store.templates().getFirst();
        var transitions = new ArrayList<>(template.transitions());
        transitions.add(template.transitions().getFirst());
        var ambiguous = new ProcessTemplate(template.id(), template.title(), template.classId(), template.createRoles(),
                template.actions(), template.predicates(), template.states(), transitions);
        repository.save(new StoreData(1, store.classes(), store.roles(), store.users(), List.of(ambiguous), store.objects(), store.history()));
        BusinessObject object = create(10);
        AtomicInteger calls = new AtomicInteger();
        engine.registerHandler("submit", event -> { calls.incrementAndGet(); return null; });
        error(TEMPLATE_ERROR, engine.executeAction("operator", object.id(), "submit"));
        assertEquals(0, calls.get());
        assertEquals("draft", engine.getObject(object.id()).stateId());
        assertEquals(1, engine.getHistory(object.id()).size());
    }

    @Test
    void validatesExactNumericTypesAndAcceptsNullAndOutOfRangeValues() throws Exception {
        Map<String, JsonNode> parameters = Map.of(
                "quantity", BigIntegerNode.valueOf(new BigInteger("9999999999999999999999999999999")),
                "total_amount", DecimalNode.valueOf(new BigDecimal("-12345678901234567890.123456789")),
                "product_name", TextNode.valueOf("Product"), "supplier_name", NullNode.instance);
        BusinessObject object = result(engine.createObject("creator", "office_order", parameters));
        assertEquals(parameters, engine.getObject(object.id()).parameters());
        error(INVALID_PARAMETER_TYPE, engine.updateParameter("operator", object.id(), "quantity",
                DecimalNode.valueOf(new BigDecimal("1.01"))));
        error(INVALID_PARAMETER_TYPE, engine.updateParameter("operator", object.id(), "product_name", IntNode.valueOf(1)));
        error(INVALID_PARAMETER_TYPE, engine.updateParameter("operator", object.id(), "total_amount", TextNode.valueOf("1")));
        result(engine.updateParameter("operator", object.id(), "quantity", NullNode.instance));
        error(CONDITION_NOT_MET, engine.executeAction("operator", object.id(), "submit"));
        assertEquals(2, engine.getHistory(object.id()).size());
    }

    @Test
    void ownCopiedTemplateControlsCreationAndExistingObjectsKeepTheirTemplate() throws Exception {
        BusinessObject inherited = create(10);
        CatalogService catalog = new CatalogService(repository.load().classes());
        TemplateCatalogService templates = new TemplateCatalogService(catalog, repository);
        ProcessTemplate copied = templates.copyTemplate("standard-process", "office_order", "office-process");
        var own = new ProcessTemplate(copied.id(), copied.title(), copied.classId(), Set.of("manager"), copied.actions(),
                copied.predicates(), copied.states(), copied.transitions());
        repository.update(store -> new StoreData(1, store.classes(), store.roles(), store.users(),
                List.of(store.templates().getFirst(), own), store.objects(), store.history()));
        error(ACCESS_DENIED, engine.createObject("creator", "office_order", Map.of()));
        BusinessObject direct = result(engine.createObject("manager", "office_order", Map.of()));
        BusinessObject sibling = result(engine.createObject("creator", "consumables_order", Map.of()));
        assertEquals("office-process", direct.templateId());
        assertEquals("standard-process", sibling.templateId());
        result(engine.executeAction("operator", inherited.id(), "submit"));
        assertEquals("standard-process", engine.getObject(inherited.id()).templateId());
    }

    @Test
    void selectiveLegacyEditingPolicyRemainsSupported() throws Exception {
        repository.update(store -> {
            ProcessTemplate template = store.templates().getFirst();
            List<StateDefinition> states = template.states().stream().map(state -> new StateDefinition(state.id(),
                    state.title(), state.initial(), state.terminal(), state.roles(), Set.of("quantity"))).toList();
            var changed = new ProcessTemplate(template.id(), template.title(), template.classId(), template.createRoles(),
                    template.actions(), template.predicates(), states, template.transitions());
            return new StoreData(1, store.classes(), store.roles(), store.users(), List.of(changed), store.objects(), store.history());
        });
        BusinessObject object = create(10);
        result(engine.updateParameter("operator", object.id(), "quantity", IntNode.valueOf(20)));
        error(ACCESS_DENIED, engine.updateParameter("operator", object.id(), "product_name", TextNode.valueOf("Product")));
        assertEquals(2, engine.getHistory(object.id()).size());
    }

    @Test
    void handlerRegistrationDuringExecutionAppliesToFollowingMessage() throws Exception {
        BusinessObject object = create(10);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        engine.registerHandler("hold", event -> { entered.countDown(); await(release); return TextNode.valueOf("old"); });
        var first = engine.executeAction("operator", object.id(), "hold");
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = engine.executeAction("operator", object.id(), "hold");
            engine.registerHandler("hold", event -> TextNode.valueOf("new"));
            release.countDown();
            assertEquals("old", result(first).handlerResult().asText());
            assertEquals("new", result(second).handlerResult().asText());
            assertEquals(3, engine.getHistory(object.id()).size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedCreateAndUpdateLeaveNoPartialObjectOrHistory() throws Exception {
        BusinessObject object = create(10);
        FileRepository failing = new FileRepository(directory.resolve("store.json"), clock,
                (source, target) -> { throw new IOException("disk failure"); });
        DefaultWorkflowEngine other = open(failing);
        var before = repository.load();
        error(PERSISTENCE_ERROR, other.createObject("creator", "office_order", Map.of()));
        error(PERSISTENCE_ERROR, other.updateParameter("operator", object.id(), "quantity", IntNode.valueOf(99)));
        assertEquals(before, repository.load());
        assertEquals("draft", machines.getFirst().getCurrentState().id());
    }

    @Test
    void parallelObjectsDoNotLoseUpdatesAndEveryOperationHasOneHistoryEntry() throws Exception {
        List<BusinessObject> objects = new ArrayList<>();
        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            objects.add(create(10));
        }
        for (BusinessObject object : objects) {
            pending.add(engine.updateParameter("operator", object.id(), "quantity", IntNode.valueOf(20)));
            pending.add(engine.executeAction("operator", object.id(), "submit"));
            pending.add(engine.executeAction("manager", object.id(), "approve"));
        }
        result(CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)));
        assertEquals(6, repository.load().objects().size());
        assertEquals(24, repository.load().history().size());
        for (BusinessObject object : objects) {
            assertEquals("done", engine.getObject(object.id()).stateId());
            assertEquals(20, engine.getObject(object.id()).parameters().get("quantity").intValue());
            assertEquals(4, engine.getHistory(object.id()).size());
        }
    }

    @Test
    void changedSavedStateIsReportedRatherThanOverwrittenByStaleMachine() throws Exception {
        BusinessObject object = create(10);
        result(engine.executeAction("operator", object.id(), "hold"));
        DefaultWorkflowEngine other = open(repository);
        result(other.executeAction("operator", object.id(), "submit"));
        error(CONCURRENT_MODIFICATION, engine.executeAction("operator", object.id(), "submit"));
        assertEquals("review", engine.getObject(object.id()).stateId());
        assertEquals(3, engine.getHistory(object.id()).size());
    }

    @Test
    void unknownObjectsAndUsersHaveExplicitErrors() throws Exception {
        error(NOT_FOUND, engine.executeAction("operator", "missing", "hold"));
        error(NOT_FOUND, engine.updateParameter("operator", "missing", "quantity", IntNode.valueOf(1)));
        assertEquals(NOT_FOUND, assertThrows(WorkflowException.class, () -> engine.getObject("missing")).code());
        assertEquals(NOT_FOUND, assertThrows(WorkflowException.class, () -> engine.getHistory("missing")).code());
        BusinessObject object = create(10);
        error(NOT_FOUND, engine.executeAction("missing", object.id(), "hold"));
        assertEquals(NOT_FOUND, assertThrows(WorkflowException.class,
                () -> engine.getAvailableActions("missing", object.id())).code());
        error(INVALID_ACTION, engine.executeAction("operator", object.id(), "update_parameter"));
        assertEquals(1, engine.getHistory(object.id()).size());
    }

    @Test
    void closingFromHandlerIsRejectedWithoutDeadlock() throws Exception {
        BusinessObject object = create(10);
        engine.registerHandler("hold", event -> { engine.close(); return null; });
        error(HANDLER_ERROR, engine.executeAction("operator", object.id(), "hold"));
        engine.registerHandler("hold", event -> null);
        result(engine.executeAction("operator", object.id(), "hold"));
        assertEquals(2, engine.getHistory(object.id()).size());
    }

    @Test
    void defaultWiringInitializesRepositoryAndExecutesProcess() throws Exception {
        try (DefaultWorkflowEngine wired = new DefaultWorkflowEngine(repository)) {
            BusinessObject object = result(wired.createObject("creator", "office_order", Map.of("quantity", IntNode.valueOf(1))));
            result(wired.executeAction("operator", object.id(), "submit"));
            assertEquals("review", wired.getObject(object.id()).stateId());
        }
    }

    @Test
    void closeDrainsAcceptedCreationsAndRejectsFurtherCreations() throws Exception {
        List<CompletableFuture<BusinessObject>> accepted = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            accepted.add(engine.createObject("creator", "office_order", Map.of()));
        }
        engine.close();
        for (var future : accepted) {
            assertNotNull(result(future));
        }
        assertEquals(12, repository.load().objects().size());
        assertEquals(12, repository.load().history().size());
        error(ENGINE_CLOSED, engine.createObject("creator", "office_order", Map.of()));
        engine.close();
        assertTrue(machines.isEmpty());
    }

    private DefaultWorkflowEngine open(FileRepository source) throws IOException {
        CatalogService catalog = new CatalogService(source.loadOrInitialize().classes());
        DefaultWorkflowEngine opened = new DefaultWorkflowEngine(catalog, new TemplateCatalogService(catalog, source),
                new DefaultConditionEvaluator(catalog), source, new RecordingFactory());
        engines.add(opened);
        return opened;
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

    private BusinessObject create(int quantity) throws Exception {
        return result(engine.createObject("creator", "office_order", Map.of("quantity", IntNode.valueOf(quantity))));
    }

    private static <Value> Value result(CompletableFuture<Value> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void error(WorkflowError code, CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertEquals(code, assertInstanceOf(WorkflowException.class, failure.getCause()).code());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Timed out waiting for handler release"); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
