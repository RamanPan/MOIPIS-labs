package ru.moipis.workflow.web.dto;

import ru.moipis.workflow.web.dto.CatalogDtos.ActionDto;

import java.util.List;
import java.util.Set;

public record Task311AccessDto(String stateId, boolean canStart, String startBlockedReason,
                               List<ActionDto> availableActions, List<StateActionDto> stateActions,
                               boolean canEditParameters, Set<String> editableParameterIds) {
    public record StateActionDto(String id, String title, boolean allowedByRole) { }
}
