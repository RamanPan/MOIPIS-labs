package ru.moipis.workflow.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The REST error contract (status/code/message/details, no stack traces, store left untouched) is
 * covered as a black-box suite in {@code integration-autotests}. What remains here are the two
 * scenarios that need a Java-level hook with no REST equivalent: forcing a handler to fail or
 * proving one was never invoked, and observing the engine after it has been closed directly.
 */
class WebErrorHandlingTest {
    @TempDir Path directory;
    WebTestFixture fixture;
    final String root = "/api/objects/demo-office-quantity-700";

    @BeforeEach void open() throws Exception { fixture = new WebTestFixture(directory.resolve("store.json")); }
    @AfterEach void close() { if (fixture != null) fixture.close(); }

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
