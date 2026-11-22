package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ConfiguredObject(String id, String classId, Map<String, JsonNode> values,
                               Map<String, List<String>> links) {
    public ConfiguredObject {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Configured object requires an ID");
        }
        values = values == null ? Map.of() : Collections.unmodifiableMap(ParameterValues.copy(values));
        links = links == null ? Map.of() : links.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    List<String> linkedObjects(String name) {
        return links.getOrDefault(name, List.of());
    }
}
