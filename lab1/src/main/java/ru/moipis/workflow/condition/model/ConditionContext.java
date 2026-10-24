package ru.moipis.workflow.condition.model;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.engine.model.BusinessObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConditionContext(String classId, Map<String, JsonNode> parameters) {

    public ConditionContext {
        if (classId == null || classId.isBlank() || parameters == null) {
            throw new IllegalArgumentException("Condition context requires classId and parameters");
        }
        Map<String, JsonNode> snapshot = new LinkedHashMap<>();
        parameters.forEach((parameterId, value) -> snapshot.put(parameterId, value == null ? null : value.deepCopy()));
        parameters = Collections.unmodifiableMap(snapshot);
    }

    public static ConditionContext from(BusinessObject object) {
        if (object == null) {
            throw new IllegalArgumentException("Business object must not be null");
        }
        return new ConditionContext(object.classId(), object.parameters());
    }
}
