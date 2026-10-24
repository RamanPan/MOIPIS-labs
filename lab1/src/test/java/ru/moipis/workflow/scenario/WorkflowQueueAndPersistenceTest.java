package ru.moipis.workflow.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.DefaultStateMachine;
import ru.moipis.workflow.statemachine.StateMachine;
import ru.moipis.workflow.statemachine.StateMachineLifecycle;
import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.exception.AmbiguousTransitionException;
import ru.moipis.workflow.template.model.ProcessTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static ru.moipis.workflow.engine.exception.WorkflowError.*;
import static ru.moipis.workflow.scenario.WorkflowFixture.await;
import static ru.moipis.workflow.scenario.WorkflowFixture.result;

@Timeout(20)
class WorkflowQueueAndPersistenceTest {
    @TempDir Path directory;
    private WorkflowFixture fixture;

    @BeforeEach
    void setup() throws Exception {
        fixture = new WorkflowFixture(directory);
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void fifoUsesOneWorkerAndALaterRegisteredHandlerForTheNextMessage() throws Exception {
        BusinessObject object = fixture.create("standard_order");
        StoreData before = fixture.repository.load();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        List<Thread> threads = new CopyOnWriteArrayList<>();
        fixture.engine.registerHandler("complete", event -> {
            threads.add(Thread.currentThread());
            firstEntered.countDown();
            await(firstRelease);
            return TextNode.valueOf("first");
        });
        try {
            var first = fixture.sendAction("user_manager", object.id(), "complete");
            await(firstEntered);
            var second = fixture.sendAction("user_manager", object.id(), "approve");
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            fixture.unchanged(before);
            fixture.engine.registerHandler("approve", event -> {
                threads.add(Thread.currentThread());
                secondEntered.countDown();
                await(secondRelease);
                return TextNode.valueOf("registered-later");
            });
            firstRelease.countDown();
            await(secondEntered);
            var firstResult = result(first);
            assertEquals("first", firstResult.handlerResult().asText());
            fixture.verifyAction(before, "user_manager", object.id(), "complete", "composition_approval", firstResult);
            StoreData afterFirst = fixture.repository.load();
            secondRelease.countDown();
            var secondResult = result(second);
            assertEquals("registered-later", secondResult.handlerResult().asText());
            fixture.verifyAction(afterFirst, "user_manager", object.id(), "approve", "price_approval", secondResult);
            assertEquals(2, threads.size());
            assertSame(threads.getFirst(), threads.getLast());
            assertNotSame(Thread.currentThread(), threads.getFirst());
            assertTrue(first.isDone() && second.isDone());
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
        }
    }

    @Test
    void handlerFailureLeavesNoAuditAndTheSameWorkerProcessesTheNextEvent() throws Exception {
        BusinessObject object = fixture.create("standard_order");
        StoreData before = fixture.repository.load();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        fixture.engine.registerHandler("complete", event -> {
            threads.add(Thread.currentThread());
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                await(firstRelease);
                throw new IllegalStateException("Injected handler failure");
            }
            secondEntered.countDown();
            await(secondRelease);
            return null;
        });
        try {
            var failed = fixture.sendAction("user_manager", object.id(), "complete");
            await(firstEntered);
            var accepted = fixture.sendAction("user_manager", object.id(), "complete");
            firstRelease.countDown();
            await(secondEntered);
            var failure = WorkflowFixture.failure(HANDLER_ERROR, failed);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            fixture.unchanged(before);
            secondRelease.countDown();
            fixture.verifyAction(before, "user_manager", object.id(), "complete", "composition_approval", result(accepted));
            assertTrue(failed.isCompletedExceptionally());
            assertTrue(accepted.isDone());
            assertSame(threads.getFirst(), threads.getLast());
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
        }
    }

