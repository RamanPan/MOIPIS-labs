package ru.moipis.workflow.web.dto;

import java.time.Instant;

public record ObjectSummaryDto(String id, String classId, String classTitle, String orderNumber,
                               String displayTitle, String stateId, String stateTitle, Instant updatedAt) { }
