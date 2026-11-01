package ru.moipis.workflow.web.dto;

import java.util.List;
import java.util.Set;
import ru.moipis.workflow.web.dto.CatalogDtos.ActionDto;

public record ObjectAccessDto(String stateId, boolean canEditParameters, Set<String> editableParameterIds,
                              List<ActionDto> availableActions) { }
