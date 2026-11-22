package ru.moipis.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.engine.ConfiguredObject;
import ru.moipis.workflow.engine.CoordinationScenario;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Task311ApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID = "demo-office-valid";
    private static final String TOO_MANY = "demo-office-quantity-700";
    private static final String MANAGER = "user_manager";
    private static final String FINANCE = "user_finance";

    @TempDir Path temporary;

    @Test
    void exposesReadyOrdersAndAuditsBothSuccessfulAndRejectedCommands() throws Exception {
        Path journal = temporary.resolve("run/journal.jsonl");
        try (var app = start(journal); var client = HttpClient.newHttpClient()) {
            var api = new Api(client, app.port());
            assertEquals("task-3-1-1", api.read("/meta").path("mode").asText());
            JsonNode orders = api.read("/objects");
            assertEquals(2, orders.size());
            orders.forEach(order -> assertTrue(order.path("stateId").isNull()));
            assertEquals(0, api.read("/journal").size());
            JsonNode object = api.read("/objects/" + VALID);
            assertEquals(14, object.path("parameters").path("delivery_days").asInt());
            assertEquals("standard_order_supply", object.path("templateId").asText());
            JsonNode graph = api.read("/objects/" + VALID + "/process");
            assertTrue(graph.path("currentStateId").isNull());
            assertEquals(10, graph.path("states").size());
            assertEquals(19, graph.path("transitions").size());
            assertTrue(api.access(VALID, MANAGER).path("canStart").asBoolean());
            assertFalse(api.access(VALID, FINANCE).path("canStart").asBoolean());

            api.command("start", VALID, MANAGER, null, 200);
            api.command("action", VALID, MANAGER, "complete", 200);
            assertFalse(api.access(VALID, FINANCE).path("stateActions").get(0).path("allowedByRole").asBoolean());
            assertTrue(api.access(VALID, MANAGER).path("stateActions").get(0).path("allowedByRole").asBoolean());
            JsonNode roleError = api.command("action", VALID, FINANCE, "approve", 403);
            JsonNode roleEntry = api.read("/journal?objectId=" + VALID).get(2);
            assertRejection("ACCESS_DENIED", roleError, roleEntry);
            assertEquals("composition_approval", api.read("/objects/" + VALID).path("stateId").asText());

            api.command("start", TOO_MANY, MANAGER, null, 200);
            api.command("action", TOO_MANY, MANAGER, "complete", 200);
            JsonNode conditionError = api.command("action", TOO_MANY, MANAGER, "approve", 409);
            assertRejection("CONDITION_NOT_MET", conditionError, api.read("/journal?objectId=" + TOO_MANY).get(2));

            String malformed = "{bad";
            JsonNode badCommand = api.send(malformed, 400);
            JsonNode entries = api.read("/journal");
            assertEquals(7, entries.size());
            JsonNode last = entries.get(6);
            assertEquals("INVALID_COMMAND", badCommand.path("code").asText());
            assertEquals("ERROR", last.path("outcome").asText());
            assertEquals(malformed, last.path("command").asText());
            assertEquals(last.path("id"), badCommand.path("details").path("entryId"));
            var download = api.get("/journal/download");
            assertEquals(200, download.statusCode());
            assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains("journal.jsonl"));
            assertEquals(Files.readString(journal), download.body());
            assertEquals(last, JSON.readTree(download.body().lines().toList().getLast()));
        }
    }

    @Test
    void restartingReloadsObjectsWithoutReadingPreviousJournal() throws Exception {
        Path firstJournal = temporary.resolve("first/journal.jsonl");
        try (var app = start(firstJournal); var client = HttpClient.newHttpClient()) {
            new Api(client, app.port()).command("start", VALID, MANAGER, null, 200);
        }
        String earlier = Files.readString(firstJournal);
        var scenario = CoordinationScenario.task311();
        var objects = new ArrayList<>(scenario.objects());
        objects.add(new ConfiguredObject("unbound", "standard_order", Map.of(), Map.of()));
        var scenarioWithUnboundOrder = new CoordinationScenario(scenario.catalog(), objects);
        try (var app = Task311WebApplication.start(scenarioWithUnboundOrder,
                temporary.resolve("second/journal.jsonl"), 0); var client = HttpClient.newHttpClient()) {
            var api = new Api(client, app.port());
            assertTrue(api.read("/objects/" + VALID).path("stateId").isNull());
            assertTrue(api.read("/objects/unbound").path("templateId").isNull());
            assertTrue(api.read("/objects/unbound/process").isNull());
            assertFalse(api.access("unbound", MANAGER).path("canStart").asBoolean());
            assertEquals(0, api.read("/journal").size());
            api.command("start", VALID, MANAGER, null, 200);
            assertEquals("NO_EXACT_TEMPLATE", api.command("start", "unbound", MANAGER, null, 409)
                    .path("code").asText());
            JsonNode missing = api.command("start", "missing", MANAGER, null, 404);
            JsonNode journal = api.read("/journal");
            assertRejection("UNKNOWN_OBJECT", missing, journal.get(journal.size() - 1));
            assertEquals("NOT_FOUND", JSON.readTree(api.get("/not-a-route").body()).path("code").asText());
        }
        assertEquals(earlier, Files.readString(firstJournal));
    }

    private static Task311WebApplication start(Path journal) throws Exception {
        return Task311WebApplication.start(CoordinationScenario.task311(), journal, 0);
    }

    private static void assertRejection(String code, JsonNode response, JsonNode entry) {
        assertEquals(code, response.path("code").asText());
        assertEquals(response.path("details").path("entryId"), entry.path("id"));
        assertEquals("REJECTED", entry.path("outcome").asText());
        assertEquals(entry.path("before"), entry.path("after"));
    }

    private record Api(HttpClient client, int port) {
        HttpResponse<String> get(String path) throws Exception {
            return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        JsonNode read(String path) throws Exception {
            var response = get(path);
            assertEquals(200, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }

        JsonNode access(String id, String user) throws Exception {
            return read("/objects/" + id + "/access?userId=" + user);
        }

        JsonNode command(String type, String object, String user, String action, int expected) throws Exception {
            var command = JSON.createObjectNode().put("type", type).put("objectId", object).put("userId", user);
            if (action != null) command.put("actionId", action);
            return send(command.toString(), expected);
        }

        JsonNode send(String body, int expected) throws Exception {
            var request = request("/commands").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(expected, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path));
        }
    }
}
