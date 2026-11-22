package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class Task311CommandsTest {
    private static final String VALID = "demo-office-valid";
    private static final String EXCESS = "demo-office-quantity-700";
    @TempDir Path directory;

    @Test
    void processesTheRouteAndLogsAllOutcomes() throws Exception {
        var scenario = CoordinationScenario.task311();
        var commands = scenario.openTask311(directory.resolve("journal.jsonl"));
        assertEquals("PROCESS_NOT_STARTED", commands.submit(action(VALID, "complete")).reason());
        assertEquals("ACCESS_DENIED", commands.submit(start("user_finance", VALID)).reason());
        var started = commands.submit(start("user_manager", VALID));
        assertNull(started.before());
        assertEquals("registration", started.after());
        assertEquals("start", started.command().get("type").textValue());
        var repeated = commands.submit(start("user_manager", VALID));
        assertEquals("PROCESS_ALREADY_STARTED", repeated.reason());
        assertEquals(repeated.before(), repeated.after());
        assertEquals("composition_approval", commands.submit(action(VALID, "complete")).after());
        var denied = commands.submit(action(VALID, "approve").replace("user_coordinator", "user_finance"));
        assertEquals("ACCESS_DENIED", denied.reason());
        assertEquals(denied.before(), denied.after());
        assertEquals("price_approval", commands.submit(action(VALID, "approve")).after());
        assertEquals("composition_approval", commands.submit(action(VALID, "correction")).after());
        for (String target : new String[]{"price_approval", "delivery_approval", "payment_approval", "execution_decision"}) {
            assertEquals(target, commands.submit(action(VALID, "approve")).after());
        }
        assertEquals("execution", commands.submit(action(VALID, "execute")).after());
        assertEquals("completed", commands.submit(action(VALID, "complete")).after());
        assertEquals("FINAL_STATE", commands.submit(action(VALID, "complete")).reason());
        assertEquals("UNKNOWN_USER", commands.submit(start("unknown", EXCESS)).reason());
        commands.submit(start("user_manager", EXCESS));
        commands.submit(action(EXCESS, "complete"));
        var range = commands.submit(action(EXCESS, "approve"));
        assertEquals("CONDITION_NOT_MET", range.reason());
        assertEquals(range.before(), range.after());
        var malformed = commands.submit("{oops");
        assertEquals("INVALID_COMMAND", malformed.reason());
        assertEquals("{oops", malformed.command().textValue());
        assertEquals("INVALID_COMMAND", commands.submit("{\"type\":\"unknown\"}").reason());
        assertEquals("INVALID_COMMAND", commands.submit("{\"type\":\"action\",\"userId\":\"user_manager\"}").reason());
        assertEquals("UNKNOWN_OBJECT", commands.submit(start("user_manager", "unknown")).reason());
        assertEquals(commands.journal().size(), commands.journalText().lines().count());
        var fresh = scenario.openTask311(directory.resolve("fresh.jsonl"));
        assertTrue(fresh.findProcess(VALID).isEmpty());
        assertTrue(fresh.journal().isEmpty());
        assertEquals("registration", fresh.submit(start("user_manager", VALID)).after());
        assertEquals("completed", commands.findProcess(VALID).orElseThrow().stateId());
    }

    @Test
    void serializesTwoStarts() throws Exception {
        var commands = CoordinationScenario.task311().openTask311(directory.resolve("race.jsonl"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> commands.submit(start("user_manager", VALID)));
            var second = executor.submit(() -> commands.submit(start("user_manager", VALID)));
            assertNotEquals(first.get().outcome(), second.get().outcome());
        }
        var snapshot = commands.snapshot();
        assertEquals(2, snapshot.journal().size());
        assertEquals("PROCESS_ALREADY_STARTED", snapshot.journal().getLast().reason());
        assertEquals(snapshot.journal().getFirst().after(), snapshot.processes().get(VALID).stateId());
    }

    @Test
    void distinguishesMultipleDnfGroupsFromMultipleTransitions() throws Exception {
        var both = Task311Fixture.change(config -> Task311Fixture.values(config).put("prepayment_percent", 20))
                .openTask311(directory.resolve("both.jsonl"));
        advanceToPayment(both);
        assertEquals("execution_decision", both.submit(action(VALID, "approve")).after());

        var blocked = Task311Fixture.change(config -> Task311Fixture.values(config).put("block_reason", "Блокировка"))
                .openTask311(directory.resolve("blocked.jsonl"));
        advanceToPayment(blocked);
        assertEquals("CONDITION_NOT_MET", blocked.submit(action(VALID, "approve")).reason());

        var ambiguous = Task311Fixture.change(config -> {
            var transitions = (ArrayNode) config.path("catalog").path("templates").get(0).path("transitions");
            transitions.addObject().put("from", "registration").put("action", "complete").put("to", "price_approval");
        }).openTask311(directory.resolve("ambiguous.jsonl"));
        ambiguous.submit(start("user_manager", VALID));
        var rejected = ambiguous.submit(action(VALID, "complete"));
        assertEquals("AMBIGUOUS_TRANSITION", rejected.reason());
        assertEquals(CoordinationJournal.Outcome.ERROR, rejected.outcome());
        assertEquals(rejected.before(), rejected.after());
    }

    @Test
    void doesNotInheritATemplateOrApplyAnUnloggedStateChange() throws Exception {
        var parentTemplate = Task311Fixture.change(config ->
                ((ObjectNode) config.path("catalog").path("templates").get(0)).put("classId", "standard_order"));
        assertEquals("NO_EXACT_TEMPLATE", parentTemplate.openTask311(directory.resolve("parent.jsonl"))
                .submit(start("user_manager", VALID)).reason());

        var unavailable = Files.createDirectory(directory.resolve("not-a-file"));
        var commands = CoordinationScenario.task311().openTask311(unavailable);
        assertThrows(IOException.class, () -> commands.submit(start("user_manager", VALID)));
        assertTrue(commands.findProcess(VALID).isEmpty());
    }

    private static void advanceToPayment(Task311Commands commands) throws IOException {
        commands.submit(start("user_manager", VALID));
        commands.submit(action(VALID, "complete"));
        for (int i = 0; i < 3; i++) {
            commands.submit(action(VALID, "approve"));
        }
        assertEquals("payment_approval", commands.findProcess(VALID).orElseThrow().stateId());
    }

    private static String start(String user, String id) {
        return """
                {"type":"start","userId":"%s","objectId":"%s"}""".formatted(user, id);
    }

    private static String action(String id, String action) {
        return """
                {"type":"action","userId":"user_coordinator","objectId":"%s","actionId":"%s"}""".formatted(id, action);
    }
}
