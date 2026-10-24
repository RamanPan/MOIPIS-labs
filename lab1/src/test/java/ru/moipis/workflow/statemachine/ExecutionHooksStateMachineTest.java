package ru.moipis.workflow.statemachine;

import org.junit.jupiter.api.Test;
import ru.moipis.workflow.statemachine.event.Event;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionHooksStateMachineTest {
    @Test
    void committerRunsAfterHandlerButBeforeStatePublication() throws Exception {
        try (StateMachine machine = new DefaultStateMachine("start")) {
            machine.registerState("end");
            machine.registerTransition("start", "go", "end");
            AtomicInteger sequence = new AtomicInteger();
            machine.registerHandler("start", "go", event -> {
                assertEquals(1, sequence.getAndIncrement());
                return null;
            });
            machine.setExecutionHooks((event, state) -> {
                assertEquals(0, sequence.getAndIncrement());
                return event;
            }, (event, result) -> {
                assertEquals(2, sequence.getAndIncrement());
                assertEquals("start", machine.getCurrentState().id());
                assertEquals("end", result.currentStateId());
            });
            machine.start();
            machine.send(new Event("go", null, null)).get(5, TimeUnit.SECONDS);
            assertEquals("end", machine.getCurrentState().id());
            assertEquals(3, sequence.get());
        }
    }

    @Test
    void failedCommitDoesNotPublishStateAndNextEventCanRetry() throws Exception {
        try (StateMachine machine = new DefaultStateMachine("start")) {
            machine.registerState("end");
            machine.registerTransition("start", "go", "end");
            machine.setExecutionHooks((event, state) -> event,
                    (event, result) -> { throw new IllegalStateException("commit failed"); });
            machine.start();
            var first = machine.send(new Event("go", null, null));
            assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertEquals("start", machine.getCurrentState().id());
            machine.setExecutionHooks((event, state) -> event, (event, result) -> { });
            machine.send(new Event("go", null, null)).get(5, TimeUnit.SECONDS);
            assertEquals("end", machine.getCurrentState().id());
        }
    }

    @Test
    void hooksBelongToOneConfigurationSnapshotAndRegistrationDoesNotBlock() throws Exception {
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger oldCommits = new AtomicInteger();
        AtomicInteger newCommits = new AtomicInteger();
        try (StateMachine machine = new DefaultStateMachine("start")) {
            machine.registerHandler("start", "work", event -> null);
            machine.setExecutionHooks((event, state) -> {
                preparing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Preparation timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return event;
            }, (event, result) -> oldCommits.incrementAndGet());
            machine.start();
            var first = machine.send(new Event("work", null, null));
            try {
                assertTrue(preparing.await(5, TimeUnit.SECONDS));
                machine.setExecutionHooks((event, state) -> event, (event, result) -> newCommits.incrementAndGet());
                var second = machine.send(new Event("work", null, null));
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                assertEquals(1, oldCommits.get());
                assertEquals(1, newCommits.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void preparationCannotChangeEventTypeOrInvokeHandlerAfterRejection() throws Exception {
        try (StateMachine machine = new DefaultStateMachine("start")) {
            AtomicInteger calls = new AtomicInteger();
            machine.registerHandler("start", "work", event -> { calls.incrementAndGet(); return null; });
            machine.setExecutionHooks((event, state) -> new Event("other", null, null), (event, result) -> { });
            machine.start();
            var future = machine.send(new Event("work", null, null));
            var failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals(0, calls.get());
            assertEquals("start", machine.getCurrentState().id());
        }
    }
}
