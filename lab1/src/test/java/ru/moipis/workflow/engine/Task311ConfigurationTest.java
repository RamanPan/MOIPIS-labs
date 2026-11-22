package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.ConditionLiteral;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class Task311ConfigurationTest {
    @TempDir Path directory;

    @Test
    void loadsReadyOrdersWithInheritedDefinitionsAndAnExactTemplate() throws Exception {
        var scenario = CoordinationScenario.task311();
        var template = scenario.catalog().templates().getFirst();
        assertEquals("office_order", template.classId());
        assertEquals(500, new CatalogService(scenario.catalog().classes()).getEffectiveParameters("office_order")
                .get("quantity").max().intValueExact());
        assertEquals(10, template.states().size());
        assertEquals(19, template.transitions().size());
        var commands = scenario.openTask311(directory.resolve("journal.jsonl"));
        var order = commands.objects().stream().filter(o -> o.id().equals("demo-office-valid")).findFirst().orElseThrow();
        assertEquals("Б3-002", order.values().get("order_number").textValue());
        assertEquals(14, order.values().get("delivery_days").intValue());
        assertTrue(order.values().get("block_reason").isNull());
        assertTrue(commands.findProcess(order.id()).isEmpty());
        assertTrue(commands.objects().stream().anyMatch(o -> o.values().get("quantity").intValue() == 700));
    }

    @Test
    void preservesExplicitNullAndUsesTheWholeOverriddenDefinition() throws Exception {
        assertTrue(officeValues(config -> Task311Fixture.values(config).putNull("delivery_days")).get("delivery_days").isNull());
        assertFalse(officeValues(config -> {
            var parameters = (ArrayNode) config.path("catalog").path("classes").get(2).path("parameters");
            parameters.addObject().put("id", "delivery_days").put("title", "Срок поставки").put("type", "integer");
        }).containsKey("delivery_days"));
    }

    @Test
    void appliesDnfNegationToAnAbsentParameterAndTreatsAnEmptyStringAsDefined() throws Exception {
        var scenario = Task311Fixture.change(config -> {
            var predicates = config.path("catalog").path("templates").get(0).path("predicates");
            for (var predicate : predicates) {
                if (predicate.path("id").asText().equals("block_reason_filled")) {
                    ((ObjectNode) predicate).put("parameterId", "missing_definition");
                }
            }
        });
        var template = scenario.catalog().templates().getFirst();
        var evaluator = new DefaultConditionEvaluator(new CatalogService(scenario.catalog().classes()), true);
        var context = new ConditionContext("office_order", Map.of());
        var absent = template.predicates().stream().filter(p -> p.id().equals("block_reason_filled")).findFirst().orElseThrow();
        assertFalse(evaluator.evaluatePredicate(absent, context));
        assertFalse(evaluator.evaluatePredicate(
                new PredicateDefinition("comparison", "Comparison", "missing_definition", "!=", TextNode.valueOf("x")),
                context));
        assertTrue(DefaultConditionEvaluator.evaluateDnf(
                new DnfCondition(List.of(List.of(new ConditionLiteral(absent.id(), true)))),
                id -> evaluator.evaluatePredicate(absent, context)));
        var commands = scenario.openTask311(directory.resolve("absent.jsonl"));
        assertEquals("registration", commands.submit("{\"type\":\"start\",\"userId\":\"user_manager\",\"objectId\":\"demo-office-valid\"}").after());
        var filled = template.predicates().stream().filter(p -> p.id().equals("order_number_filled")).findFirst().orElseThrow();
        assertTrue(evaluator.evaluatePredicate(filled,
                new ConditionContext("office_order", Map.of("order_number", TextNode.valueOf("")))));
    }

    private Map<String, JsonNode> officeValues(Consumer<ObjectNode> change) throws Exception {
        return Task311Fixture.change(change).openTask311(directory.resolve("defaults.jsonl")).objects().getFirst().values();
    }
}
