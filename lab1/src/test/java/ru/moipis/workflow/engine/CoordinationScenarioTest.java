package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.moipis.workflow.engine.CoordinationJournal.Outcome.*;

class CoordinationScenarioTest {
    @TempDir Path directory;

    @Test
    void waitsForApprovalAndDeliversTheChangeBeforeReturning() throws Exception {
        Path log = directory.resolve("run.jsonl");
        var session = CoordinationScenario.demo().open(log);
        assertEquals(SUCCESS, session.start("manager", "order-1").command().outcome());
        assertEquals(SUCCESS, session.start("reviewer", "company-1").command().outcome());
        assertEquals(REJECTED, session.action("manager", "order-1", "submit").command().outcome());
        session.action("reviewer", "company-1", "approve");
        assertEquals("preparing", session.findProcess("order-1").orElseThrow().stateId());
        assertEquals(SUCCESS, session.action("manager", "order-1", "submit").command().outcome());
        var revise = session.action("reviewer", "company-1", "revise");
        assertEquals(SUCCESS, revise.command().outcome());
        var event = revise.deliveries().getFirst();
        assertEquals(SUCCESS, event.outcome());
        assertEquals("customer_changed", event.eventName());
        assertEquals(revise.command().id(), event.parentId());
        assertEquals("preparing", session.findProcess("order-1").orElseThrow().stateId());
        assertEquals("submit", session.findProcess("order-1").orElseThrow().lastActionId());
        session.action("reviewer", "company-1", "approve");
        session.action("manager", "order-1", "submit");
        assertEquals("done", session.action("manager", "order-1", "confirm").command().after());
        assertEquals(10, Files.readAllLines(log).size());
        assertTrue(CoordinationScenario.demo().open(log).findProcess("order-1").isEmpty());
    }

    @Test
    void unstartedRecipientDoesNotUndoTheSource() throws Exception {
        var session = CoordinationScenario.demo().open(directory.resolve("run.jsonl"));
        assertEquals(REJECTED, session.action("manager", "order-1", "submit").command().outcome());
        assertEquals(REJECTED, session.start("manager", "company-1").command().outcome());
        assertEquals(SUCCESS, session.start("reviewer", "company-1").command().outcome());
        assertEquals(REJECTED, session.start("reviewer", "company-1").command().outcome());
        session.action("reviewer", "company-1", "approve");
        var refused = session.action("manager", "company-1", "revise");
        assertEquals(REJECTED, refused.command().outcome());
        assertTrue(refused.deliveries().isEmpty());
        var revised = session.action("reviewer", "company-1", "revise");
        assertEquals(SUCCESS, revised.command().outcome());
        assertEquals("checking", revised.command().after());
        assertEquals(REJECTED, revised.deliveries().getFirst().outcome());
        assertEquals("PROCESS_NOT_STARTED", revised.deliveries().getFirst().reason());
        assertNull(revised.deliveries().getFirst().before());
        assertTrue(session.findProcess("order-1").isEmpty());
    }

    @Test
    void missingRecipientsRejectTransitionBeforeAnyChange() throws Exception {
        var json = seed();
        ((ObjectNode) json.at("/objects/1/links")).putArray("orders");
        var session = open(json);
        session.start("reviewer", "company-1");
        session.action("reviewer", "company-1", "approve");
        var result = session.action("reviewer", "company-1", "revise");
        assertEquals(REJECTED, result.command().outcome());
        assertTrue(result.deliveries().isEmpty());
        assertEquals(new CoordinationSession.ProcessSnapshot("verified", "approve"),
                session.findProcess("company-1").orElseThrow());
    }

    @Test
    void verifiedStateAloneIsInsufficient() throws Exception {
        var json = seed();
        ((ObjectNode) json.at("/catalog/templates/1/actions/0")).put("id", "other");
        ((ObjectNode) json.at("/catalog/templates/1/transitions/0")).put("action", "other");
        var session = open(json);
        session.start("manager", "order-1");
        session.start("reviewer", "company-1");
        assertEquals("verified", session.action("reviewer", "company-1", "other").command().after());
        assertEquals("CONDITION_NOT_MET", session.action("manager", "order-1", "submit").command().reason());
    }

    @Test
    void missingLastActionDoesNotSatisfyNotEqual() throws Exception {
        var json = seed();
        ((ObjectNode) json.at("/catalog/templates/0/predicates/1")).put("value", "checking");
        ((ObjectNode) json.at("/catalog/templates/0/predicates/2")).put("operator", "!=");
        var session = open(json);
        session.start("manager", "order-1");
        session.start("reviewer", "company-1");
        assertEquals("CONDITION_NOT_MET", session.action("manager", "order-1", "submit").command().reason());
    }

