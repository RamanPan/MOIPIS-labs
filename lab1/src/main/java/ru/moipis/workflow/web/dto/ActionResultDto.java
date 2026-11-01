package ru.moipis.workflow.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ActionResultDto(String previousStateId, String currentStateId, JsonNode handlerResult) { }
