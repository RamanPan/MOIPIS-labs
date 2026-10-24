package ru.moipis.workflow.catalog.model;

import java.math.BigDecimal;

public record ParameterDefinition(
        String id,
        String title,
        String type,
        BigDecimal min,
        BigDecimal max
) {
}
