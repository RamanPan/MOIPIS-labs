package ru.moipis.workflow.catalog.model;

import java.util.List;

public record ObjectClassDefinition(
        String id,
        String title,
        String parentId,
        List<ParameterDefinition> parameters
) {
}
