package ru.moipis.workflow.engine.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record HistoryEntry(
        String id,
        String objectId,
        String userId,
        Instant timestamp,
        String operation,
        JsonNode details
) {
}
