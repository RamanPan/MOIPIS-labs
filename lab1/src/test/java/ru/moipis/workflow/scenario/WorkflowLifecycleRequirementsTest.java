package ru.moipis.workflow.scenario;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.moipis.workflow.engine.model.BusinessObject;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static ru.moipis.workflow.engine.exception.WorkflowError.FINAL_STATE;

@Timeout(20)
class WorkflowLifecycleRequirementsTest {
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

    @ParameterizedTest(name = "{0} -- {1} --> {2}")
    @CsvSource({
            "registration,complete,composition_approval,user_manager",
            "composition_approval,approve,price_approval,user_manager",
            "composition_approval,postpone,postponed_review,user_manager",
            "price_approval,approve,delivery_approval,user_procurement",
            "price_approval,correction,composition_approval,user_procurement",
            "price_approval,postpone,postponed_review,user_procurement",
            "delivery_approval,approve,payment_approval,user_logistics",
            "delivery_approval,correction,composition_approval,user_logistics",
            "delivery_approval,postpone,postponed_review,user_logistics",
            "payment_approval,approve,execution_decision,user_finance",
            "payment_approval,correction,composition_approval,user_finance",
            "payment_approval,postpone,postponed_review,user_finance",
            "postponed_review,resume_review,composition_approval,user_manager",
            "execution_decision,execute,execution,user_head",
            "execution_decision,return_to_approval,composition_approval,user_head",
            "execution_decision,postpone_review,postponed_execution,user_head",
            "execution,complete,completed,user_executor",
            "execution,postpone_execution,postponed_execution,user_executor",
            "postponed_execution,reconsider,execution_decision,user_head"
    })
    void eachTransitionHasTheCorrectSourceTargetAndExactlyOneCompleteAuditRecord(
            String source, String actionId, String target, String userId) throws Exception {
        BusinessObject object = fixture.atState(source);
        assertEquals(source, object.stateId());
        fixture.action(userId, object.id(), actionId, target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"standard_order", "office_order"})
    void fullLifecycleChecksTheObjectAndHistoryAfterEveryOperation(String classId) throws Exception {
        BusinessObject object = fixture.create(classId);
        for (int index = 0; index < WorkflowFixture.MAIN_PATH.size(); index++) {
            fixture.action(WorkflowFixture.MAIN_USERS.get(index), object.id(), WorkflowFixture.MAIN_PATH.get(index),
                    WorkflowFixture.MAIN_STATES.get(index + 1));
        }
        assertEquals("completed", fixture.engine.getObject(object.id()).stateId());
        assertEquals(8, fixture.engine.getHistory(object.id()).size());
        assertTrue(fixture.engine.getAvailableActions("user_coordinator", object.id()).isEmpty());
    }

    @Test
    void completedObjectsRejectActionsWithoutChangingAnyData() throws Exception {
        BusinessObject object = fixture.atState("completed");
        fixture.reject(FINAL_STATE, () -> fixture.sendAction("user_coordinator", object.id(), "complete"));
        fixture.reject(FINAL_STATE, () -> fixture.sendAction("user_coordinator", object.id(), "approve"));
    }
}
