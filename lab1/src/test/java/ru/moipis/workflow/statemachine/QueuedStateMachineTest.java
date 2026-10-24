package ru.moipis.workflow.statemachine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuedStateMachineTest {

    private final List<StateMachine> machines = new ArrayList<>();

    @AfterEach
    void stopMachines() {
        machines.forEach(StateMachine::stop);
    }

    @Test
    void processesMessagesInFifoOrderOnOneWorkerInsteadOfTheSenderThread() throws Exception {
        StateMachine machine = machine();
        List<Integer> observed = new ArrayList<>();
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        Thread sender = Thread.currentThread();
        machine.registerHandler("start", "record", event -> {
            observed.add(event.payload().intValue());
            workers.add(Thread.currentThread());
            return event.payload();
        });
        machine.start();
        List<CompletableFuture<EventResult>> futures = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            futures.add(machine.send(event("record", IntNode.valueOf(index))));
        }
        for (int index = 0; index < futures.size(); index++) {
            assertEquals(index, result(futures.get(index)).handlerResult().intValue());
        }

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19), observed);
        assertEquals(1, workers.size());
        Thread worker = workers.iterator().next();
        assertNotEquals(sender, worker);
        machine.stop();
        assertFalse(worker.isAlive());
    }

    @Test
    void processesMultipleTransitionsAndHandlerOnlyEvents() throws Exception {
        StateMachine machine = machine();
        machine.registerState("middle");
        machine.registerState("end");
        machine.registerTransition("start", "advance", "middle");
        machine.registerTransition("middle", "advance", "end");
        machine.registerHandler("middle", "inspect", event -> IntNode.valueOf(7));
        machine.start();

        CompletableFuture<EventResult> first = machine.send(event("advance"));
        CompletableFuture<EventResult> middle = machine.send(event("inspect"));
        CompletableFuture<EventResult> last = machine.send(event("advance"));

        assertEquals("middle", result(first).currentStateId());
        assertEquals("middle", result(middle).currentStateId());
        assertEquals(IntNode.valueOf(7), result(middle).handlerResult());
        assertEquals("end", result(last).currentStateId());
        assertEquals("end", machine.currentStateId());
    }

    @Test
    void failureLeavesStateUnchangedAndWorkerProcessesLaterMessages() throws Exception {
        StateMachine machine = machine();
        machine.registerState("end");
        machine.registerTransition("start", "fail", "end");
        machine.registerTransition("start", "recover", "end");
        IllegalArgumentException failure = new IllegalArgumentException("Handler failed");
        machine.registerHandler("start", "fail", event -> { throw failure; });
        machine.start();

        CompletableFuture<EventResult> failed = machine.send(event("fail"));
        CompletableFuture<EventResult> invalid = machine.send(event("missing"));
        CompletableFuture<EventResult> recovered = machine.send(event("recover"));

        assertSame(failure, failure(failed));
        assertInstanceOf(IllegalArgumentException.class, failure(invalid));
        assertEquals("start", result(recovered).previousStateId());
        assertEquals("end", machine.currentStateId());
    }

    @Test
    void keepsStateUnchangedWhileHandlerIsRunning() throws Exception {
        StateMachine machine = machine();
        machine.registerState("end");
        machine.registerTransition("start", "advance", "end");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        machine.registerHandler("start", "advance", event -> {
            entered.countDown();
            await(release);
            return null;
        });
        machine.start();

        CompletableFuture<EventResult> processing = machine.send(event("advance"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("start", machine.currentStateId());
            assertFalse(processing.isDone());
        } finally {
            release.countDown();
        }
        assertEquals("end", result(processing).currentStateId());
    }

    @Test
    void configurationChangesAffectTheNextQueuedMessageButNotTheRunningOne() throws Exception {
        StateMachine machine = machine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        machine.registerHandler("start", "advance", event -> {
            entered.countDown();
            await(release);
            return null;
        });
        machine.start();

        CompletableFuture<EventResult> running = machine.send(event("advance"));
        CompletableFuture<EventResult> queued = machine.send(event("advance"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            machine.registerState("end");
            machine.registerTransition("start", "advance", "end");
        } finally {
            release.countDown();
        }

        assertEquals("start", result(running).currentStateId());
        assertEquals("end", result(queued).currentStateId());
    }

    @Test
    void stopDrainsTheQueueAndCompletesEveryAcceptedFuture() throws Exception {
        StateMachine machine = machine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        machine.registerHandler("start", "work", event -> {
            entered.countDown();
            await(release);
            return IntNode.valueOf(count.incrementAndGet());
        });
        machine.start();
        List<CompletableFuture<EventResult>> accepted = new ArrayList<>();
        accepted.add(machine.send(event("work")));
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int index = 0; index < 10; index++) {
                    accepted.add(machine.send(event("work")));
                }
                var stopping = executor.submit(machine::stop);
                release.countDown();
                stopping.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertEquals(11, count.get());
        for (CompletableFuture<EventResult> future : accepted) {
            assertTrue(future.isDone());
            result(future);
        }
        assertEquals(StateMachineLifecycle.STOPPED, machine.getLifecycle());
        assertInstanceOf(IllegalStateException.class, failure(machine.send(event("work"))));
        machine.stop();
        assertThrows(IllegalStateException.class, machine::start);
    }

    @Test
    void rejectsSendsBeforeStartAndAfterStoppingWithoutPendingFutures() throws Exception {
        StateMachine machine = machine();
        assertEquals(StateMachineLifecycle.STOPPED, machine.getLifecycle());
        assertInstanceOf(IllegalStateException.class, failure(machine.send(event("event"))));
        machine.start();
        assertEquals(StateMachineLifecycle.STARTED, machine.getLifecycle());
        machine.stop();

        assertInstanceOf(IllegalStateException.class, failure(machine.send(event("event"))));
        machine.stop();
    }

    @Test
    void stopBeforeStartIsSafeAndTerminal() throws Exception {
        StateMachine machine = machine();
        machine.stop();
        machine.stop();

        assertEquals(StateMachineLifecycle.STOPPED, machine.getLifecycle());
        assertThrows(IllegalStateException.class, machine::start);
        assertInstanceOf(IllegalStateException.class, failure(machine.send(event("event"))));
    }

    @Test
    void repeatedConcurrentStartsUseOnlyOneWorker() throws Exception {
        StateMachine machine = machine();
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        machine.registerHandler("start", "work", event -> {
            workers.add(Thread.currentThread());
            machine.start();
            return null;
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<CompletableFuture<Void>> starts = new ArrayList<>();
            CountDownLatch gate = new CountDownLatch(1);
            for (int index = 0; index < 8; index++) {
                starts.add(CompletableFuture.runAsync(() -> { await(gate); machine.start(); }, executor));
            }
            gate.countDown();
            CompletableFuture.allOf(starts.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        }
        List<CompletableFuture<EventResult>> messages = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            messages.add(machine.send(event("work")));
        }
        for (CompletableFuture<EventResult> message : messages) {
            result(message);
        }
        assertEquals(1, workers.size());
    }

    @Test
    void sendRacingWithStopIsEitherProcessedOrRejectedAndNeverHangs() throws Exception {
        StateMachine machine = machine();
        AtomicInteger processed = new AtomicInteger();
        machine.registerHandler("start", "work", event -> IntNode.valueOf(processed.incrementAndGet()));
        machine.start();
        CountDownLatch gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<CompletableFuture<List<CompletableFuture<EventResult>>>> producers = new ArrayList<>();
            for (int producer = 0; producer < 3; producer++) {
                producers.add(CompletableFuture.supplyAsync(() -> {
                    await(gate);
                    List<CompletableFuture<EventResult>> sends = new ArrayList<>();
                    for (int index = 0; index < 20; index++) {
                        sends.add(machine.send(event("work")));
                    }
                    return sends;
                }, executor));
            }
            var stopping = CompletableFuture.runAsync(() -> { await(gate); machine.stop(); }, executor);
            gate.countDown();
            stopping.get(5, TimeUnit.SECONDS);
            int successful = 0;
            for (var producer : producers) {
                for (CompletableFuture<EventResult> future : producer.get(5, TimeUnit.SECONDS)) {
                    try {
                        result(future);
                        successful++;
                    } catch (ExecutionException exception) {
                        assertInstanceOf(IllegalStateException.class, exception.getCause());
                    }
                    assertTrue(future.isDone());
                }
            }
            assertEquals(successful, processed.get());
        }
    }

    @Test
    void stopFromACompletionCallbackDoesNotJoinItsOwnWorkerAndRejectsNewSends() throws Exception {
        StateMachine machine = machine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        CountDownLatch finishCallback = new CountDownLatch(1);
        machine.registerHandler("start", "work", event -> { entered.countDown(); await(release); return null; });
        machine.start();
        CompletableFuture<EventResult> first = machine.send(event("work"));
        CompletableFuture<EventResult> second = machine.send(event("work"));
        CompletableFuture<Void> callback = first.thenRun(() -> {
            machine.stop();
            stopping.countDown();
            await(finishCallback);
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(stopping.await(5, TimeUnit.SECONDS));
            assertEquals(StateMachineLifecycle.STOPPING, machine.getLifecycle());
            assertInstanceOf(IllegalStateException.class, failure(machine.send(event("work"))));
        } finally {
            release.countDown();
            finishCallback.countDown();
        }
        callback.get(5, TimeUnit.SECONDS);
        result(second);
        machine.stop();
        assertEquals(StateMachineLifecycle.STOPPED, machine.getLifecycle());
    }

    @Test
    void aCancelledFutureDoesNotDiscardAnAcceptedEvent() throws Exception {
        StateMachine machine = machine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        machine.registerHandler("start", "work", event -> {
            entered.countDown();
            await(release);
            processed.incrementAndGet();
            return null;
        });
        machine.start();
        CompletableFuture<EventResult> first = machine.send(event("work"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<EventResult> cancelled = machine.send(event("work"));
            assertTrue(cancelled.cancel(true));
            release.countDown();
            machine.stop();
            assertTrue(cancelled.isDone());
        } finally {
            release.countDown();
        }
        result(first);
        assertEquals(2, processed.get());
    }

    @Test
    void snapshotsPayloadWhenTheEventIsAccepted() throws Exception {
        StateMachine machine = machine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        machine.registerHandler("start", "block", event -> { entered.countDown(); await(release); return null; });
        machine.registerHandler("start", "echo", Event::payload);
        machine.start();
        CompletableFuture<EventResult> blocker = machine.send(event("block"));
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("number", 1);
        CompletableFuture<EventResult> echo;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            echo = machine.send(event("echo", payload));
            payload.put("number", 2);
        } finally {
            release.countDown();
        }
        result(blocker);
        assertEquals(1, result(echo).handlerResult().get("number").intValue());
    }

    @Test
    void unexpectedWorkerInterruptionCompletesPendingFuturesExceptionally() throws Exception {
        StateMachine machine = machine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        machine.registerHandler("start", "interrupt", event -> {
            entered.countDown();
            await(release);
            Thread.currentThread().interrupt();
            return null;
        });
        machine.registerHandler("start", "work", event -> null);
        machine.start();
        CompletableFuture<EventResult> interrupted = machine.send(event("interrupt"));
        CompletableFuture<EventResult> pending;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pending = machine.send(event("work"));
        } finally {
            release.countDown();
        }
        result(interrupted);
        assertInstanceOf(IllegalStateException.class, failure(pending));
        machine.stop();
        assertEquals(StateMachineLifecycle.STOPPED, machine.getLifecycle());
    }

    @Test
    void stoppingFromAnInterruptedCallerStillDrainsAndRestoresTheInterruptFlag() throws Exception {
        StateMachine machine = machine();
        machine.registerHandler("start", "work", event -> null);
        machine.start();
        CompletableFuture<EventResult> accepted = machine.send(event("work"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            boolean interrupted = executor.submit(() -> {
                Thread.currentThread().interrupt();
                machine.stop();
                return Thread.currentThread().isInterrupted();
            }).get(5, TimeUnit.SECONDS);
            assertTrue(interrupted);
        }
        result(accepted);
        assertEquals(StateMachineLifecycle.STOPPED, machine.getLifecycle());
    }

    private StateMachine machine() {
        StateMachine machine = new DefaultStateMachine("start");
        machines.add(machine);
        return machine;
    }

    private static Event event(String type) {
        return event(type, null);
    }

    private static Event event(String type, JsonNode payload) {
        return new Event(type, payload, null);
    }

    private static EventResult result(CompletableFuture<EventResult> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletableFuture<EventResult> future) {
        return assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
