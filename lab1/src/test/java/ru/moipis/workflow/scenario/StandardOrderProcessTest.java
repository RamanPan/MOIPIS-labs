package ru.moipis.workflow.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.condition.ConditionEvaluator;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;
import ru.moipis.workflow.engine.DefaultWorkflowEngine;
import ru.moipis.workflow.engine.WorkflowEngine;
import ru.moipis.workflow.engine.exception.WorkflowError;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.repository.file.FileRepository;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.template.TemplateCatalogService;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class StandardOrderProcessTest {
    private static final String TEMPLATE_ID = "standard_order_supply";
    private static final String STANDARD = "demo-standard-valid";
    private static final String OFFICE = "demo-office-valid";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);
    private static final List<String> MAIN_PATH = List.of("complete", "approve", "approve", "approve", "approve", "execute", "complete");
    private static final List<String> MAIN_USERS = List.of("user_manager", "user_manager", "user_procurement", "user_logistics",
            "user_finance", "user_head", "user_executor");

    @TempDir Path directory;
    private FileRepository repository;
    private StoreData seed;
    private CatalogService catalog;
    private TemplateCatalogService templates;
    private WorkflowEngine engine;

    @BeforeEach
    void setup() throws Exception {
        repository = new FileRepository(directory.resolve("store.json"), CLOCK);
        seed = repository.loadOrInitialize();
        catalog = new CatalogService(seed.classes());
        templates = new TemplateCatalogService(catalog, repository);
        engine = new DefaultWorkflowEngine(repository);
    }

    @AfterEach
    void close() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void loadsExactlyTenStatesAndTheNineteenSpecifiedTransitions() throws Exception {
        assertEquals(1, seed.templates().size());
        ProcessTemplate template = template();
        assertEquals("standard_order", template.classId());
        Set<String> states = Set.of("registration", "composition_approval", "price_approval", "delivery_approval",
                "payment_approval", "postponed_review", "execution_decision", "execution", "postponed_execution", "completed");
        assertEquals(10, template.states().size());
        assertEquals(states, template.states().stream().map(StateDefinition::id).collect(Collectors.toSet()));
        assertEquals(List.of("registration"), template.states().stream().filter(StateDefinition::initial).map(StateDefinition::id).toList());
        assertEquals(List.of("completed"), template.states().stream().filter(StateDefinition::terminal).map(StateDefinition::id).toList());
        Set<String> expected = Set.of(
                "registration/complete/composition_approval", "composition_approval/approve/price_approval",
                "composition_approval/postpone/postponed_review", "price_approval/approve/delivery_approval",
                "price_approval/correction/composition_approval", "price_approval/postpone/postponed_review",
                "delivery_approval/approve/payment_approval", "delivery_approval/correction/composition_approval",
                "delivery_approval/postpone/postponed_review", "payment_approval/approve/execution_decision",
                "payment_approval/correction/composition_approval", "payment_approval/postpone/postponed_review",
                "postponed_review/resume_review/composition_approval", "execution_decision/execute/execution",
                "execution_decision/return_to_approval/composition_approval", "execution_decision/postpone_review/postponed_execution",
                "execution/complete/completed", "execution/postpone_execution/postponed_execution",
                "postponed_execution/reconsider/execution_decision");
        assertEquals(19, template.transitions().size());
        assertEquals(expected, template.transitions().stream()
                .map(transition -> transition.from() + "/" + transition.action() + "/" + transition.to()).collect(Collectors.toSet()));
        assertEquals(Set.of("complete", "approve", "postpone", "correction", "resume_review", "execute",
                "return_to_approval", "postpone_review", "postpone_execution", "reconsider"),
                template.actions().stream().map(action -> action.id()).collect(Collectors.toSet()));
    }

    @Test
    void rolesUsersAndEditingPermissionsMatchTheProcess() throws Exception {
        Set<String> roles = Set.of("order_manager", "procurement_specialist", "logistics_specialist", "finance_specialist",
                "procurement_head", "executor");
        assertEquals(roles, seed.roles().stream().map(role -> role.id()).collect(Collectors.toSet()));
        for (String role : roles) {
            assertTrue(seed.users().stream().anyMatch(user -> user.roles().equals(Set.of(role))), role);
        }
        assertTrue(seed.users().stream().anyMatch(user -> user.roles().size() > 1));
        assertTrue(seed.users().stream().allMatch(user -> roles.containsAll(user.roles())));
        assertEquals(Set.of("order_manager"), template().createRoles());
        Map<String, String> stateRoles = Map.of("registration", "order_manager", "composition_approval", "order_manager",
                "price_approval", "procurement_specialist", "delivery_approval", "logistics_specialist",
                "payment_approval", "finance_specialist", "postponed_review", "order_manager",
                "execution_decision", "procurement_head", "execution", "executor", "postponed_execution", "procurement_head");
        for (StateDefinition state : template().states()) {
            assertEquals(stateRoles.containsKey(state.id()) ? Set.of(stateRoles.get(state.id())) : Set.of(), state.roles());
            assertEquals(Set.of("registration", "composition_approval").contains(state.id()), state.editParameters().all(), state.id());
        }
    }

    @Test
    void subclassesInheritAndConsumablesCanGetTheirOwnCopiedTemplate() throws Exception {
        assertEquals(TEMPLATE_ID, templates.resolveTemplate("office_order").id());
        assertEquals(TEMPLATE_ID, templates.resolveTemplate("consumables_order").id());
        ProcessTemplate original = template();
        ProcessTemplate copied = templates.copyTemplate(TEMPLATE_ID, "consumables_order", "consumables_supply");
        assertEquals("consumables_order", copied.classId());
        assertEquals("consumables_supply", templates.resolveTemplate("consumables_order").id());
        assertEquals(TEMPLATE_ID, templates.resolveTemplate("office_order").id());
        assertEquals(original, template());
        assertEquals(original.transitions(), copied.transitions());
        assertThrows(IllegalArgumentException.class, () -> templates.copyTemplate(TEMPLATE_ID, "consumables_order", "duplicate"));
        BusinessObject created = result(engine.createObject("user_manager", "consumables_order", Map.of()));
        assertEquals("consumables_supply", created.templateId());
    }

    @Test
    void officeQuantityUsesOverriddenRangeAndFailedActionDoesNotChangeHistory() throws Exception {
        String id = "demo-office-quantity-700";
        advance(id, 1);
        var before = engine.getObject(id);
        var history = engine.getHistory(id);
        rejected(WorkflowError.CONDITION_NOT_MET, engine.executeAction("user_manager", id, "approve"));
        assertEquals(before, engine.getObject(id));
        assertEquals(history, engine.getHistory(id));
        assertTrue(engine.getAvailableActions("user_manager", id).contains("approve"));
        assertTrue(evaluate(transition("composition_approval", "approve").condition(), "standard_order", before.parameters()));
        assertFalse(evaluate(transition("composition_approval", "approve").condition(), "office_order", before.parameters()));
        result(engine.updateParameter("user_manager", id, "quantity", IntNode.valueOf(500)));
        assertEquals("price_approval", result(engine.executeAction("user_manager", id, "approve")).currentStateId());
    }

    @ParameterizedTest
    @ValueSource(strings = {STANDARD, OFFICE})
    void bothSuccessfulPaymentScenariosCompleteThroughTheUniversalEngine(String id) throws Exception {
        int initialHistory = engine.getHistory(id).size();
        advance(id, MAIN_PATH.size());
        assertEquals("completed", engine.getObject(id).stateId());
        assertEquals(initialHistory + 7, engine.getHistory(id).size());
        assertTrue(engine.getAvailableActions("user_coordinator", id).isEmpty());
        rejected(WorkflowError.FINAL_STATE, engine.executeAction("user_executor", id, "complete"));
        rejected(WorkflowError.FINAL_STATE, engine.updateParameter("user_manager", id, "quantity", IntNode.valueOf(1)));
    }

    @ParameterizedTest
    @CsvSource({"standard_order,30,true", "standard_order,31,false", "office_order,30,true", "office_order,40,true",
            "office_order,50,true", "office_order,51,false", "consumables_order,40,false"})
    void paymentConditionUsesBothOrBranchesClassPredicateAndNumericComparison(String classId, int prepayment, boolean expected) throws Exception {
        Map<String, JsonNode> values = values(STANDARD);
        values.put("prepayment_percent", IntNode.valueOf(prepayment));
        assertEquals(expected, evaluate(transition("payment_approval", "approve").condition(), classId, values));
    }

    @Test
    void officeExceptionIsOneDnfConditionNotTwoAmbiguousTransitions() throws Exception {
        TransitionDefinition payment = transition("payment_approval", "approve");
        assertEquals(2, payment.condition().groups().size());
        Map<String, JsonNode> standard = values(STANDARD);
        Map<String, JsonNode> office = values(OFFICE);
        DnfCondition first = new DnfCondition(List.of(payment.condition().groups().getFirst()));
        DnfCondition second = new DnfCondition(List.of(payment.condition().groups().getLast()));
        assertTrue(evaluate(first, "standard_order", standard));
        assertFalse(evaluate(second, "standard_order", standard));
        assertFalse(evaluate(first, "office_order", office));
        assertTrue(evaluate(second, "office_order", office));
        result(engine.updateParameter("user_manager", OFFICE, "prepayment_percent", IntNode.valueOf(20)));
        advance(OFFICE, 5);
        assertEquals("execution_decision", engine.getObject(OFFICE).stateId());
    }

    @ParameterizedTest
    @CsvSource({"product_name,missing", "quantity,0", "quantity,10001", "supplier_name,missing", "order_number,missing"})
    void compositionAndRejectsEveryInvalidConjunct(String parameterId, String value) throws Exception {
        Map<String, JsonNode> values = values(STANDARD);
        values.put(parameterId, value.equals("missing") ? NullNode.instance : IntNode.valueOf(Integer.parseInt(value)));
        assertFalse(evaluate(transition("composition_approval", "approve").condition(), "standard_order", values));
    }

    @ParameterizedTest
    @CsvSource({"price_approval,total_amount,0", "price_approval,total_amount,2000001",
            "delivery_approval,delivery_days,0", "delivery_approval,delivery_days,61"})
    void priceAndDeliveryRangesBlockTheirOwnApprovals(String stateId, String parameterId, int value) throws Exception {
        Map<String, JsonNode> values = values(STANDARD);
        values.put(parameterId, IntNode.valueOf(value));
        assertFalse(evaluate(transition(stateId, "approve").condition(), "standard_order", values));
        values.remove(parameterId);
        assertFalse(evaluate(transition(stateId, "approve").condition(), "standard_order", values));
    }

    @Test
    void notBlockReasonAndEveryPaymentConjunctAreEnforced() throws Exception {
        DnfCondition condition = transition("payment_approval", "approve").condition();
        for (String id : List.of(STANDARD, OFFICE)) {
            Map<String, JsonNode> values = values(id);
            String classId = engine.getObject(id).classId();
            assertTrue(evaluate(condition, classId, values));
            values.put("block_reason", TextNode.valueOf("Проверка поставщика"));
            assertFalse(evaluate(condition, classId, values));
            values.put("block_reason", TextNode.valueOf(""));
            assertFalse(evaluate(condition, classId, values));
            values.remove("block_reason");
            assertTrue(evaluate(condition, classId, values));
            values.put("total_amount", IntNode.valueOf(2000001));
            assertFalse(evaluate(condition, classId, values));
            values.put("total_amount", IntNode.valueOf(100));
            values.remove("prepayment_percent");
            assertFalse(evaluate(condition, classId, values));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_number", "product_name", "quantity", "total_amount", "supplier_name", "delivery_days", "prepayment_percent"})
    void completionRequiresEveryMandatoryParameter(String parameterId) throws Exception {
        Map<String, JsonNode> values = values(STANDARD);
        DnfCondition completion = transition("execution", "complete").condition();
        assertTrue(evaluate(completion, "standard_order", values));
        values.remove(parameterId);
        assertFalse(evaluate(completion, "standard_order", values));
    }

    @Test
    void invalidDemoOrdersAreBlockedAtTheirExpectedCheckpoints() throws Exception {
        advance("demo-missing-product", 1);
        rejected(WorkflowError.CONDITION_NOT_MET, engine.executeAction("user_manager", "demo-missing-product", "approve"));
        advance("demo-invalid-prepayment", 4);
        rejected(WorkflowError.CONDITION_NOT_MET, engine.executeAction("user_finance", "demo-invalid-prepayment", "approve"));
        assertEquals("composition_approval", engine.getObject("demo-missing-product").stateId());
        assertEquals("payment_approval", engine.getObject("demo-invalid-prepayment").stateId());
    }

    @Test
    void executesEveryCorrectionAndPostponementTransition() throws Exception {
        advance(STANDARD, 1);
        action(STANDARD, "postpone", "postponed_review");
        action(STANDARD, "resume_review", "composition_approval");
        for (int stage = 2; stage <= 4; stage++) {
            for (int step = 1; step < stage; step++) {
                action(STANDARD, "approve", List.of("price_approval", "delivery_approval", "payment_approval").get(step - 1));
            }
            action(STANDARD, "correction", "composition_approval");
            for (int step = 1; step < stage; step++) {
                action(STANDARD, "approve", List.of("price_approval", "delivery_approval", "payment_approval").get(step - 1));
            }
            action(STANDARD, "postpone", "postponed_review");
            action(STANDARD, "resume_review", "composition_approval");
        }
        for (int index = 1; index < 5; index++) {
            result(engine.executeAction("user_coordinator", STANDARD, MAIN_PATH.get(index)));
        }
        action(STANDARD, "return_to_approval", "composition_approval");
        for (int index = 1; index < 5; index++) {
            result(engine.executeAction("user_coordinator", STANDARD, MAIN_PATH.get(index)));
        }
        action(STANDARD, "postpone_review", "postponed_execution");
        action(STANDARD, "reconsider", "execution_decision");
        action(STANDARD, "execute", "execution");
        action(STANDARD, "postpone_execution", "postponed_execution");
        action(STANDARD, "reconsider", "execution_decision");
        action(STANDARD, "execute", "execution");
        action(STANDARD, "complete", "completed");
        Set<String> visited = engine.getHistory(STANDARD).stream().filter(entry -> entry.operation().equals("ACTION"))
                .map(entry -> entry.details().get("oldState").asText() + "/" + entry.details().get("actionId").asText()
                        + "/" + entry.details().get("newState").asText()).collect(Collectors.toSet());
        assertEquals(template().transitions().stream().map(transition -> transition.from() + "/" + transition.action()
                + "/" + transition.to()).collect(Collectors.toSet()), visited);
    }

    @Test
    void stateRolesAndEditingRulesAreEnforcedWithSeedUsers() throws Exception {
        rejected(WorkflowError.ACCESS_DENIED, engine.createObject("user_finance", "standard_order", Map.of()));
        BusinessObject created = result(engine.createObject("user_manager", "office_order", values(OFFICE)));
        assertEquals(TEMPLATE_ID, created.templateId());
        rejected(WorkflowError.ACCESS_DENIED, engine.executeAction("user_executor", STANDARD, "complete"));
        advance(STANDARD, 2);
        rejected(WorkflowError.ACCESS_DENIED, engine.executeAction("user_manager", STANDARD, "approve"));
        rejected(WorkflowError.ACCESS_DENIED, engine.updateParameter("user_procurement", STANDARD, "total_amount", IntNode.valueOf(1)));
    }

    @Test
    void demoObjectsAndTheirCreationHistoryLoadAndSurviveRestart() throws Exception {
        assertEquals(Set.of(STANDARD, OFFICE, "demo-office-quantity-700", "demo-missing-product", "demo-invalid-prepayment"),
                seed.objects().stream().map(BusinessObject::id).collect(Collectors.toSet()));
        assertEquals(5, seed.history().size());
        for (BusinessObject object : seed.objects()) {
            assertEquals("registration", object.stateId());
            assertEquals(TEMPLATE_ID, object.templateId());
            var entry = engine.getHistory(object.id()).getFirst();
            assertEquals("CREATE", entry.operation());
            assertEquals("user_manager", entry.userId());
            assertEquals("registration", entry.details().get("initialState").asText());
            assertEquals(new ObjectMapper().valueToTree(object.parameters()), entry.details().get("parameters"));
        }
        advance(STANDARD, 4);
        engine.close();
        engine = new DefaultWorkflowEngine(new FileRepository(directory.resolve("store.json"), CLOCK));
        assertEquals("payment_approval", engine.getObject(STANDARD).stateId());
        assertEquals("execution_decision", result(engine.executeAction("user_finance", STANDARD, "approve")).currentStateId());
        assertEquals(6, engine.getHistory(STANDARD).size());
    }

    private ProcessTemplate template() throws Exception {
        return templates.getTemplate(TEMPLATE_ID);
    }

    private TransitionDefinition transition(String stateId, String action) throws Exception {
        return template().transitions().stream().filter(transition -> transition.from().equals(stateId)
                && transition.action().equals(action)).findFirst().orElseThrow();
    }

    private Map<String, JsonNode> values(String id) {
        return new HashMap<>(engine.getObject(id).parameters());
    }

    private boolean evaluate(DnfCondition condition, String classId, Map<String, JsonNode> values) throws Exception {
        ConditionEvaluator evaluator = new DefaultConditionEvaluator(catalog);
        Map<String, PredicateDefinition> predicates = template().predicates().stream()
                .collect(Collectors.toMap(PredicateDefinition::id, predicate -> predicate));
        return evaluator.evaluate(condition, predicates, new ConditionContext(classId, values));
    }

    private void advance(String id, int steps) throws Exception {
        for (int index = 0; index < steps; index++) {
            result(engine.executeAction(MAIN_USERS.get(index), id, MAIN_PATH.get(index)));
        }
    }

    private void action(String id, String actionId, String target) throws Exception {
        assertEquals(target, result(engine.executeAction("user_coordinator", id, actionId)).currentStateId());
    }

    private static <Value> Value result(CompletableFuture<Value> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void rejected(WorkflowError code, CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result(future));
        assertEquals(code, assertInstanceOf(WorkflowException.class, failure.getCause()).code());
    }
}
