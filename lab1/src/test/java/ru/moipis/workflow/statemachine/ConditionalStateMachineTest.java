package ru.moipis.workflow.statemachine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.IntNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.condition.ConditionEvaluator;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.ConditionLiteral;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.statemachine.exception.AmbiguousTransitionException;
import ru.moipis.workflow.statemachine.exception.NoApplicableTransitionException;
import ru.moipis.workflow.statemachine.factory.DefaultStateMachineFactory;
import ru.moipis.workflow.template.TemplateCatalogService;
import ru.moipis.workflow.template.model.ActionDefinition;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalStateMachineTest {

    private final List<StateMachine> machines = new ArrayList<>();
    private StoreData seed;
    private CatalogService classes;
    private ConditionEvaluator evaluator;

    @BeforeEach
    void setUp() throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/seed/initial-data.json"))) {
            seed = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(input, StoreData.class);
        }
        classes = new CatalogService(seed.classes());
        evaluator = new DefaultConditionEvaluator(classes);
    }

    @AfterEach
    void stopMachines() {
        machines.forEach(StateMachine::stop);
    }

    @Test
    void inheritedTemplateUsesTheActualObjectClassAndItsOverriddenRange() throws Exception {
        ProcessTemplate template = template(List.of(rangePredicate()), List.of(
                transition("first", condition("range", false)), transition("second", condition("range", true))));
        StateMachine office = machine(template, "office_order");
        StateMachine consumables = machine(template, "consumables_order");

        assertEquals("second", result(office.send(event("office_order", 501))).currentStateId());
        assertEquals("first", result(consumables.send(event("consumables_order", 501))).currentStateId());
    }

    @Test
    void classPredicateCanSelectAConcreteSubclass() throws Exception {
        PredicateDefinition predicate = new PredicateDefinition("office", "Office", null, null, null, "office_order");
        ProcessTemplate template = template(List.of(predicate), List.of(
                transition("first", condition("office", false)), transition("second", condition("office", true))));

        assertEquals("first", result(machine(template, "office_order").send(event("office_order", 10))).currentStateId());
        assertEquals("second", result(machine(template, "consumables_order").send(event("consumables_order", 10))).currentStateId());
    }

    @Test
    void rejectsZeroMatchesBeforeHandlerAndContinuesAfterRefusal() throws Exception {
        StateMachine machine = machine(template(List.of(rangePredicate()), List.of(
                transition("first", condition("range", false)))), "office_order");
        AtomicInteger handlers = new AtomicInteger();
        machine.registerHandler("start", "choose", event -> { handlers.incrementAndGet(); return null; });

        assertInstanceOf(NoApplicableTransitionException.class, failure(machine.send(event("office_order", 501))));
        assertEquals("start", machine.currentStateId());
        assertEquals(0, handlers.get());
        assertEquals("first", result(machine.send(event("office_order", 10))).currentStateId());
        assertEquals(1, handlers.get());
    }

    @Test
    void dedicatedAmbiguousTemplateRejectsTwoTrueTransitionsInsteadOfChoosingTheFirst() throws Exception {
        ProcessTemplate template;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/templates/ambiguous-template.json"))) {
            template = new ObjectMapper().readValue(input, ProcessTemplate.class);
        }
        StateMachine machine = machine(template, "office_order");
        AtomicInteger handlers = new AtomicInteger();
        machine.registerHandler("start", "choose", event -> { handlers.incrementAndGet(); return null; });

        assertInstanceOf(AmbiguousTransitionException.class, failure(machine.send(event("office_order", 10))));
        assertEquals("start", machine.currentStateId());
        assertEquals(0, handlers.get());
        assertEquals("first", result(machine.send(event("office_order", 2000))).currentStateId());
        assertEquals(1, handlers.get());
    }

    @Test
    void unconditionalTransitionDoesNotRequireAnObjectContext() throws Exception {
        StateMachine machine = machine(template(List.of(), List.of(transition("first", null))), "office_order");

        assertEquals("first", result(machine.send(new Event("choose", null, null))).currentStateId());
    }

    @Test
    void unconditionalTransitionDoesNotTakePriorityOverATrueConditionalOne() throws Exception {
        ProcessTemplate template = template(List.of(rangePredicate()), List.of(
                transition("first", null), transition("second", condition("range", false))));
        StateMachine machine = machine(template, "office_order");

        assertInstanceOf(AmbiguousTransitionException.class, failure(machine.send(event("office_order", 10))));
        assertEquals("first", result(machine.send(event("office_order", 501))).currentStateId());
    }

    @Test
    void conditionalTransitionRequiresObjectContextAndLeavesWorkerUsable() throws Exception {
        StateMachine machine = machine(template(List.of(rangePredicate()), List.of(
                transition("first", condition("range", false)))), "office_order");

        assertInstanceOf(IllegalArgumentException.class, failure(machine.send(new Event("choose", null, null))));
        assertEquals("first", result(machine.send(event("office_order", 10))).currentStateId());
    }

    @Test
    void queuedConditionUsesTheAcceptedObjectSnapshot() throws Exception {
        StateMachine machine = machine(template(List.of(rangePredicate()), List.of(
                transition("first", condition("range", false)), transition("second", condition("range", true)))), "office_order");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        machine.registerHandler("start", "block", event -> { entered.countDown(); await(release); return null; });
        CompletableFuture<EventResult> blocker = machine.send(new Event("block", null, null));
        Map<String, JsonNode> values = new HashMap<>();
        values.put("quantity", IntNode.valueOf(10));
        BusinessObject object = new BusinessObject("object", "office_order", "start", values, "template");
        CompletableFuture<EventResult> queued;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queued = machine.send(new Event("choose", null, null, ConditionContext.from(object)));
            values.put("quantity", IntNode.valueOf(501));
        } finally {
            release.countDown();
        }

        result(blocker);
        assertEquals("first", result(queued).currentStateId());
    }

    @Test
    void evaluatesEveryCandidateEvenAfterTheFirstTwoMatch() throws Exception {
        AtomicInteger evaluated = new AtomicInteger();
        StateMachine machine = started(new DefaultStateMachine("start", (condition, event) -> {
            evaluated.incrementAndGet();
            return true;
        }));
        machine.registerState("first");
        machine.registerState("second");
        machine.addTransition(transition("first", condition("A", false)));
        machine.addTransition(transition("second", condition("B", false)));
        machine.addTransition(transition("second", condition("C", false)));

        assertInstanceOf(AmbiguousTransitionException.class, failure(machine.send(new Event("choose", null, null))));
        assertEquals(3, evaluated.get());
    }

    @Test
    void configurationIsNotLockedDuringEvaluationAndNewTransitionsAffectOnlyLaterMessages() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StateMachine machine = started(new DefaultStateMachine("start", (condition, event) -> {
            entered.countDown();
            await(release);
            return true;
        }));
        machine.addTransition(new TransitionDefinition("start", "choose", "start", condition("A", false)));
        CompletableFuture<EventResult> running = machine.send(new Event("choose", null, null));
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                executor.submit(() -> {
                    machine.registerState("second");
                    machine.registerTransition("start", "choose", "second");
                }).get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertEquals("start", result(running).currentStateId());
        assertInstanceOf(AmbiguousTransitionException.class, failure(machine.send(new Event("choose", null, null))));
    }

    @Test
    void templateCopyAndJsonRoundTripPreserveClassPredicates() throws Exception {
        PredicateDefinition predicate = new PredicateDefinition("class", "Class", null, null, null, "order");
        MemoryRepository repository = repository(template(List.of(predicate), List.of(transition("first", condition("class", false)))));
        TemplateCatalogService templates = new TemplateCatalogService(classes, repository);
        templates.copyTemplate("template", "office_order", "office");

        PredicateDefinition copied = templates.getTemplate("office").predicates().getFirst();
        assertEquals("order", copied.classId());
        ObjectMapper mapper = new ObjectMapper();
        ProcessTemplate restored = mapper.readValue(mapper.writeValueAsBytes(templates.getTemplate("office")), ProcessTemplate.class);
        assertEquals("order", restored.predicates().getFirst().classId());
    }

    @Test
    void templateCatalogRejectsUnknownClassPredicateReferences() {
        PredicateDefinition predicate = new PredicateDefinition("class", "Class", null, null, null, "missing");

        assertThrows(IllegalArgumentException.class, () -> new TemplateCatalogService(classes,
                repository(template(List.of(predicate), List.of(transition("first", condition("class", false)))))));
    }

    private StateMachine machine(ProcessTemplate template, String classId) throws IOException {
        TemplateCatalogService templates = new TemplateCatalogService(classes, repository(template));
        return started(new DefaultStateMachineFactory().create(templates.prepareStateMachineConfiguration(classId), evaluator));
    }

    private StateMachine started(StateMachine machine) {
        machines.add(machine);
        machine.start();
        return machine;
    }

    private MemoryRepository repository(ProcessTemplate template) {
        return new MemoryRepository(new StoreData(1, seed.classes(), List.of(), List.of(), List.of(template), List.of(), List.of()));
    }

    private static ProcessTemplate template(List<PredicateDefinition> predicates, List<TransitionDefinition> transitions) {
        return new ProcessTemplate("template", "Template", "standard_order", Set.of(),
                List.of(new ActionDefinition("choose", "Choose")), predicates,
                List.of(state("start", true), state("first", false), state("second", false)), transitions);
    }

    private static StateDefinition state(String id, boolean initial) {
        return new StateDefinition(id, id, initial, !initial, Set.of(), Set.of());
    }

    private static TransitionDefinition transition(String to, DnfCondition condition) {
        return new TransitionDefinition("start", "choose", to, condition);
    }

    private static PredicateDefinition rangePredicate() {
        return new PredicateDefinition("range", "Range", "quantity", "in_range", null);
    }

    private static DnfCondition condition(String predicate, boolean negated) {
        return new DnfCondition(List.of(List.of(new ConditionLiteral(predicate, negated))));
    }

    private static Event event(String classId, int quantity) {
        BusinessObject object = new BusinessObject("object", classId, "start", Map.of("quantity", IntNode.valueOf(quantity)), "template");
        return new Event("choose", null, null, ConditionContext.from(object));
    }

    private static EventResult result(CompletableFuture<EventResult> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletableFuture<EventResult> future) {
        return assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
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

    private static final class MemoryRepository implements Repository {
        @Override
        public synchronized StoreData update(UnaryOperator<StoreData> change) {
            StoreData prepared = change.apply(load());
            save(prepared);
            return prepared;
        }

        @Override
        public HistoryEntry commit(ObjectChange change) {
            throw new UnsupportedOperationException("Object operations are not used in transition tests");
        }

        private StoreData data;

        private MemoryRepository(StoreData data) {
            this.data = data;
        }

        @Override
        public StoreData loadOrInitialize() {
            return data;
        }

        @Override
        public StoreData load() {
            return data;
        }

        @Override
        public void save(StoreData data) {
            this.data = data;
        }
    }
}
