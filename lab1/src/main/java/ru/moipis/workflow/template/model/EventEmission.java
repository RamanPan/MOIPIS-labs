package ru.moipis.workflow.template.model;

public record EventEmission(String event, String recipientLink) {
    public EventEmission {
        if (event == null || event.isBlank() || recipientLink == null || recipientLink.isBlank()) {
            throw new IllegalArgumentException("Event emission requires an event and a recipient link");
        }
    }
}
