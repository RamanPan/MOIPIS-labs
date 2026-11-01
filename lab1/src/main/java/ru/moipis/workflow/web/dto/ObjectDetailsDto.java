package ru.moipis.workflow.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import ru.moipis.workflow.web.dto.CatalogDtos.ParameterDto;

public record ObjectDetailsDto(String id, String classId, String classTitle, String stateId,
                               String stateTitle, String templateId, Map<String, JsonNode> parameters,
                               List<ParameterDto> parameterDefinitions) { }
