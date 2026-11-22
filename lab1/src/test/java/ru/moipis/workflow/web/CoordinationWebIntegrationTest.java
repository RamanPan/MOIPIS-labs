package ru.moipis.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.engine.CoordinationScenario;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CoordinationWebIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void exposesTheTwoProcessesAndCorrelatesTheEventWithItsCommand() throws Exception {
        Path journal = temporary.resolve("success.jsonl");
        try (var app = start(journal); var client = HttpClient.newHttpClient()) {
            var api = new Api(client, app.port());
            assertEquals("/coordination.html", api.get("/").headers().firstValue("Location").orElseThrow());
            assertEquals(200, api.get("/coordination.html").statusCode());
            assertTrue(api.state().path("processes").get(0).path("stateId").isNull());
            assertEquals(2, api.state().path("processes").size());

            api.command("start", "manager", "order-1", null, 200);
            api.command("start", "reviewer", "company-1", null, 200);
            assertEquals("CONDITION_NOT_MET", api.command("action", "manager", "order-1", "submit", 409)
                    .path("command").path("reason").asText());
            api.command("action", "reviewer", "company-1", "approve", 200);
            assertEquals("preparing", api.state().path("processes").get(0).path("stateId").asText());
            api.command("action", "manager", "order-1", "submit", 200);
            JsonNode result = api.command("action", "reviewer", "company-1", "revise", 200);
            JsonNode event = result.path("deliveries").get(0);
            assertEquals("customer_changed", event.path("eventName").asText());
            assertEquals(result.path("command").path("id"), event.path("parentId"));
            assertEquals("ready", event.path("before").asText());
            assertEquals("preparing", event.path("after").asText());
            JsonNode order = api.state().path("processes").get(0);
            assertEquals("preparing", order.path("stateId").asText());
            assertEquals("submit", order.path("lastActionId").asText());
            api.command("action", "reviewer", "company-1", "approve", 200);
            api.command("action", "manager", "order-1", "submit", 200);
            api.command("action", "manager", "order-1", "confirm", 200);
            JsonNode finalState = api.state();
            assertEquals("done", finalState.path("processes").get(0).path("stateId").asText());
            assertEquals(10, finalState.path("journal").size());
            assertEquals(Files.readString(journal), api.get("/api/coordination/journal/download").body());
        }
    }

    @Test
    void keepsSuccessfulSourceTransitionVisibleWhenDeliveryIsRejected() throws Exception {
        try (var app = start(temporary.resolve("rejection.jsonl")); var client = HttpClient.newHttpClient()) {
            var api = new Api(client, app.port());
            api.command("start", "reviewer", "company-1", null, 200);
            api.command("action", "reviewer", "company-1", "approve", 200);
            JsonNode result = api.command("action", "reviewer", "company-1", "revise", 200);
            assertEquals("SUCCESS", result.path("command").path("outcome").asText());
            assertEquals("PROCESS_NOT_STARTED", result.path("deliveries").get(0).path("reason").asText());
            assertEquals(result.path("command").path("id"), result.path("deliveries").get(0).path("parentId"));
            JsonNode state = api.state();
            assertEquals("checking", state.path("processes").get(1).path("stateId").asText());
            assertTrue(state.path("processes").get(0).path("stateId").isNull());
            assertEquals(4, state.path("journal").size());
            api.command("event", "reviewer", "company-1", "customer_changed", 400);
            assertEquals(4, api.state().path("journal").size());
        }
    }

    private static CoordinationWebApplication start(Path journal) throws Exception {
        return CoordinationWebApplication.start(CoordinationScenario.demo(), journal, 0);
    }

    private record Api(HttpClient client, int port) {
        JsonNode state() throws Exception {
            var response = get("/api/coordination");
            assertEquals(200, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        JsonNode command(String type, String user, String object, String action, int status) throws Exception {
            var body = JSON.createObjectNode().put("type", type).put("userId", user).put("objectId", object);
            if (action != null) body.put("actionId", action);
            var request = request("/api/coordination/commands").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(status, response.statusCode(), response.body());
            return JSON.readTree(response.body());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        }
    }
}
