package ru.moipis.workflow.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record CreateObjectRequest(String userId, String classId, Map<String, JsonNode> parameters) { }
