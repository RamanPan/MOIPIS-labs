package ru.moipis.workflow.web;

import io.javalin.Javalin;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.engine.DefaultWorkflowEngine;
import ru.moipis.workflow.engine.WorkflowEngine;
import ru.moipis.workflow.repository.file.FileRepository;
import ru.moipis.workflow.statemachine.factory.DefaultStateMachineFactory;
import ru.moipis.workflow.template.TemplateCatalogService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one engine and one loopback HTTP server. Existing JSON stores are never reset. */
public final class WebApplication implements AutoCloseable {
    private final WorkflowEngine engine;
    private final Javalin server;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private WebApplication(WorkflowEngine engine, Javalin server) {
        this.engine = engine;
        this.server = server;
    }

    public static WebApplication start(Path storePath, int port) throws IOException {
        Objects.requireNonNull(storePath, "Store path");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        var repository = new FileRepository(storePath, Clock.systemUTC());
        var catalog = new CatalogService(repository.loadOrInitialize().classes());
        var templates = new TemplateCatalogService(catalog, repository);
        WorkflowEngine engine = new DefaultWorkflowEngine(catalog, templates, new DefaultConditionEvaluator(catalog),
                repository, new DefaultStateMachineFactory());
        Javalin server = null;
        try {
            server = WebServerFactory.create(engine, repository, catalog, templates);
            server.start(port);
            return new WebApplication(engine, server);
        } catch (RuntimeException | Error failure) {
            if (server != null) {
                try { server.stop(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            engine.close();
            throw failure;
        }
    }

    public int port() { return server.port(); }

    @Override public void close() {
        if (closed.getAndSet(true)) return;
        try {
            // Jetty stops accepting requests and gives active async requests a graceful stop window.
            server.stop();
        } finally {
            try { engine.close(); } finally { stopped.countDown(); }
        }
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = parseArguments(args, System.getenv("WORKFLOW_WEB_STORE"));
        WebApplication app = start(arguments.storePath(), arguments.port());
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "workflow-web-shutdown"));
        System.out.println("Workflow API: http://127.0.0.1:" + app.port() + "/api/objects");
        System.out.println("JSON store: " + arguments.storePath().toAbsolutePath().normalize());
        try {
            app.stopped.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            app.close();
        }
    }

    static Arguments parseArguments(String[] args, String environmentPath) {
        Path storePath = null;
        Integer port = null;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw usage();
            String value = args[i + 1];
            switch (args[i]) {
                case "--store" -> {
                    if (storePath != null || value.isBlank()) throw usage();
                    storePath = Path.of(value);
                }
                case "--port" -> {
                    if (port != null) throw usage();
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException invalid) {
                        throw usage();
                    }
                }
                default -> throw usage();
            }
        }
        if (storePath == null) {
            storePath = Path.of(environmentPath == null || environmentPath.isBlank() ? "demo/web/store.json" : environmentPath);
        }
        return new Arguments(storePath, port == null ? 7070 : port);
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: WebApplication [--store path/to/store.json] [--port N]");
    }

    record Arguments(Path storePath, int port) {
    }
}
