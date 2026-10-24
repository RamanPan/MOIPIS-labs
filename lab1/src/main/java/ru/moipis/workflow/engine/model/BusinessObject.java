package ru.moipis.workflow.engine.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record BusinessObject(
        String id,
        String classId,
        String stateId,
        Map<String, JsonNode> parameters,
        String templateId
) {
}
