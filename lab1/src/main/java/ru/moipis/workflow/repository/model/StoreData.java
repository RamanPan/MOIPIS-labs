package ru.moipis.workflow.repository.model;

import ru.moipis.workflow.catalog.model.ObjectClassDefinition;
import ru.moipis.workflow.catalog.model.RoleDefinition;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.template.model.ProcessTemplate;

import java.util.List;

public record StoreData(
        int schemaVersion,
        List<ObjectClassDefinition> classes,
        List<RoleDefinition> roles,
        List<UserDefinition> users,
        List<ProcessTemplate> templates,
        List<BusinessObject> objects,
        List<HistoryEntry> history
) {
}
