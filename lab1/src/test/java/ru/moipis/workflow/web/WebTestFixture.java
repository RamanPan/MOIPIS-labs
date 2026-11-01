package ru.moipis.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.engine.DefaultWorkflowEngine;
import ru.moipis.workflow.repository.file.AtomicFileMover;
import ru.moipis.workflow.repository.file.FileRepository;
import ru.moipis.workflow.statemachine.factory.DefaultStateMachineFactory;
import ru.moipis.workflow.template.TemplateCatalogService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

final class WebTestFixture implements AutoCloseable {
    final ObjectMapper mapper = new ObjectMapper();
    final FileRepository repository;
    final DefaultWorkflowEngine engine;
    final TemplateCatalogService templates;
    final Javalin server;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    WebTestFixture(Path path) throws Exception {
        this(path, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    WebTestFixture(Path path, AtomicFileMover mover) throws Exception {
        repository = new FileRepository(path, Clock.systemUTC(), mover);
        var catalog = new CatalogService(repository.loadOrInitialize().classes());
        templates = new TemplateCatalogService(catalog, repository);
        engine = new DefaultWorkflowEngine(catalog, templates, new DefaultConditionEvaluator(catalog),
                repository, new DefaultStateMachineFactory());
        server = WebServerFactory.create(engine, repository, catalog, templates).start(0);
    }

    HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    CompletableFuture<HttpResponse<String>> sendAsync(String method, String path, String body) {
        return client.sendAsync(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }

    @Override public void close() {
        try { server.stop(); } finally {
            try { engine.close(); } finally { client.close(); }
        }
    }
}
