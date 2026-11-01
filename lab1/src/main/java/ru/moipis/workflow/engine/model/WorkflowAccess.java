package ru.moipis.workflow.engine.model;

import java.util.List;
import java.util.Set;

/** A display snapshot; every command is checked again inside its object queue. */
public record WorkflowAccess(String stateId, List<String> availableActionIds,
                             boolean canEditParameters, Set<String> editableParameterIds) {
    public WorkflowAccess {
        availableActionIds = List.copyOf(availableActionIds);
        editableParameterIds = Set.copyOf(editableParameterIds);
    }
}
