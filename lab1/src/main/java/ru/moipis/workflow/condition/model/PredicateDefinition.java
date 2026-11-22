package ru.moipis.workflow.condition.model;

import com.fasterxml.jackson.databind.JsonNode;

public record PredicateDefinition(
        String id,
        String title,
        String parameterId,
        String operator,
        JsonNode value,
        String classId,
        RelatedProcess relatedProcess
) {

    public PredicateDefinition(String id, String title, String parameterId, String operator, JsonNode value, String classId) {
        this(id, title, parameterId, operator, value, classId, null);
    }

    public PredicateDefinition(String id, String title, String parameterId, String operator, JsonNode value) {
        this(id, title, parameterId, operator, value, null);
    }
}
