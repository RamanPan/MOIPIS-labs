package ru.moipis.workflow.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record UpdateParameterRequest(String userId, JsonNode value) { }
