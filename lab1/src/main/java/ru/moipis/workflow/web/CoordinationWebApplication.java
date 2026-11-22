package ru.moipis.workflow.web;

import io.javalin.Javalin;
import ru.moipis.workflow.engine.CoordinationScenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CoordinationWebApplication implements AutoCloseable {
    private static final int DEFAULT_PORT = 7072;
    private final Javalin server;
    private final AtomicBoolean closed = new AtomicBoolean();

    private CoordinationWebApplication(Javalin server) {
        this.server = server;
    }

    public static CoordinationWebApplication start(CoordinationScenario scenario, Path journal, int port)
            throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        var controller = new CoordinationWebController(scenario, scenario.open(journal));
        var server = WebServerFactory.create(WebServerFactory.jsonMapper(), DEFAULT_PORT, controller::register);
        try {
            server.start(port);
            return new CoordinationWebApplication(server);
        } catch (RuntimeException | Error failure) {
            try {
                server.stop();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        if (!closed.getAndSet(true)) server.stop();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 0 && (args.length != 2 || !"--port".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: CoordinationWebApplication [--port 7072]");
        }
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[1]);
        var folder = Files.createDirectories(Path.of("demo", "coordination-web"));
        var journal = Files.createTempDirectory(folder, "run-").resolve("journal.jsonl");
        try (var app = start(CoordinationScenario.demo(), journal, port)) {
            var stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    app.close();
                } finally {
                    stopped.countDown();
                }
            }, "coordination-web-shutdown"));
            System.out.println("Task 3-1-2: http://127.0.0.1:" + app.port());
            System.out.println("Journal: " + journal.toAbsolutePath());
            try {
                stopped.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
