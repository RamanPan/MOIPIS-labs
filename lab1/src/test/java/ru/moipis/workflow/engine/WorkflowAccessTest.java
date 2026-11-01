package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.node.IntNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.repository.file.FileRepository;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.template.model.ParameterEditPolicy;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowAccessTest {
    @TempDir Path directory;

    @Test
    void accessIncludesConditionalActionsAndUsesTheRealUsersRoleWithoutWriting() throws Exception {
        var repository = new FileRepository(directory.resolve("store.json"), Clock.systemUTC());
        try (WorkflowEngine engine = new DefaultWorkflowEngine(repository)) {
            var object = engine.createObject("user_manager", "office_order", Map.of("quantity", IntNode.valueOf(700)))
                    .get(5, TimeUnit.SECONDS);
            engine.executeAction("user_manager", object.id(), "complete").get(5, TimeUnit.SECONDS);
            var before = repository.load();
            var access = engine.getAccess("user_manager", object.id());
            assertEquals("composition_approval", access.stateId());
            assertEquals(List.of("approve", "postpone"), access.availableActionIds());
            assertTrue(access.canEditParameters());
            assertTrue(access.editableParameterIds().contains("quantity"));
            var denied = engine.getAccess("user_finance", object.id());
            assertFalse(denied.canEditParameters());
            assertTrue(denied.availableActionIds().isEmpty());
            assertTrue(denied.editableParameterIds().isEmpty());
            assertEquals(before, repository.load());
        }
    }

    @Test
    void selectiveEditingDoesNotDependOnHavingOutgoingActions() throws Exception {
        var repository = new FileRepository(directory.resolve("store.json"), Clock.systemUTC());
        var seed = repository.loadOrInitialize();
        var original = seed.templates().getFirst();
        var template = new ProcessTemplate(original.id(), original.title(), original.classId(), original.createRoles(),
                List.of(), List.of(), List.of(new StateDefinition("editable", "Editable", true, false,
                Set.of("order_manager"), new ParameterEditPolicy(false, Set.of("quantity")))), List.of());
        repository.save(new StoreData(1, seed.classes(), seed.roles(), seed.users(), List.of(template), List.of(), List.of()));
        try (WorkflowEngine engine = new DefaultWorkflowEngine(repository)) {
            var object = engine.createObject("user_manager", "office_order", Map.of()).get(5, TimeUnit.SECONDS);
            var access = engine.getAccess("user_manager", object.id());
            assertTrue(access.availableActionIds().isEmpty());
            assertTrue(access.canEditParameters());
            assertEquals(Set.of("quantity"), access.editableParameterIds());
            engine.updateParameter("user_manager", object.id(), "quantity", IntNode.valueOf(10)).get(5, TimeUnit.SECONDS);
            assertEquals(10, engine.getObject(object.id()).parameters().get("quantity").intValue());
        }
    }

    @Test
    void terminalStateDeniesEditingEvenWhenItsPolicyAllowsIt() throws Exception {
        var repository = new FileRepository(directory.resolve("store.json"), Clock.systemUTC());
        var seed = repository.loadOrInitialize();
        var original = seed.templates().getFirst();
        var template = new ProcessTemplate(original.id(), original.title(), original.classId(), original.createRoles(),
                List.of(), List.of(), List.of(new StateDefinition("done", "Done", true, true,
                Set.of("order_manager"), true)), List.of());
        repository.save(new StoreData(1, seed.classes(), seed.roles(), seed.users(), List.of(template), List.of(), List.of()));
        try (WorkflowEngine engine = new DefaultWorkflowEngine(repository)) {
            var object = engine.createObject("user_manager", "office_order", Map.of()).get(5, TimeUnit.SECONDS);
            var access = engine.getAccess("user_manager", object.id());
            assertFalse(access.canEditParameters());
            assertTrue(access.availableActionIds().isEmpty());
            assertTrue(access.editableParameterIds().isEmpty());
        }
    }
}
