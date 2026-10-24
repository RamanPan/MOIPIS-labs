package ru.moipis.workflow.condition.model;

import java.util.List;

public record DnfCondition(List<List<ConditionLiteral>> groups) {
}
