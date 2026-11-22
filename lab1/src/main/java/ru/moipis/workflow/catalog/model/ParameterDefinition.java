package ru.moipis.workflow.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

public record ParameterDefinition(
        String id,
        String title,
        String type,
        BigDecimal min,
        BigDecimal max,
        @JsonProperty("default") @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode defaultValue
) {
    public ParameterDefinition(String id, String title, String type, BigDecimal min, BigDecimal max) {
        this(id, title, type, min, max, null);
    }
}
