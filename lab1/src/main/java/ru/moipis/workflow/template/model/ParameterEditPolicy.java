package ru.moipis.workflow.template.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

public record ParameterEditPolicy(boolean all, Set<String> parameterIds) {
    public ParameterEditPolicy {
        parameterIds = Set.copyOf(parameterIds);
    }

    public boolean enabled() {
        return all || !parameterIds.isEmpty();
    }

    public boolean allows(String parameterId) {
        return all || parameterIds.contains(parameterId);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ParameterEditPolicy fromJson(JsonNode value) {
        if (value.isBoolean()) {
            return new ParameterEditPolicy(value.booleanValue(), Set.of());
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("editParameters must be a boolean or an array of parameter ids");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("Editable parameter id must be a nonblank string");
            }
            ids.add(item.textValue());
        }
        return new ParameterEditPolicy(false, ids);
    }

    @JsonValue
    public Object jsonValue() {
        return all || parameterIds.isEmpty() ? all : parameterIds;
    }
}