    @Test
    void outgoingMessagesAreFifoEvenAfterAmbiguousDelivery() throws Exception {
        var json = seed();
        var objects = (ArrayNode) json.path("objects");
        ObjectNode first = ((ObjectNode) objects.get(0)).deepCopy().put("id", "order-first").put("classId", "order-alt");
        objects.add(first);
        ((ArrayNode) json.at("/objects/1/links/orders")).insert(0, "order-first");
        ((ObjectNode) json.at("/objects/0/links")).putArray("peers").add("order-first");
        ((ObjectNode) first.path("links")).putArray("peers").add("order-1");

        ((ArrayNode) json.at("/catalog/classes")).addObject().put("id", "order-alt").put("title", "Alternative")
                .put("parentId", "order").putArray("parameters");
        var orderTemplate = (ObjectNode) json.at("/catalog/templates/0");
        var alternate = orderTemplate.deepCopy().put("id", "order-process-alt").put("classId", "order-alt");
        var alternateEvents = (ArrayNode) alternate.path("eventTransitions");
        alternateEvents.add(alternateEvents.get(0).deepCopy());
        alternateEvents.addObject().put("from", "ready").put("to", "preparing")
                .put("event", "peer_changed").put("sourceLink", "peers");
        ((ArrayNode) json.at("/catalog/templates")).add(alternate);
        ((ObjectNode) orderTemplate.at("/eventTransitions/0")).putArray("emit").addObject()
                .put("event", "peer_changed").put("recipientLink", "peers");

        var session = open(json);
        session.start("manager", "order-first");
        session.start("manager", "order-1");
        session.start("reviewer", "company-1");
        session.action("reviewer", "company-1", "approve");
        session.action("manager", "order-first", "submit");
        session.action("manager", "order-1", "submit");
        var result = session.action("reviewer", "company-1", "revise");
        assertEquals(List.of("order-first", "order-1", "order-first"),
                result.deliveries().stream().map(CoordinationJournal.Entry::recipientId).toList());
        assertEquals(List.of(ERROR, SUCCESS, SUCCESS),
                result.deliveries().stream().map(CoordinationJournal.Entry::outcome).toList());
        assertEquals(result.deliveries().get(1).id(), result.deliveries().get(2).parentId());
    }

    @ParameterizedTest
    @CsvSource({"0,REJECTED", "1,SUCCESS", "100000,SUCCESS", "100001,REJECTED"})
    void amountRangeIsCheckedOnTransition(int amount, CoordinationJournal.Outcome outcome) throws Exception {
        var json = seed();
        ((ObjectNode) json.at("/objects/0/values")).put("amount", amount);
        var session = open(json);
        assertEquals(SUCCESS, session.start("manager", "order-1").command().outcome());
        session.start("reviewer", "company-1");
        session.action("reviewer", "company-1", "approve");
        assertEquals(outcome, session.action("manager", "order-1", "submit").command().outcome());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "unstarted", "mismatch"})
    void allRequiresEveryLinkedProcessAndNotAppliesAfterAggregation(String variant) throws Exception {
        var json = seed();
        var links = (ObjectNode) json.at("/objects/0/links");
        if (variant.equals("missing")) {
            links.remove("customer");
        } else if (variant.equals("empty")) {
            links.putArray("customer");
        } else {
            ((ArrayNode) json.path("objects")).add(((ObjectNode) json.at("/objects/1")).deepCopy()
                    .put("id", "company-2"));
            ((ArrayNode) links.path("customer")).add("company-2");
        }
        var session = open(json);
        session.start("manager", "order-1");
        session.start("reviewer", "company-1");
        session.action("reviewer", "company-1", "approve");
        if (variant.equals("mismatch")) {
            session.start("reviewer", "company-2");
        }
        assertEquals("CONDITION_NOT_MET", session.action("manager", "order-1", "submit").command().reason());
        var group = (ArrayNode) json.at("/catalog/templates/0/transitions/0/condition/groups/0");
        ((ObjectNode) group.get(1)).put("negated", true);
        ((ObjectNode) group.get(2)).put("negated", true);
        var negated = open(json);
        negated.start("manager", "order-1");
        negated.start("reviewer", "company-1");
        negated.action("reviewer", "company-1", "approve");
        if (variant.equals("mismatch")) {
            negated.start("reviewer", "company-2");
        }
        assertEquals(SUCCESS, negated.action("manager", "order-1", "submit").command().outcome());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sender", "condition", "final", "handler"})
    void rejectedDeliveryLeavesTheOrderAsItWas(String variant) throws Exception {
        var json = seed();
        var event = (ObjectNode) json.at("/catalog/templates/0/eventTransitions/0");
        switch (variant) {
            case "sender" -> event.put("sourceLink", "unrelated");
            case "condition" -> event.putObject("condition").putArray("groups").addArray().addObject()
                    .put("predicateId", "amount_valid").put("negated", true);
            case "handler" -> event.put("event", "other");
            default -> { }
        }
        var session = open(json);
        session.start("manager", "order-1");
        session.start("reviewer", "company-1");
        session.action("reviewer", "company-1", "approve");
        session.action("manager", "order-1", "submit");
        if (variant.equals("final")) {
            session.action("manager", "order-1", "confirm");
        }
        var before = session.findProcess("order-1");
        var revision = session.action("reviewer", "company-1", "revise");
        assertEquals(SUCCESS, revision.command().outcome());
        assertEquals(REJECTED, revision.deliveries().getFirst().outcome());
        assertEquals(before, session.findProcess("order-1"));
    }

    @Test
    void childDoesNotStartWithParentTemplate() throws Exception {
        var json = seed();
        ((ArrayNode) json.at("/catalog/classes")).addObject().put("id", "child").put("title", "Child")
                .put("parentId", "order").putArray("parameters");
        ((ObjectNode) json.at("/objects/0")).put("classId", "child");
        var session = open(json);
        assertEquals("NO_EXACT_TEMPLATE", session.start("manager", "order-1").command().reason());
        assertTrue(session.findProcess("order-1").isEmpty());
    }

    private static ObjectNode seed() throws Exception {
        try (var input = CoordinationScenario.class.getResourceAsStream("/seed/coordination-3-1-2.json")) {
            return (ObjectNode) new ObjectMapper().readTree(input);
        }
    }

    private CoordinationSession open(ObjectNode json) throws Exception {
        try (var input = new ByteArrayInputStream(new ObjectMapper().writeValueAsBytes(json))) {
            return CoordinationScenario.read(input).open(directory.resolve("run.jsonl"));
        }
    }
}
