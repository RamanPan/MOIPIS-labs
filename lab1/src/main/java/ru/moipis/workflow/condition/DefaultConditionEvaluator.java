package ru.moipis.workflow.condition;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.ParameterDefinition;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.ConditionLiteral;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Вычисляет условия по параметрам и классу объекта. Сеанс лабораторных задач
 * вызывает этот класс для отдельных локальных предикатов.
 */
public final class DefaultConditionEvaluator implements ConditionEvaluator {

    private static final Set<String> OPERATORS = Set.of("filled", "=", "!=", "<", "<=", ">", ">=", "in_range");
    private static final Set<String> STRING_OPERATORS = Set.of("filled", "=", "!=");
    private final CatalogService classes;
    private final boolean allowAbsentParameters;

    public DefaultConditionEvaluator(CatalogService classes) {
        this(classes, false);
    }

    public DefaultConditionEvaluator(CatalogService classes, boolean allowAbsentParameters) {
        this.classes = required(classes, "Class catalog");
        this.allowAbsentParameters = allowAbsentParameters;
    }

    @Override
    public boolean evaluate(DnfCondition condition, Map<String, PredicateDefinition> predicates, ConditionContext context) {
        if (condition == null) {
            return true;
        }
        required(predicates, "Predicates");
        required(context, "Condition context");
        Map<String, ParameterDefinition> definitions = classes.getEffectiveParameters(context.classId());
        return evaluateDnf(condition, predicateId -> {
            PredicateDefinition predicate = predicates.get(predicateId);
            if (predicate == null) {
                throw new IllegalArgumentException("Unknown predicate: " + predicateId);
            }
            return evaluatePredicate(predicate, context, definitions);
        });
    }

    /** Применяет отрицания к результатам предикатов и ищет группу, где все литералы истинны. */
    public static boolean evaluateDnf(DnfCondition condition, Predicate<String> predicateMatches) {
        if (condition == null) {
            return true;
        }
        for (List<ConditionLiteral> group : required(condition.groups(), "Condition groups")) {
            boolean matches = true;
            for (ConditionLiteral literal : required(group, "Condition group")) {
                required(literal, "Condition literal");
                boolean value = predicateMatches.test(literal.predicateId());
                if (literal.negated() == value) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** Вычисляет один локальный предикат по значениям объекта и определениям его фактического класса. */
    public boolean evaluatePredicate(PredicateDefinition predicate, ConditionContext context) {
        required(context, "Condition context");
        return evaluatePredicate(predicate, context, classes.getEffectiveParameters(context.classId()));
    }

    private boolean evaluatePredicate(PredicateDefinition predicate, ConditionContext context,
                                      Map<String, ParameterDefinition> definitions) {
        required(predicate, "Predicate");
        if (predicate.classId() != null) {
            if (predicate.parameterId() != null) {
                throw new IllegalArgumentException("Predicate cannot reference both a parameter and a class: " + predicate.id());
            }
            return classes.isInstanceOf(context.classId(), predicate.classId());
        }
        ParameterDefinition definition = definitions.get(predicate.parameterId());
        if (definition == null) {
            if (allowAbsentParameters && predicate.parameterId() != null && !predicate.parameterId().isBlank()
                    && predicate.operator() != null && OPERATORS.contains(predicate.operator())) {
                return false;
            }
            throw new IllegalArgumentException("Unknown parameter in predicate: " + predicate.parameterId());
        }
        String operator = predicate.operator();
        if (operator == null || !OPERATORS.contains(operator)
                || (definition.type().equals("string") && !STRING_OPERATORS.contains(operator))) {
            throw new IllegalArgumentException("Invalid operator '%s' for parameter '%s' of type '%s'"
                    .formatted(operator, definition.id(), definition.type()));
        }
        JsonNode actual = context.parameters().get(definition.id());
        // Для filled пустая строка считается заданным значением, а null и отсутствующее поле — нет.
        boolean filled = actual != null && !actual.isNull() && !actual.isMissingNode();
        if (operator.equals("filled")) {
            return filled;
        }
        if (!filled) {
            return false;
        }
        // Диапазон берётся из определения фактического класса, включая переопределённые границы.
        if (operator.equals("in_range")) {
            BigDecimal numeric = definition.type().equals("integer")
                    ? new BigDecimal(integer(actual)) : decimal(actual);
            return (definition.min() == null || numeric.compareTo(definition.min()) >= 0)
                    && (definition.max() == null || numeric.compareTo(definition.max()) <= 0);
        }
        JsonNode expected = predicate.value();
        if (expected == null || expected.isNull() || expected.isMissingNode()) {
            throw new IllegalArgumentException("Comparison requires a value: " + predicate.id());
        }
        int comparison = switch (definition.type()) {
            case "integer" -> integer(actual).compareTo(integer(expected));
            case "decimal" -> decimal(actual).compareTo(decimal(expected));
            case "string" -> text(actual).compareTo(text(expected));
            default -> throw new IllegalArgumentException("Unknown parameter type: " + definition.type());
        };
        return switch (operator) {
            case "=" -> comparison == 0;
            case "!=" -> comparison != 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            default -> throw new IllegalArgumentException("Unknown operator: " + operator);
        };
    }

    private static BigInteger integer(JsonNode value) {
        try {
            return decimal(value).toBigIntegerExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Integer predicate requires a whole number: " + value, failure);
        }
    }

    private static BigDecimal decimal(JsonNode value) {
        if (!value.isNumber()) {
            throw new IllegalArgumentException("Numeric predicate requires a JSON number: " + value);
        }
        return value.decimalValue();
    }

    private static String text(JsonNode value) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException("String predicate requires a JSON string: " + value);
        }
        return value.textValue();
    }

    private static <Value> Value required(Value value, String description) {
        if (value == null) {
            throw new IllegalArgumentException(description + " must not be null");
        }
        return value;
    }
}
