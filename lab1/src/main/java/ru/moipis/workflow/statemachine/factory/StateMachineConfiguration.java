package ru.moipis.workflow.statemachine.factory;

import ru.moipis.workflow.catalog.model.ParameterDefinition;
import ru.moipis.workflow.template.model.ProcessTemplate;

import java.util.Map;

public record StateMachineConfiguration(
        String classId,
        String initialStateId,
        ProcessTemplate template,
        Map<String, ParameterDefinition> effectiveParameters
) {
}
