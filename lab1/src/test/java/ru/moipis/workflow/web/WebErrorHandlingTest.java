package ru.moipis.workflow.web;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WebErrorHandlingTest {
    @TempDir Path directory;
    WebTestFixture fixture;
    final String root = "/api/objects/demo-office-quantity-700";

    @BeforeEach void open() throws Exception { fixture = new WebTestFixture(directory.resolve("store.json")); }
    @AfterEach void close() { if (fixture != null) fixture.close(); }

    @Test void wrongRoleAndFalseConditionDoNotChangeObjectsHistoryOrFile() throws Exception {
        unchanged("POST", root + "/actions", """
                {"userId":"user_finance","actionId":"complete"}
                """, 403, "ACCESS_DENIED");
        assertEquals(200, fixture.send("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"}
                """).statusCode());
        unchanged("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"approve"}
                """, 409, "CONDITION_NOT_MET");
    }

    @Test void requestShapeAndParameterTypeErrorsHaveOneJsonFormat() throws Exception {
        unchanged("POST", "/api/objects", "{", 400, "INVALID_REQUEST");
        unchanged("POST", "/api/objects", "null", 400, "INVALID_REQUEST");
        unchanged("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{},"stateId":"completed"}
                """, 400, "INVALID_REQUEST");
        unchanged("PATCH", root + "/parameters/quantity", """
                {"userId":"user_manager","value":"100"}
                """, 400, "INVALID_PARAMETER_TYPE");
        unchanged("PATCH", root + "/parameters/quantity", """
                {"userId":"user_manager"}
                """, 400, "INVALID_REQUEST");
        unchanged("PATCH", root + "/parameters/no_such_parameter", """
                {"userId":"user_manager","value":100}
                """, 400, "UNKNOWN_PARAMETER");
        unchanged("GET", root + "/access", null, 400, "INVALID_REQUEST");
        unchanged("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"} {}
                """, 400, "INVALID_REQUEST");
    }

    @Test void missingResourcesAndUsersReturn404() throws Exception {
        unchanged("GET", "/api/objects/missing", null, 404, "NOT_FOUND");
        unchanged("GET", "/api/classes/missing/parameters", null, 404, "NOT_FOUND");
        unchanged("GET", "/api/not-a-route", null, 404, "NOT_FOUND");
        unchanged("GET", root + "/access?userId=missing", null, 404, "NOT_FOUND");
        unchanged("POST", "/api/objects", """
                {"userId":"user_manager","classId":"missing","parameters":{}}
                """, 404, "NOT_FOUND");
    }

    @Test void readOnlyAndTerminalStatesAreCheckedByTheBackend() throws Exception {
        String objectRoot = "/api/objects/demo-office-valid";
        action(objectRoot, "complete");
        action(objectRoot, "approve");
        unchanged("PATCH", objectRoot + "/parameters/quantity", """
                {"userId":"user_coordinator","value":10}
                """, 403, "ACCESS_DENIED");
        action(objectRoot, "approve");
        action(objectRoot, "approve");
        action(objectRoot, "approve");
        action(objectRoot, "execute");
        action(objectRoot, "complete");
        var access = fixture.json(fixture.send("GET", objectRoot + "/access?userId=user_coordinator", null));
        assertFalse(access.path("canEditParameters").asBoolean());
        assertEquals(0, access.path("availableActions").size());
        unchanged("POST", objectRoot + "/actions", """
                {"userId":"user_coordinator","actionId":"complete"}
                """, 409, "FINAL_STATE");
        unchanged("PATCH", objectRoot + "/parameters/quantity", """
                {"userId":"user_coordinator","value":10}
                """, 409, "FINAL_STATE");
    }

    @Test void nullClearsAValueAndExactJsonNumbersSurviveHttp() throws Exception {
        var clear = fixture.send("PATCH", root + "/parameters/block_reason", """
                {"userId":"user_manager","value":null}
                """);
        assertEquals(200, clear.statusCode(), clear.body());
        assertTrue(fixture.engine.getObject("demo-office-quantity-700").parameters().get("block_reason").isNull());
        var created = fixture.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{
                "quantity":123456789012345678901234567890,"total_amount":0.12345678901234567890123456789}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        var object = fixture.engine.getObject(fixture.json(created).path("id").asText());
        assertEquals("123456789012345678901234567890", object.parameters().get("quantity").asText());
        assertEquals("0.12345678901234567890123456789", object.parameters().get("total_amount").asText());
        assertTrue(created.body().contains("0.12345678901234567890123456789"));
    }

    @Test void numericIdentifiersAndCreationWithoutARoleAreRejected() throws Exception {
        unchanged("POST", "/api/objects", """
                {"userId":123,"classId":"office_order","parameters":{}}
                """, 400, "INVALID_REQUEST");
        unchanged("POST", "/api/objects", """
                {"userId":"user_finance","classId":"office_order","parameters":{}}
                """, 403, "ACCESS_DENIED");
    }

    @Test void ambiguousTransitionsAre409AndDoNotInvokeTheHandler() throws Exception {
        ru.moipis.workflow.template.model.ProcessTemplate template;
        try (var input = getClass().getResourceAsStream("/templates/ambiguous-workflow-template.json")) {
            template = fixture.mapper.readValue(input, ru.moipis.workflow.template.model.ProcessTemplate.class);
        }
        var extra = template;
        fixture.repository.update(store -> {
            var templates = new java.util.ArrayList<>(store.templates());
            templates.add(extra);
            return new ru.moipis.workflow.repository.model.StoreData(1, store.classes(), store.roles(), store.users(),
                    templates, store.objects(), store.history());
        });
        var created = fixture.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{"quantity":100}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        fixture.engine.registerHandler("choose", event -> { called.set(true); return null; });
        unchanged("POST", "/api/objects/" + fixture.json(created).path("id").asText() + "/actions", """
                {"userId":"user_manager","actionId":"choose"}
                """, 409, "TEMPLATE_ERROR");
        assertFalse(called.get());
    }

    @Test void handlerFailureAndClosedEngineHaveSafeJsonErrors() throws Exception {
        fixture.engine.registerHandler("complete", event -> { throw new IllegalStateException("private-handler-detail"); });
        unchanged("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"}
                """, 500, "HANDLER_ERROR");
        fixture.engine.close();
        unchanged("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"}
                """, 503, "ENGINE_CLOSED");
    }

    void action(String path, String action) throws Exception {
        var response = fixture.send("POST", path + "/actions",
                "{\"userId\":\"user_coordinator\",\"actionId\":\"" + action + "\"}");
        assertEquals(200, response.statusCode(), response.body());
    }

    void unchanged(String method, String path, String body, int status, String code) throws Exception {
        var before = fixture.repository.load();
        var bytes = Files.readAllBytes(directory.resolve("store.json"));
        var response = fixture.send(method, path, body);
        assertEquals(status, response.statusCode(), response.body());
        var json = fixture.json(response);
        assertEquals(code, json.path("code").asText());
        assertFalse(json.path("message").asText().isBlank());
        assertTrue(json.path("details").isObject());
        assertFalse(response.body().contains("stackTrace"));
        assertEquals(before, fixture.repository.load());
        assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("store.json")));
    }
}
