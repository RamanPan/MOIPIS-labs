package ru.moipis.workflow.template.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

public record StateDefinition(
        String id,
        String title,
        boolean initial,
        @JsonProperty("final") boolean terminal,
        Set<String> roles,
        ParameterEditPolicy editParameters
) {
    public StateDefinition {
        editParameters = editParameters == null ? new ParameterEditPolicy(false, Set.of()) : editParameters;
    }

    public StateDefinition(String id, String title, boolean initial, boolean terminal,
                           Set<String> roles, Set<String> editParameters) {
        this(id, title, initial, terminal, roles,
                new ParameterEditPolicy(false, editParameters == null ? Set.of() : editParameters));
    }

    public StateDefinition(String id, String title, boolean initial, boolean terminal,
                           Set<String> roles, boolean editParameters) {
        this(id, title, initial, terminal, roles, new ParameterEditPolicy(editParameters, Set.of()));
    }
}
