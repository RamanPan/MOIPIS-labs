package ru.moipis.workflow.condition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.ObjectClassDefinition;
import ru.moipis.workflow.catalog.model.ParameterDefinition;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.ConditionLiteral;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;
import ru.moipis.workflow.repository.model.StoreData;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultConditionEvaluatorTest {

    private ConditionEvaluator evaluator;

    @BeforeEach
    void setUp() throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/seed/initial-data.json"))) {
            StoreData data = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(input, StoreData.class);
            evaluator = new DefaultConditionEvaluator(new CatalogService(data.classes()));
        }
    }

    @Test
    void filledMeansAssignedIncludingEmptyStringAndZero() {
        PredicateDefinition text = predicate("order_number", "filled", null);
        PredicateDefinition number = predicate("quantity", "filled", null);

        assertTrue(evaluator.evaluatePredicate(text, context("order_number", TextNode.valueOf(""))));
        assertTrue(evaluator.evaluatePredicate(number, context("quantity", decimal("0"))));
        assertFalse(evaluator.evaluatePredicate(text, new ConditionContext("order", Map.of())));
        assertFalse(evaluator.evaluatePredicate(text, context("order_number", NullNode.instance)));
        assertFalse(evaluator.evaluatePredicate(text, context("order_number", null)));
        assertFalse(evaluator.evaluatePredicate(text, context("order_number", MissingNode.getInstance())));
    }

    @ParameterizedTest
    @CsvSource({"=,same,same,true", "=,same,other,false", "!=,same,other,true", "!=,same,same,false"})
    void comparesStrings(String operator, String actual, String expected, boolean result) {
        assertEquals(result, evaluator.evaluatePredicate(predicate("order_number", operator, TextNode.valueOf(expected)),
                context("order_number", TextNode.valueOf(actual))));
    }

    @ParameterizedTest
    @CsvSource({
            "=,10,10,true", "=,10,2,false", "!=,10,2,true", "!=,10,10,false",
            "<,2,10,true", "<,10,2,false", "<,10,10,false",
            "<=,2,10,true", "<=,10,10,true", "<=,11,10,false",
            ">,10,2,true", ">,2,10,false", ">,10,10,false",
            ">=,10,2,true", ">=,10,10,true", ">=,2,10,false"
    })
    void comparesIntegerValuesExactly(String operator, String actual, String expected, boolean result) {
        assertEquals(result, evaluator.evaluatePredicate(predicate("quantity", operator, integer(expected)),
                context("quantity", integer(actual))));
    }

    @ParameterizedTest
    @CsvSource({
            "=,1.00,1.0,true", "!=,1.00,1.0,false", "!=,1.01,1.0,true",
            "<,2.01,10.0,true", "<,10.0,2.01,false", "<=,1.10,1.1,true",
            ">,10.01,2.0,true", ">=,1.1,1.10,true", ">=,1.09,1.10,false",
            "<,9007199254740992.0000000001,9007199254740992.0000000002,true"
    })
    void comparesDecimalsUsingBigDecimal(String operator, String actual, String expected, boolean result) {
        assertEquals(result, evaluator.evaluatePredicate(predicate("total_amount", operator, decimal(expected)),
                context("total_amount", decimal(actual))));
    }

    @Test
    void doesNotLoseIntegerPrecisionBeyondLongOrDoubleRange() {
        assertTrue(evaluator.evaluatePredicate(predicate("quantity", ">", integer("9223372036854775808")),
                context("quantity", integer("9223372036854775809"))));
    }

    @ParameterizedTest
    @CsvSource({"1,true", "10000,true", "500,true", "0,false", "10001,false"})
    void inRangeUsesInclusiveBounds(String actual, boolean expected) {
        assertEquals(expected, evaluator.evaluatePredicate(predicate("quantity", "in_range", null),
                context("quantity", integer(actual))));
    }

    @Test
    void inRangeUsesOverriddenBoundsOfTheActualSubclass() {
        PredicateDefinition predicate = predicate("quantity", "in_range", null);

        assertTrue(evaluator.evaluatePredicate(predicate, new ConditionContext("standard_order", Map.of("quantity", integer("501")))));
        assertFalse(evaluator.evaluatePredicate(predicate, new ConditionContext("office_order", Map.of("quantity", integer("501")))));
        assertTrue(evaluator.evaluatePredicate(predicate, new ConditionContext("office_order", Map.of("quantity", integer("500")))));
    }

    @ParameterizedTest
    @CsvSource(value = {"1,NULL,0,false", "1,NULL,1,true", "1,NULL,999999,true",
            "NULL,10,11,false", "NULL,10,10,true", "NULL,10,-100,true", "NULL,NULL,-999,true",
            "0.1,0.3,0.3,true", "0.1,0.3,0.300000000000000000001,false"}, nullValues = "NULL")
    void inRangeAllowsEitherBoundOrBothToBeAbsent(String min, String max, String actual, boolean expected) {
        ConditionEvaluator service = new DefaultConditionEvaluator(new CatalogService(List.of(
                new ObjectClassDefinition("sample", "Sample", null, List.of(
                        new ParameterDefinition("value", "Value", "decimal", bound(min), bound(max)))))));

        assertEquals(expected, service.evaluatePredicate(predicate("value", "in_range", null),
                new ConditionContext("sample", Map.of("value", decimal(actual)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "!=", "<", "<=", ">", ">=", "in_range", "filled"})
    void absentOrNullValuesAreFalseForEveryParameterPredicate(String operator) {
        PredicateDefinition predicate = predicate("quantity", operator, integer("1"));

        assertFalse(evaluator.evaluatePredicate(predicate, new ConditionContext("order", Map.of())));
        assertFalse(evaluator.evaluatePredicate(predicate, context("quantity", NullNode.instance)));
        assertFalse(evaluator.evaluatePredicate(predicate, context("quantity", null)));
        assertFalse(evaluator.evaluatePredicate(predicate, context("quantity", MissingNode.getInstance())));
    }

    @ParameterizedTest
    @CsvSource({"office_order,order,true", "office_order,standard_order,true", "office_order,office_order,true",
            "office_order,consumables_order,false", "order,office_order,false"})
    void classPredicateIncludesTheClassAndItsDescendants(String actual, String expected, boolean result) {
        assertEquals(result, evaluator.evaluatePredicate(
                new PredicateDefinition("class", "Class", null, null, null, expected),
                new ConditionContext(actual, Map.of())));
    }

    @ParameterizedTest
    @CsvSource({"true,true,true", "true,false,false", "false,true,false", "false,false,false"})
    void computesAnd(boolean first, boolean second, boolean expected) {
        assertEquals(expected, evaluator.evaluate(new DnfCondition(List.of(List.of(literal("A"), literal("B")))),
                booleanPredicates(), booleanContext(first, second)));
    }

    @ParameterizedTest
    @CsvSource({"true,false,true", "false,true,true", "true,true,true", "false,false,false"})
    void computesOrUsingEitherBranch(boolean first, boolean second, boolean expected) {
        assertEquals(expected, evaluator.evaluate(new DnfCondition(List.of(List.of(literal("A")), List.of(literal("B")))),
                booleanPredicates(), booleanContext(first, second)));
    }

    @Test
    void computesNotIncludingAnAbsentValueAndAFullDnfExpression() {
        Map<String, PredicateDefinition> predicates = new HashMap<>(booleanPredicates());
        predicates.put("C", predicate("product_name", "filled", null));
        predicates.put("D", predicate("total_amount", "filled", null));
        DnfCondition condition = new DnfCondition(List.of(List.of(literal("A"), literal("B")),
                List.of(literal("C"), new ConditionLiteral("D", true))));

        assertTrue(evaluator.evaluate(condition, predicates, context("product_name", TextNode.valueOf("paper"))));
        assertFalse(evaluator.evaluate(condition, predicates, new ConditionContext("order", Map.of(
                "product_name", TextNode.valueOf("paper"), "total_amount", decimal("10")))));
        assertTrue(evaluator.evaluate(new DnfCondition(List.of(List.of(new ConditionLiteral("A", true)))),
                predicates, new ConditionContext("order", Map.of())));
    }

    @Test
    void supportsUnconditionalAndEmptyBooleanExpressions() {
        assertTrue(evaluator.evaluate(null, Map.of(), null));
        assertFalse(evaluator.evaluate(new DnfCondition(List.of()), Map.of(), new ConditionContext("order", Map.of())));
        assertTrue(evaluator.evaluate(new DnfCondition(List.of(List.of())), Map.of(), new ConditionContext("order", Map.of())));
    }

    @Test
    void rejectsInvalidTypesOperatorsOperandsAndReferences() {
        ConditionContext numeric = context("quantity", integer("5"));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(predicate("quantity", "contains", integer("5")), numeric));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(predicate("order_number", "<", TextNode.valueOf("x")), context("order_number", TextNode.valueOf("y"))));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(predicate("quantity", "=", TextNode.valueOf("5")), numeric));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(predicate("quantity", "=", integer("5")), context("quantity", TextNode.valueOf("5"))));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(predicate("quantity", "=", decimal("5.5")), numeric));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(predicate("quantity", "=", integer("5")), context("quantity", decimal("5.5"))));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(predicate("quantity", "=", null), numeric));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(predicate("missing", "filled", null), numeric));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(new DnfCondition(List.of(List.of(literal("missing")))), Map.of(), numeric));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluatePredicate(new PredicateDefinition("class", "Class", null, null, null, "missing"), numeric));
    }

    private static Map<String, PredicateDefinition> booleanPredicates() {
        return Map.of("A", predicate("order_number", "filled", null), "B", predicate("quantity", "filled", null));
    }

    private static ConditionContext booleanContext(boolean first, boolean second) {
        Map<String, JsonNode> values = new HashMap<>();
        if (first) {
            values.put("order_number", TextNode.valueOf("number"));
        }
        if (second) {
            values.put("quantity", integer("1"));
        }
        return new ConditionContext("order", values);
    }

    private static ConditionLiteral literal(String id) {
        return new ConditionLiteral(id, false);
    }

    private static PredicateDefinition predicate(String parameterId, String operator, JsonNode value) {
        return new PredicateDefinition(parameterId + operator, parameterId, parameterId, operator, value);
    }

    private static ConditionContext context(String parameterId, JsonNode value) {
        Map<String, JsonNode> values = new HashMap<>();
        values.put(parameterId, value);
        return new ConditionContext("order", values);
    }

    private static JsonNode integer(String value) {
        return JsonNodeFactory.instance.numberNode(new BigInteger(value));
    }

    private static JsonNode decimal(String value) {
        return DecimalNode.valueOf(new BigDecimal(value));
    }

    private static BigDecimal bound(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
