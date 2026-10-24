package ru.moipis.workflow.catalog;

import ru.moipis.workflow.catalog.model.ObjectClassDefinition;
import ru.moipis.workflow.catalog.model.ParameterDefinition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CatalogService {

    private static final Set<String> PARAMETER_TYPES = Set.of("integer", "decimal", "string");

    private final Map<String, ObjectClassDefinition> classes;

    public CatalogService(Collection<ObjectClassDefinition> definitions) {
        if (definitions == null) {
            throw new IllegalArgumentException("Class definitions must not be null");
        }
        Map<String, ObjectClassDefinition> indexedClasses = new LinkedHashMap<>();
        for (ObjectClassDefinition definition : definitions) {
            ObjectClassDefinition snapshot = validatedSnapshot(definition);
            if (indexedClasses.putIfAbsent(snapshot.id(), snapshot) != null) {
                throw new IllegalArgumentException("Duplicate class id: " + snapshot.id());
            }
        }
        classes = Collections.unmodifiableMap(indexedClasses);
        validateParents();
        for (String classId : classes.keySet()) {
            getEffectiveParameters(classId);
        }
    }

    public ObjectClassDefinition getClass(String classId) {
        ObjectClassDefinition definition = classes.get(classId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown class: " + classId);
        }
        return definition;
    }

    public Map<String, ParameterDefinition> getEffectiveParameters(String classId) {
        Map<String, ParameterDefinition> effective = new LinkedHashMap<>();
        for (ObjectClassDefinition definition : getLineage(classId).reversed()) {
            for (ParameterDefinition parameter : definition.parameters()) {
                ParameterDefinition inherited = effective.get(parameter.id());
                if (inherited != null && !inherited.type().equals(parameter.type())) {
                    throw new IllegalArgumentException(
                            "Class '%s' cannot change type of parameter '%s' from '%s' to '%s'"
                                    .formatted(definition.id(), parameter.id(), inherited.type(), parameter.type()));
                }
                effective.put(parameter.id(), parameter);
            }
        }
        return Collections.unmodifiableMap(effective);
    }

    public boolean isInstanceOf(String classId, String expectedClassId) {
        getClass(expectedClassId);
        return getLineage(classId).stream().anyMatch(definition -> definition.id().equals(expectedClassId));
    }

    public List<ObjectClassDefinition> getAncestors(String classId) {
        List<ObjectClassDefinition> lineage = getLineage(classId);
        return List.copyOf(lineage.subList(1, lineage.size()));
    }

    private List<ObjectClassDefinition> getLineage(String classId) {
        List<ObjectClassDefinition> lineage = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ObjectClassDefinition current = getClass(classId);
        while (true) {
            if (!visited.add(current.id())) {
                throw new IllegalArgumentException(
                        "Inheritance cycle for class '%s' at '%s'".formatted(classId, current.id()));
            }
            lineage.add(current);
            if (current.parentId() == null) {
                return lineage;
            }
            current = getClass(current.parentId());
        }
    }

    private void validateParents() {
        for (ObjectClassDefinition definition : classes.values()) {
            if (definition.parentId() != null && !classes.containsKey(definition.parentId())) {
                throw new IllegalArgumentException(
                        "Unknown parent '%s' for class '%s'".formatted(definition.parentId(), definition.id()));
            }
        }
    }

    private static ObjectClassDefinition validatedSnapshot(ObjectClassDefinition definition) {
        if (definition == null || definition.id() == null || definition.id().isBlank()) {
            throw new IllegalArgumentException("Class must have a non-blank id");
        }
        if (definition.parameters() == null) {
            throw new IllegalArgumentException("Missing parameters for class: " + definition.id());
        }
        Set<String> parameterIds = new HashSet<>();
        for (ParameterDefinition parameter : definition.parameters()) {
            validateParameter(definition.id(), parameter);
            if (!parameterIds.add(parameter.id())) {
                throw new IllegalArgumentException(
                        "Duplicate parameter '%s' in class '%s'".formatted(parameter.id(), definition.id()));
            }
        }
        return new ObjectClassDefinition(definition.id(), definition.title(), definition.parentId(),
                List.copyOf(definition.parameters()));
    }

    private static void validateParameter(String classId, ParameterDefinition parameter) {
        if (parameter == null || parameter.id() == null || parameter.id().isBlank()) {
            throw new IllegalArgumentException("Parameter must have a non-blank id in class: " + classId);
        }
        String context = "parameter '%s' in class '%s'".formatted(parameter.id(), classId);
        if (parameter.type() == null || !PARAMETER_TYPES.contains(parameter.type())) {
            throw new IllegalArgumentException("Unsupported type for " + context + ": " + parameter.type());
        }
        if (parameter.type().equals("string") && (parameter.min() != null || parameter.max() != null)) {
            throw new IllegalArgumentException("Numeric bounds are not allowed for string " + context);
        }
        if (parameter.min() != null && parameter.max() != null
                && parameter.min().compareTo(parameter.max()) > 0) {
            throw new IllegalArgumentException("Minimum exceeds maximum for " + context);
        }
        if (parameter.type().equals("integer")
                && (hasFractionalPart(parameter.min()) || hasFractionalPart(parameter.max()))) {
            throw new IllegalArgumentException("Integer bounds must be whole numbers for " + context);
        }
    }

    private static boolean hasFractionalPart(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() > 0;
    }
}
