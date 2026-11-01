package ru.moipis.workflow.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WebConcurrencyTest {
    @TempDir Path directory;

    @Test void httpSuccessWaitsForTheAtomicFileReplacement() throws Exception {
        var gate = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new WebTestFixture(directory.resolve("store.json"), (source, target) -> {
            if (gate.get()) {
                entered.countDown();
                await(release);
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        })) {
            byte[] before = Files.readAllBytes(directory.resolve("store.json"));
            gate.set(true);
            var pending = fixture.sendAsync("POST", "/api/objects/demo-office-valid/actions", """
                    {"userId":"user_manager","actionId":"complete"}
                    """);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(pending.isDone(), "HTTP must wait for commit");
                assertArrayEquals(before, Files.readAllBytes(directory.resolve("store.json")));
            } finally { release.countDown(); }
            assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals("composition_approval", fixture.engine.getObject("demo-office-valid").stateId());
            assertEquals(2, fixture.engine.getHistory("demo-office-valid").size());
        }
    }

    @Test void simultaneousActionsDoNotBypassTheObjectsWorkerAndAnotherObjectCanProceed() throws Exception {
        try (var fixture = new WebTestFixture(directory.resolve("store.json"))) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var once = new AtomicBoolean(true);
            fixture.engine.registerHandler("complete", event -> {
                if (once.getAndSet(false)) { entered.countDown(); await(release); }
                return null;
            });
            String root = "/api/objects/demo-office-valid/actions";
            var first = fixture.sendAsync("POST", root, """
                    {"userId":"user_manager","actionId":"complete"}
                    """);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = fixture.sendAsync("POST", root, """
                        {"userId":"user_manager","actionId":"complete"}
                        """);
                var other = fixture.send("POST", "/api/objects/demo-standard-valid/actions", """
                        {"userId":"user_manager","actionId":"complete"}
                        """);
                assertEquals(200, other.statusCode(), other.body());
                assertFalse(first.isDone());
                release.countDown();
                assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
                var rejected = second.get(5, TimeUnit.SECONDS);
                assertEquals(409, rejected.statusCode(), rejected.body());
                assertEquals("INVALID_ACTION", fixture.json(rejected).path("code").asText());
                assertEquals(2, fixture.engine.getHistory("demo-office-valid").size());
            } finally { release.countDown(); }
        }
    }

    @Test void storageFailureReturns500WithoutPublishingChangesAndTheNextCommandWorks() throws Exception {
        var fail = new AtomicBoolean();
        try (var fixture = new WebTestFixture(directory.resolve("store.json"), (source, target) -> {
            if (fail.get()) throw new IOException("Private storage path must not reach HTTP");
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        })) {
            var before = fixture.repository.load();
            byte[] bytes = Files.readAllBytes(directory.resolve("store.json"));
            fail.set(true);
            var response = fixture.send("POST", "/api/objects/demo-office-valid/actions", """
                    {"userId":"user_manager","actionId":"complete"}
                    """);
            assertEquals(500, response.statusCode(), response.body());
            assertEquals("PERSISTENCE_ERROR", fixture.json(response).path("code").asText());
            assertFalse(response.body().contains("Private storage path"));
            assertEquals(before, fixture.repository.load());
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("store.json")));
            fail.set(false);
            assertEquals(200, fixture.send("POST", "/api/objects/demo-office-valid/actions", """
                    {"userId":"user_manager","actionId":"complete"}
                    """).statusCode());
        }
    }

    @Test void gracefulServerStopAllowsAnAcceptedHttpCommandToFinish() throws Exception {
        try (var fixture = new WebTestFixture(directory.resolve("store.json"));
             var closer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var stopping = new CountDownLatch(1);
            fixture.engine.registerHandler("complete", event -> { entered.countDown(); await(release); return null; });
            var pending = fixture.sendAsync("POST", "/api/objects/demo-office-valid/actions", """
                    {"userId":"user_manager","actionId":"complete"}
                    """);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var stop = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    stopping.countDown();
                    fixture.server.stop();
                    fixture.engine.close();
                }, closer);
                assertTrue(stopping.await(5, TimeUnit.SECONDS));
                release.countDown();
                assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode());
                stop.get(5, TimeUnit.SECONDS);
                assertEquals("composition_approval", fixture.engine.getObject("demo-office-valid").stateId());
                assertEquals(2, fixture.engine.getHistory("demo-office-valid").size());
            } finally { release.countDown(); }
        }
    }

    static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
