package ru.moipis.workflow.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProcessGraphDto(String templateId, String templateTitle, String currentStateId,
                              List<StateDto> states, List<TransitionDto> transitions) {
    public record StateDto(String id, String title, boolean initial,
                           @JsonProperty("final") boolean terminal, Object editParameters) { }
    public record TransitionDto(String from, String action, String to, String actionTitle) { }
}
