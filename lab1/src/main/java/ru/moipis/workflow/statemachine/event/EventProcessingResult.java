package ru.moipis.workflow.statemachine.event;

import com.fasterxml.jackson.databind.JsonNode;

public record EventProcessingResult(String previousStateId, String currentStateId, JsonNode handlerResult) {
}