    @Test
    void blockedObjectDoesNotBlockAnotherObjectsWorkerOrState() throws Exception {
        var firstParameters = fixture.parameters("standard_order");
        firstParameters.put("order_number", TextNode.valueOf("FIRST"));
        BusinessObject first = fixture.create("user_manager", "standard_order", firstParameters);
        BusinessObject second = fixture.create("standard_order");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        fixture.engine.registerHandler("complete", event -> {
            if (event.context().parameters().get("order_number").asText().equals("FIRST")) {
                firstThread.set(Thread.currentThread());
                entered.countDown();
                await(release);
            } else {
                secondThread.set(Thread.currentThread());
            }
            return null;
        });
        try {
            var pending = fixture.sendAction("user_manager", first.id(), "complete");
            await(entered);
            fixture.action("user_manager", second.id(), "complete", "composition_approval");
            assertEquals(first, fixture.engine.getObject(first.id()));
            assertEquals(1, fixture.engine.getHistory(first.id()).size());
            assertFalse(pending.isDone());
            StoreData beforeFirstCommit = fixture.repository.load();
            release.countDown();
            fixture.verifyAction(beforeFirstCommit, "user_manager", first.id(), "complete", "composition_approval", result(pending));
            assertNotNull(firstThread.get());
            assertNotNull(secondThread.get());
            assertNotSame(firstThread.get(), secondThread.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingDrainsAcceptedEventsStopsWorkersAndLeavesNoPendingFutures() throws Exception {
        BusinessObject object = fixture.create("standard_order");
        StoreData before = fixture.repository.load();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        fixture.engine.registerHandler("complete", event -> { firstEntered.countDown(); await(firstRelease); return null; });
        fixture.engine.registerHandler("approve", event -> { secondEntered.countDown(); await(secondRelease); return null; });
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var first = fixture.sendAction("user_manager", object.id(), "complete");
                await(firstEntered);
                var second = fixture.sendAction("user_manager", object.id(), "approve");
                var closing = CompletableFuture.runAsync(fixture.engine::close, executor);
                firstRelease.countDown();
                await(secondEntered);
                fixture.verifyAction(before, "user_manager", object.id(), "complete", "composition_approval", result(first));
                assertFalse(closing.isDone());
                StoreData afterFirst = fixture.repository.load();
                secondRelease.countDown();
                var secondResult = result(second);
                result(closing);
                fixture.verifyAction(afterFirst, "user_manager", object.id(), "approve", "price_approval", secondResult);
                assertTrue(first.isDone() && second.isDone() && closing.isDone());
                assertTrue(fixture.machines.stream().allMatch(machine -> machine.getLifecycle() == StateMachineLifecycle.STOPPED));
                fixture.reject(ENGINE_CLOSED, () -> fixture.sendAction("user_manager", object.id(), "approve"));
                fixture.reject(ENGINE_CLOSED, () -> fixture.engine.createObject("user_manager", "standard_order", fixture.parameters("standard_order")));
                fixture.engine.close();
            } finally {
                firstRelease.countDown();
                secondRelease.countDown();
            }
        }
    }

    @Test
    void runningMachineAcceptsANewStateHandlerAndTransitionWithoutLockingTheHandler() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (StateMachine machine = new DefaultStateMachine("base"); var executor = Executors.newSingleThreadExecutor()) {
            machine.registerHandler("base", "hold", event -> { entered.countDown(); await(release); return null; });
            machine.start();
            try {
                var first = machine.send(new Event("hold", null, null));
                await(entered);
                var registration = CompletableFuture.runAsync(() -> {
                    machine.registerState("added");
                    machine.registerHandler("base", "advance", event -> TextNode.valueOf("dynamic"));
                    machine.registerTransition("base", "advance", "added");
                }, executor);
                result(registration);
                assertEquals("base", machine.getCurrentState().id());
                var second = machine.send(new Event("advance", null, null));
                release.countDown();
                assertEquals("base", result(first).currentStateId());
                var transition = result(second);
                assertEquals("base", transition.previousStateId());
                assertEquals("added", transition.currentStateId());
                assertEquals("dynamic", transition.handlerResult().asText());
                machine.stop();
                machine.stop();
                assertEquals(StateMachineLifecycle.STOPPED, machine.getLifecycle());
                assertTrue(first.isDone() && second.isDone());
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACTION", "UPDATE_PARAMETER"})
    void failedAtomicWritePreservesTheFileObjectHistoryAndMachineAndCanBeRetried(String operation) throws Exception {
        BusinessObject object = fixture.create("standard_order");
        fixture.failWrites.set(true);
        try {
            var failure = switch (operation) {
                case "CREATE" -> fixture.reject(PERSISTENCE_ERROR,
                        () -> fixture.engine.createObject("user_manager", "standard_order", fixture.parameters("standard_order")));
                case "ACTION" -> fixture.reject(PERSISTENCE_ERROR, () -> fixture.sendAction("user_manager", object.id(), "complete"));
                case "UPDATE_PARAMETER" -> fixture.reject(PERSISTENCE_ERROR,
                        () -> fixture.sendUpdate("user_manager", object.id(), "quantity", IntNode.valueOf(123)));
                default -> throw new AssertionError(operation);
            };
            assertInstanceOf(IOException.class, failure.getCause());
        } finally {
            fixture.failWrites.set(false);
        }
        switch (operation) {
            case "CREATE" -> fixture.create("standard_order");
            case "ACTION" -> fixture.action("user_manager", object.id(), "complete", "composition_approval");
            case "UPDATE_PARAMETER" -> fixture.update("user_manager", object.id(), "quantity", IntNode.valueOf(123));
            default -> throw new AssertionError(operation);
        }
    }

    @Test
    void machinePublishesTheStateOnlyAfterAtomicReplacementFinishes() throws Exception {
        BusinessObject object = fixture.create("standard_order");
        StoreData before = fixture.repository.load();
        byte[] bytes = Files.readAllBytes(fixture.path);
        fixture.writeGate = new WorkflowFixture.WriteGate();
        try {
            var pending = fixture.sendAction("user_manager", object.id(), "complete");
            await(fixture.writeGate.entered);
            assertFalse(pending.isDone());
            assertEquals("registration", fixture.objectMachines.get(object.id()).getCurrentState().id());
            assertArrayEquals(bytes, Files.readAllBytes(fixture.path));
            fixture.writeGate.release.countDown();
            fixture.verifyAction(before, "user_manager", object.id(), "complete", "composition_approval", result(pending));
        } finally {
            fixture.writeGate.release.countDown();
        }
    }

    @Test
    void copiedTemplateSaveFailureIsAtomicAndSuccessfulCopySurvivesRestart() throws Exception {
        BusinessObject object = fixture.create("standard_order");
        StoreData before = fixture.repository.load();
        byte[] bytes = Files.readAllBytes(fixture.path);
        fixture.failWrites.set(true);
        try {
            assertThrows(IOException.class,
                    () -> fixture.templates.copyTemplate(WorkflowFixture.TEMPLATE_ID, "consumables_order", "copy"));
            assertArrayEquals(bytes, Files.readAllBytes(fixture.path));
            fixture.unchanged(before);
        } finally {
            fixture.failWrites.set(false);
        }
        ProcessTemplate original = fixture.templates.getTemplate(WorkflowFixture.TEMPLATE_ID);
        ProcessTemplate copied = fixture.templates.copyTemplate(WorkflowFixture.TEMPLATE_ID, "consumables_order", "copy");
        assertEquals(original, fixture.templates.getTemplate(WorkflowFixture.TEMPLATE_ID));
        assertEquals(before.objects(), fixture.repository.load().objects());
        assertEquals(before.history(), fixture.repository.load().history());
        fixture.restart();
        assertEquals(copied, fixture.templates.getTemplate("copy"));
        assertEquals("copy", fixture.templates.resolveTemplate("consumables_order").id());
        assertEquals(object, fixture.engine.getObject(object.id()));
        assertEquals(before.history(), fixture.engine.getHistory(object.id()));
    }

    @Test
    void restartRetainsUpdatedValuesAuditAndTheSavedStateAndExecutionContinues() throws Exception {
        BusinessObject object = fixture.create("office_order");
        fixture.update("user_manager", object.id(), "quantity", IntNode.valueOf(321));
        fixture.moveTo(object.id(), "payment_approval");
        StoreData persisted = fixture.repository.load();
        fixture.restart();
        fixture.unchanged(persisted);
        assertEquals(321, fixture.engine.getObject(object.id()).parameters().get("quantity").intValue());
        fixture.action("user_finance", object.id(), "approve", "execution_decision");
        fixture.action("user_head", object.id(), "execute", "execution");
        fixture.action("user_executor", object.id(), "complete", "completed");
    }

    @Test
    void twoMatchingTransitionsAreATemplateErrorRatherThanAFirstMatch() throws Exception {
        ProcessTemplate ambiguous;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/templates/ambiguous-workflow-template.json"))) {
            ambiguous = new ObjectMapper().readValue(input, ProcessTemplate.class);
        }
        fixture.repository.update(store -> {
            var templates = new ArrayList<>(store.templates());
            templates.add(ambiguous);
            return new StoreData(store.schemaVersion(), store.classes(), store.roles(), store.users(), templates, store.objects(), store.history());
        });
        fixture.restart();
        BusinessObject object = fixture.create("office_order");
        AtomicInteger calls = new AtomicInteger();
        fixture.engine.registerHandler("choose", event -> { calls.incrementAndGet(); return null; });
        var error = fixture.reject(TEMPLATE_ERROR, () -> fixture.sendAction("user_manager", object.id(), "choose"));
        assertInstanceOf(AmbiguousTransitionException.class, error.getCause());
        assertEquals(0, calls.get());
        assertEquals("start", fixture.engine.getObject(object.id()).stateId());
    }
}
