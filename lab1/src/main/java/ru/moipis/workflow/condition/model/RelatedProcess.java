package ru.moipis.workflow.condition.model;

public record RelatedProcess(String link, String field, String aggregate) {
    public RelatedProcess {
        if (link == null || link.isBlank()
                || !("state".equals(field) || "last_action".equals(field))
                || !"all".equals(aggregate)) {
            throw new IllegalArgumentException("Related process requires a link, state or last_action, and aggregate all");
        }
    }
}
