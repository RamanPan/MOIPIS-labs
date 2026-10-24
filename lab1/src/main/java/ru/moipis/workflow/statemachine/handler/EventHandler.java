package ru.moipis.workflow.statemachine.handler;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.statemachine.event.Event;

@FunctionalInterface
public interface EventHandler {

    JsonNode handle(Event event);
}
