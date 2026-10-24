package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.catalog.model.ParameterDefinition;
import ru.moipis.workflow.engine.exception.WorkflowError;
import ru.moipis.workflow.engine.exception.WorkflowException;

import java.util.LinkedHashMap;
import java.util.Map;

final class ParameterValues {
    private ParameterValues() {
    }

    static Map<String, JsonNode> copy(Map<String, JsonNode> values) {
        if (values == null) {
            throw new WorkflowException(WorkflowError.INVALID_PARAMETER_TYPE, "Parameters must not be null");
        }
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        values.forEach((id, value) -> copy.put(id, value == null ? null : value.deepCopy()));
        return copy;
    }

    static void validate(String id, JsonNode value, Map<String, ParameterDefinition> definitions) {
        ParameterDefinition definition = definitions.get(id);
        if (definition == null) {
            throw new WorkflowException(WorkflowError.UNKNOWN_PARAMETER, "Unknown parameter: " + id);
        }
        if (value == null || value.isNull()) {
            return;
        }
        boolean valid = switch (definition.type()) {
            case "string" -> value.isTextual();
            case "decimal" -> value.isNumber() && !nonFinite(value);
            case "integer" -> value.isNumber() && !nonFinite(value)
                    && value.decimalValue().stripTrailingZeros().scale() <= 0;
            default -> false;
        };
        if (!valid) {
            throw new WorkflowException(WorkflowError.INVALID_PARAMETER_TYPE,
                    "Parameter '%s' requires type '%s'".formatted(id, definition.type()));
        }
    }

    private static boolean nonFinite(JsonNode value) {
        return (value.isDouble() || value.isFloat()) && !Double.isFinite(value.doubleValue());
    }
}
