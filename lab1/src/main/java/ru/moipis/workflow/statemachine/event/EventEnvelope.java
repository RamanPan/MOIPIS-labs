package ru.moipis.workflow.statemachine.event;

import java.util.concurrent.CompletableFuture;

public record EventEnvelope(Event event, CompletableFuture<EventResult> future) {
}
