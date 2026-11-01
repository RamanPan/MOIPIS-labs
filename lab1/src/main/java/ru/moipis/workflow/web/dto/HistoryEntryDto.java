package ru.moipis.workflow.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record HistoryEntryDto(String id, String objectId, String userId, String userName,
                              Instant timestamp, String operation, JsonNode details) { }
