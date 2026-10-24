package ru.moipis.workflow.statemachine.event;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.condition.model.ConditionContext;

public record Event(String eventType, JsonNode payload, InitiatorContext initiator, ConditionContext context) {

    public Event(String eventType, JsonNode payload, InitiatorContext initiator) {
        this(eventType, payload, initiator, null);
    }
}
