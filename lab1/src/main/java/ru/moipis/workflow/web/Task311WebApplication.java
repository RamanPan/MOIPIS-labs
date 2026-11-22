package ru.moipis.workflow.web;

import io.javalin.Javalin;
import ru.moipis.workflow.engine.CoordinationScenario;
import ru.moipis.workflow.engine.Task311Commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs a single in-memory 3-1-1 session on the loopback HTTP interface. */
public final class Task311WebApplication implements AutoCloseable {
    private static final int DEFAULT_PORT = 7071;
    private final Javalin server;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private Task311WebApplication(Javalin server) {
        this.server = server;
    }

    public static Task311WebApplication start(CoordinationScenario scenario, Path journal, int port) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        var controller = new Task311ApiController(scenario.openTask311(journal));
        var server = WebServerFactory.create(WebServerFactory.jsonMapper(), DEFAULT_PORT, controller::register);
        try {
            server.start(port);
            return new Task311WebApplication(server);
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
        if (closed.getAndSet(true)) return;
        try {
            server.stop();
        } finally {
            stopped.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        Path config = null;
        int port = DEFAULT_PORT;
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 == args.length || args[index + 1].isBlank()) throw usage();
            switch (args[index]) {
                case "--config" -> config = Path.of(args[index + 1]);
                case "--port" -> port = Integer.parseInt(args[index + 1]);
                default -> throw usage();
            }
        }
        CoordinationScenario scenario;
        if (config == null) {
            scenario = CoordinationScenario.task311();
        } else {
            try (var input = Files.newInputStream(config)) {
                scenario = CoordinationScenario.read(input);
            }
        }
        var journal = Task311Commands.newJournalPath();
        try (var app = start(scenario, journal, port)) {
            Runtime.getRuntime().addShutdownHook(new Thread(app::close, "task-3-1-1-web-shutdown"));
            System.out.println("Task 3-1-1: http://127.0.0.1:" + app.port());
            System.out.println("Journal: " + journal.toAbsolutePath());
            try {
                app.stopped.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: Task311WebApplication [--config path/to/config.json] [--port 7071]");
    }
}
