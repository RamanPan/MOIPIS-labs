package ru.moipis.workflow.web;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowApiIntegrationTest {
    @TempDir Path directory;
    WebTestFixture fixture;

    @BeforeEach void open() throws Exception { fixture = new WebTestFixture(directory.resolve("store.json")); }
    @AfterEach void close() { if (fixture != null) fixture.close(); }

    @Test void returnsActualUsersAndInheritedClassParameters() throws Exception {
        var users = fixture.send("GET", "/api/users", null);
        assertEquals(200, users.statusCode());
        assertEquals(7, fixture.json(users).size());
        assertEquals("user_manager", fixture.json(users).get(0).path("id").asText());
        assertEquals("Анна — менеджер заказа", fixture.json(users).get(0).path("name").asText());
        assertEquals("order_manager", fixture.json(users).get(0).path("roles").get(0).asText());
        assertEquals("Менеджер заказа", fixture.json(users).get(0).path("roleTitles").path("order_manager").asText());
        var classes = fixture.send("GET", "/api/classes", null);
        assertEquals(200, classes.statusCode());
        assertEquals(4, fixture.json(classes).size());
        var parameters = fixture.send("GET", "/api/classes/office_order/parameters", null);
        assertEquals(200, parameters.statusCode());
        var quantity = StreamSupport.stream(fixture.json(parameters).spliterator(), false)
                .filter(node -> node.path("id").asText().equals("quantity")).findFirst().orElseThrow();
        assertEquals(500, quantity.path("max").intValue());
    }

    @Test void listsSeedOrdersAndCreatesWithoutRangeValidation() throws Exception {
        var orders = fixture.send("GET", "/api/objects", null);
        assertEquals(200, orders.statusCode());
        assertEquals(5, fixture.json(orders).size());
        var created = fixture.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{"order_number":"HTTP-001","quantity":700}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        var object = fixture.json(created);
        String id = object.path("id").asText();
        assertFalse(id.isBlank());
        assertEquals("/api/objects/" + id, created.headers().firstValue("Location").orElseThrow());
        assertEquals("registration", object.path("stateId").asText());
        assertEquals("Регистрация", object.path("stateTitle").asText());
        assertEquals(700, object.path("parameters").path("quantity").intValue());
        assertEquals(6, fixture.repository.load().objects().size());
        assertEquals("CREATE", fixture.engine.getHistory(id).getFirst().operation());
        assertEquals(object, fixture.json(fixture.send("GET", "/api/objects/" + id, null)));
    }

    @Test void classCatalogReportsOwnAndInheritedTemplatesWithoutDroppingBaseClasses() throws Exception {
        var bytes = Files.readAllBytes(directory.resolve("store.json"));
        var classes = classCatalog();
        assertEquals(Set.of("order", "standard_order", "office_order", "consumables_order"), classes.keySet());
        assertTrue(classes.get("order").has("templateId"));
        assertTrue(classes.get("order").path("templateId").isNull());
        for (String id : List.of("standard_order", "office_order", "consumables_order")) {
            assertEquals("standard_order_supply", classes.get(id).path("templateId").asText());
        }
        assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("store.json")));

        fixture.templates.copyTemplate("standard_order_supply", "office_order", "office_copy");
        classes = classCatalog();
        assertEquals("office_copy", classes.get("office_order").path("templateId").asText());
        assertEquals("standard_order_supply", classes.get("consumables_order").path("templateId").asText());
    }

    @Test void baseClassBecomesAvailableWhenItHasAnActualTemplate() throws Exception {
        var rootTemplate = new ProcessTemplate("root_process", "Root process", "order", Set.of("order_manager"),
                List.of(), List.of(), List.of(new StateDefinition("draft", "Draft", true, false,
                Set.of("order_manager"), true)), List.of());
        fixture.repository.update(store -> {
            var templates = new java.util.ArrayList<>(store.templates());
            templates.add(rootTemplate);
            return new StoreData(store.schemaVersion(), store.classes(), store.roles(), store.users(),
                    templates, store.objects(), store.history());
        });
        assertEquals("root_process", classCatalog().get("order").path("templateId").asText());
        var created = fixture.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"order","parameters":{"order_number":"ROOT-001"}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("root_process", fixture.json(created).path("templateId").asText());
        assertEquals("draft", fixture.json(created).path("stateId").asText());
    }

    private Map<String, com.fasterxml.jackson.databind.JsonNode> classCatalog() throws Exception {
        var response = fixture.send("GET", "/api/classes", null);
        assertEquals(200, response.statusCode(), response.body());
        return StreamSupport.stream(fixture.json(response).spliterator(), false)
                .collect(Collectors.toMap(node -> node.path("id").asText(), Function.identity()));
    }

    @Test void returnsInheritedProcessAndAccessWithoutEvaluatingFalseConditions() throws Exception {
        String root = "/api/objects/demo-office-quantity-700";
        var graph = fixture.send("GET", root + "/process", null);
        assertEquals(200, graph.statusCode());
        var process = fixture.json(graph);
        assertEquals("standard_order_supply", process.path("templateId").asText());
        assertEquals(10, process.path("states").size());
        assertEquals(19, process.path("transitions").size());
        var action = fixture.send("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"}
                """);
        assertEquals(200, action.statusCode(), action.body());
        var access = fixture.send("GET", root + "/access?userId=user_manager", null);
        assertEquals(200, access.statusCode());
        assertTrue(fixture.json(access).path("canEditParameters").asBoolean());
        assertEquals(List.of("approve", "postpone"), StreamSupport.stream(
                fixture.json(access).path("availableActions").spliterator(), false).map(n -> n.path("id").asText()).toList());
        var denied = fixture.json(fixture.send("GET", root + "/access?userId=user_finance", null));
        assertFalse(denied.path("canEditParameters").asBoolean());
        assertEquals(0, denied.path("availableActions").size());
    }

    @Test void commandsPersistStateValuesAndExactlyOneHistoryEntryBeforeResponding() throws Exception {
        String id = "demo-office-valid", root = "/api/objects/" + id;
        var update = fixture.send("PATCH", root + "/parameters/quantity", """
                {"userId":"user_manager","value":100}
                """);
        assertEquals(200, update.statusCode(), update.body());
        assertEquals(100, fixture.engine.getObject(id).parameters().get("quantity").intValue());
        var action = fixture.send("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"}
                """);
        assertEquals(200, action.statusCode());
        assertEquals("composition_approval", fixture.json(action).path("currentStateId").asText());
        var history = fixture.send("GET", root + "/history", null);
        assertEquals(200, history.statusCode());
        assertEquals(3, fixture.json(history).size());
        assertEquals("UPDATE_PARAMETER", fixture.json(history).get(1).path("operation").asText());
        assertEquals(120, fixture.json(history).get(1).path("details").path("oldValue").intValue());
        assertEquals("composition_approval", fixture.json(history).get(2).path("details").path("newState").asText());
        assertTrue(fixture.json(history).get(2).path("timestamp").isTextual());
    }

    @Test void savedTemplateIdWinsOverNewSubclassTemplate() throws Exception {
        fixture.templates.copyTemplate("standard_order_supply", "office_order", "office_copy");
        var oldGraph = fixture.json(fixture.send("GET", "/api/objects/demo-office-valid/process", null));
        assertEquals("standard_order_supply", oldGraph.path("templateId").asText());
        var created = fixture.json(fixture.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{}}
                """));
        var graph = fixture.json(fixture.send("GET", "/api/objects/" + created.path("id").asText() + "/process", null));
        assertEquals("office_copy", graph.path("templateId").asText());
    }
}
