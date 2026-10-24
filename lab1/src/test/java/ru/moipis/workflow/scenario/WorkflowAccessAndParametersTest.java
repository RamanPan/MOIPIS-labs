package ru.moipis.workflow.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.template.model.ProcessTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static ru.moipis.workflow.engine.exception.WorkflowError.*;

@Timeout(20)
class WorkflowAccessAndParametersTest {
    @TempDir Path directory;
    private WorkflowFixture fixture;

    @BeforeEach
    void setup() throws Exception {
        fixture = new WorkflowFixture(directory);
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_procurement", "user_logistics", "user_finance", "user_head", "user_executor"})
    void creationWithoutACreationRoleLeavesObjectsAndHistoryUnchanged(String userId) throws Exception {
        fixture.reject(ACCESS_DENIED, () -> fixture.engine.createObject(userId, "standard_order", fixture.parameters("standard_order")));
    }

    @Test
    void userWithSeveralRolesCanCreateAndCompleteTheProcess() throws Exception {
        BusinessObject object = fixture.create("user_coordinator", "office_order", fixture.parameters("office_order"));
        for (int index = 0; index < WorkflowFixture.MAIN_PATH.size(); index++) {
            fixture.action("user_coordinator", object.id(), WorkflowFixture.MAIN_PATH.get(index), WorkflowFixture.MAIN_STATES.get(index + 1));
        }
        assertTrue(fixture.engine.getHistory(object.id()).stream().allMatch(entry -> entry.userId().equals("user_coordinator")));
    }

    @ParameterizedTest
    @CsvSource({"registration,complete", "composition_approval,approve", "price_approval,approve",
            "delivery_approval,approve", "payment_approval,approve", "postponed_review,resume_review",
            "execution_decision,execute", "execution,complete", "postponed_execution,reconsider"})
    void wrongRoleCannotPerformActionsInAnyNonterminalState(String stateId, String actionId) throws Exception {
        BusinessObject object = fixture.atState(stateId);
        String user = stateId.equals("execution") ? "user_manager" : "user_executor";
        StoreData before = fixture.repository.load();
        assertTrue(fixture.engine.getAvailableActions(user, object.id()).isEmpty());
        fixture.unchanged(before);
        fixture.reject(ACCESS_DENIED, () -> fixture.sendAction(user, object.id(), actionId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"registration", "composition_approval"})
    void allowedEditingWritesOnlyTheNamedParameterAndOneAuditRecord(String stateId) throws Exception {
        BusinessObject object = fixture.atState(stateId);
        fixture.update("user_manager", object.id(), "quantity", IntNode.valueOf(900));
        fixture.update("user_manager", object.id(), "block_reason", TextNode.valueOf("Проверка"));
        fixture.update("user_manager", object.id(), "block_reason", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"price_approval", "delivery_approval", "payment_approval", "postponed_review",
            "execution_decision", "execution", "postponed_execution", "completed"})
    void forbiddenEditingDoesNotChangeTheObjectOrHistory(String stateId) throws Exception {
        BusinessObject object = fixture.atState(stateId);
        fixture.reject(stateId.equals("completed") ? FINAL_STATE : ACCESS_DENIED,
                () -> fixture.sendUpdate("user_coordinator", object.id(), "quantity", IntNode.valueOf(1)));
    }

    @ParameterizedTest
    @CsvSource({"quantity,'\"ten\"'", "quantity,1.5", "total_amount,'\"100\"'", "product_name,123",
            "delivery_days,true", "prepayment_percent,[]"})
    void invalidParameterTypesAreRejectedDuringCreationAndEditing(String parameterId, String json) throws Exception {
        var value = new ObjectMapper().readTree(json);
        var values = fixture.parameters("standard_order");
        values.put(parameterId, value);
        fixture.reject(INVALID_PARAMETER_TYPE, () -> fixture.engine.createObject("user_manager", "standard_order", values));
        BusinessObject object = fixture.create("standard_order");
        fixture.reject(INVALID_PARAMETER_TYPE, () -> fixture.sendUpdate("user_manager", object.id(), parameterId, value));
    }

    @ParameterizedTest
    @CsvSource({"standard_order,unknown_parameter", "standard_order,office_location", "office_order,storage_conditions",
            "consumables_order,office_location"})
    void unknownAndSiblingOnlyParametersAreRejectedAtBothEntryPoints(String classId, String parameterId) throws Exception {
        var values = fixture.parameters(classId);
        values.put(parameterId, TextNode.valueOf("Недопустимый параметр"));
        fixture.reject(UNKNOWN_PARAMETER, () -> fixture.engine.createObject("user_manager", classId, values));
        BusinessObject object = fixture.create(classId);
        fixture.reject(UNKNOWN_PARAMETER, () -> fixture.sendUpdate("user_manager", object.id(), parameterId, TextNode.valueOf("Значение")));
    }

    @Test
    void outOfRangeCreationIsAllowedButTransitionRequiresRepair() throws Exception {
        var values = fixture.parameters("office_order");
        values.put("quantity", IntNode.valueOf(700));
        BusinessObject object = fixture.create("user_manager", "office_order", values);
        fixture.action("user_manager", object.id(), "complete", "composition_approval");
        fixture.reject(CONDITION_NOT_MET, () -> fixture.sendAction("user_manager", object.id(), "approve"));
        fixture.update("user_manager", object.id(), "quantity", IntNode.valueOf(500));
        fixture.action("user_manager", object.id(), "approve", "price_approval");
    }

    @ParameterizedTest
    @CsvSource({"standard_order,product_name,null,composition_approval", "standard_order,quantity,0,composition_approval",
            "office_order,quantity,501,composition_approval", "standard_order,total_amount,2000001,price_approval",
            "standard_order,delivery_days,61,delivery_approval", "standard_order,prepayment_percent,60,payment_approval",
            "office_order,prepayment_percent,51,payment_approval", "office_order,block_reason,'\"blocked\"',payment_approval"})
    void failedPredicatesRejectApprovalsWithoutAnyPartialOperation(String classId, String parameterId, String json, String stateId) throws Exception {
        var values = fixture.parameters(classId);
        values.put(parameterId, new ObjectMapper().readTree(json));
        BusinessObject object = fixture.create("user_manager", classId, values);
        fixture.moveTo(object.id(), stateId);
        fixture.reject(CONDITION_NOT_MET, () -> fixture.sendAction("user_coordinator", object.id(), "approve"));
    }

    @Test
    void inheritedAndSubclassParametersAreUsableWithoutASeparateJavaClass() throws Exception {
        BusinessObject object = fixture.create("office_order");
        assertTrue(fixture.catalog.getEffectiveParameters("office_order").keySet().containsAll(
                Set.of("order_number", "product_name", "supplier_name", "quantity", "office_location")));
        assertEquals(new BigDecimal("500"), fixture.catalog.getEffectiveParameters("office_order").get("quantity").max());
        fixture.update("user_manager", object.id(), "order_number", TextNode.valueOf("OFFICE-NEW"));
        fixture.update("user_manager", object.id(), "office_location", TextNode.valueOf("Казань"));
        fixture.update("user_manager", object.id(), "quantity", IntNode.valueOf(500));
        fixture.action("user_manager", object.id(), "complete", "composition_approval");
        fixture.action("user_manager", object.id(), "approve", "price_approval");
    }

    @Test
    void copyingToASubclassLeavesItsSiblingAndExistingObjectsBoundToTheOriginal() throws Exception {
        BusinessObject existing = fixture.create("consumables_order");
        StoreData before = fixture.repository.load();
        ProcessTemplate original = fixture.templates.getTemplate(WorkflowFixture.TEMPLATE_ID);
        ProcessTemplate copy = fixture.templates.copyTemplate(WorkflowFixture.TEMPLATE_ID, "consumables_order", "consumables_own");
        assertEquals(original, fixture.templates.getTemplate(WorkflowFixture.TEMPLATE_ID));
        assertEquals(WorkflowFixture.TEMPLATE_ID, fixture.templates.resolveTemplate("office_order").id());
        assertEquals("consumables_own", fixture.templates.resolveTemplate("consumables_order").id());
        assertEquals(original.transitions(), copy.transitions());
        assertEquals(before.objects(), fixture.repository.load().objects());
        assertEquals(before.history(), fixture.repository.load().history());
        fixture.restart();
        assertEquals(copy, fixture.templates.getTemplate("consumables_own"));
        assertEquals(existing, fixture.engine.getObject(existing.id()));
        fixture.action("user_manager", existing.id(), "complete", "composition_approval");
        assertEquals(WorkflowFixture.TEMPLATE_ID, fixture.engine.getObject(existing.id()).templateId());
        BusinessObject created = fixture.create("consumables_order");
        assertEquals("consumables_own", created.templateId());
        StoreData unchanged = fixture.repository.load();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.templates.copyTemplate(WorkflowFixture.TEMPLATE_ID, "consumables_order", "second_copy"));
        fixture.unchanged(unchanged);
    }

    @Test
    void missingTemplateAndUnknownActionsHaveExplicitErrorsWithoutHistory() throws Exception {
        fixture.reject(TEMPLATE_ERROR, () -> fixture.engine.createObject("user_manager", "order", Map.of()));
        fixture.reject(NOT_FOUND, () -> fixture.engine.createObject("user_manager", "unknown_class", Map.of()));
        BusinessObject object = fixture.create("standard_order");
        fixture.reject(INVALID_ACTION, () -> fixture.sendAction("user_manager", object.id(), "unknown_action"));
        fixture.reject(INVALID_ACTION, () -> fixture.sendAction("user_manager", object.id(), "approve"));
    }
}
