package ru.moipis.workflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.repository.file.FileRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WebPersistenceTest {
    @TempDir Path directory;

    @Test void restartingTheApplicationPreservesValuesHistoryAndTheSavedState() throws Exception {
        Path store = directory.resolve("persistent.json");
        try (var client = HttpClient.newHttpClient()) {
            try (var app = WebApplication.start(store, 0)) {
                assertEquals(200, request(client, app.port(), "PATCH", "/api/objects/demo-office-valid/parameters/quantity",
                        "{\"userId\":\"user_manager\",\"value\":321}").statusCode());
                assertEquals(200, request(client, app.port(), "POST", "/api/objects/demo-office-valid/actions",
                        "{\"userId\":\"user_manager\",\"actionId\":\"complete\"}").statusCode());
            }
            byte[] saved = Files.readAllBytes(store);
            try (var app = WebApplication.start(store, 0)) {
                assertArrayEquals(saved, Files.readAllBytes(store), "Startup must not rewrite existing user data");
                var response = request(client, app.port(), "GET", "/api/objects/demo-office-valid", null);
                assertEquals(200, response.statusCode(), response.body());
                var json = new ObjectMapper().readTree(response.body());
                assertEquals("composition_approval", json.path("stateId").asText());
                assertEquals(321, json.path("parameters").path("quantity").intValue());
                assertEquals(200, request(client, app.port(), "POST", "/api/objects/demo-office-valid/actions",
                        "{\"userId\":\"user_manager\",\"actionId\":\"approve\"}").statusCode());
            }
        }
        var repository = new FileRepository(store, Clock.systemUTC());
        var object = repository.load().objects().stream().filter(o -> o.id().equals("demo-office-valid")).findFirst().orElseThrow();
        assertEquals("price_approval", object.stateId());
        assertEquals(4, repository.load().history().stream().filter(h -> h.objectId().equals(object.id())).count());
    }

    @Test void corruptedExistingStoreIsNotResetOnStartup() throws Exception {
        Path store = directory.resolve("corrupted.json");
        Files.writeString(store, "user-owned invalid json");
        assertThrows(Exception.class, () -> WebApplication.start(store, 0));
        assertEquals("user-owned invalid json", Files.readString(store));
    }

    @Test void occupiedPortDoesNotRewriteAnExistingStore() throws Exception {
        Path store = directory.resolve("occupied-port.json");
        new FileRepository(store, Clock.systemUTC()).loadOrInitialize();
        byte[] before = Files.readAllBytes(store);
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            assertThrows(RuntimeException.class, () -> WebApplication.start(store, socket.getLocalPort()));
        }
        assertArrayEquals(before, Files.readAllBytes(store));
    }

    static HttpResponse<String> request(HttpClient client, int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
