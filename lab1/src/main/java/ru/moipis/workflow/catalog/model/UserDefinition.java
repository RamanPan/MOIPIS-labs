package ru.moipis.workflow.catalog.model;

import java.util.Set;

public record
UserDefinition(String id, String name, Set<String> roles) {
}
