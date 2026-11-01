package ru.moipis.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.engine.WorkflowEngine;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.template.TemplateCatalogService;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.web.dto.*;
import ru.moipis.workflow.web.dto.CatalogDtos.*;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ru.moipis.workflow.engine.exception.WorkflowError.*;

/** Read projections only. All commands and access decisions belong to WorkflowEngine. */
public final class WorkflowQueryAdapter {
    private final Repository repository;
    private final CatalogService catalog;
    private final TemplateCatalogService templates;
    private final WorkflowEngine engine;

    public WorkflowQueryAdapter(Repository repository, CatalogService catalog,
                                TemplateCatalogService templates, WorkflowEngine engine) {
        this.repository = repository;
        this.catalog = catalog;
        this.templates = templates;
        this.engine = engine;
    }

    public List<UserDto> users() {
        StoreData store = load();
        Map<String, String> titles = new HashMap<>();
        store.roles().forEach(role -> titles.put(role.id(), role.title()));
        return store.users().stream().map(user -> {
            List<String> roles = user.roles() == null ? List.of() : user.roles().stream().sorted().toList();
            Map<String, String> roleTitles = new java.util.LinkedHashMap<>();
            roles.forEach(id -> roleTitles.put(id, titles.getOrDefault(id, id)));
            return new UserDto(user.id(), user.name(), roles, roleTitles);
        }).toList();
    }

    public List<ClassDto> classes() {
        return load().classes().stream().map(definition -> new ClassDto(definition.id(), definition.title(), definition.parentId())).toList();
    }

    public List<ParameterDto> parameters(String classId) {
        try {
            catalog.getClass(classId);
        } catch (IllegalArgumentException failure) {
            throw new WorkflowException(NOT_FOUND, "Unknown class: " + classId, failure);
        }
        return catalog.getEffectiveParameters(classId).values().stream().map(parameter -> new ParameterDto(
                parameter.id(), parameter.title(), parameter.type(), parameter.min(), parameter.max())).toList();
    }

    public List<ObjectSummaryDto> objects() {
        StoreData store = load();
        Map<String, Instant> updated = new HashMap<>();
        store.history().forEach(entry -> updated.merge(entry.objectId(), entry.timestamp(),
                (left, right) -> left.isAfter(right) ? left : right));
        return store.objects().stream().map(object -> {
            var template = template(object);
            String classTitle = catalog.getClass(object.classId()).title();
            String number = text(object.parameters().get("order_number"));
            String product = text(object.parameters().get("product_name"));
            String title = product != null ? product : number != null ? number : classTitle + " " + object.id();
            return new ObjectSummaryDto(object.id(), object.classId(), classTitle, number, title,
                    object.stateId(), state(template, object.stateId()).title(), updated.get(object.id()));
        }).sorted(Comparator.comparing(ObjectSummaryDto::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(ObjectSummaryDto::id)).toList();
    }

    public ObjectDetailsDto object(String objectId) {
        return details(engine.getObject(objectId));
    }

    public ObjectDetailsDto details(BusinessObject object) {
        ProcessTemplate template = template(object);
        return new ObjectDetailsDto(object.id(), object.classId(), catalog.getClass(object.classId()).title(),
                object.stateId(), state(template, object.stateId()).title(), template.id(), object.parameters(),
                parameters(object.classId()));
    }

    public ProcessGraphDto process(String objectId) {
        BusinessObject object = engine.getObject(objectId);
        ProcessTemplate template = template(object);
        state(template, object.stateId());
        return new ProcessGraphDto(template.id(), template.title(), object.stateId(),
                template.states().stream().map(s -> new ProcessGraphDto.StateDto(s.id(), s.title(), s.initial(),
                        s.terminal(), s.editParameters().jsonValue())).toList(),
                template.transitions().stream().map(t -> new ProcessGraphDto.TransitionDto(
                        t.from(), t.action(), t.to(), actionTitle(template, t.action()))).toList());
    }

    public ObjectAccessDto access(String userId, String objectId) {
        var access = engine.getAccess(userId, objectId);
        var template = template(engine.getObject(objectId));
        return new ObjectAccessDto(access.stateId(), access.canEditParameters(), access.editableParameterIds(),
                access.availableActionIds().stream().map(id -> new ActionDto(id, actionTitle(template, id))).toList());
    }

    public List<HistoryEntryDto> history(String objectId) {
        Map<String, String> names = new HashMap<>();
        load().users().forEach(user -> names.put(user.id(), user.name()));
        return engine.getHistory(objectId).stream().sorted(Comparator.comparing(HistoryEntry::timestamp))
                .map(entry -> new HistoryEntryDto(entry.id(), entry.objectId(), entry.userId(), names.get(entry.userId()),
                        entry.timestamp(), entry.operation(), entry.details())).toList();
    }

    private StoreData load() {
        try { return repository.load(); }
        catch (IOException failure) { throw new WorkflowException(PERSISTENCE_ERROR, "Cannot read repository", failure); }
    }

    private ProcessTemplate template(BusinessObject object) {
        try {
            ProcessTemplate template = object.templateId() == null ? templates.resolveTemplate(object.classId())
                    : templates.getTemplate(object.templateId());
            if (!catalog.isInstanceOf(object.classId(), template.classId())) {
                throw new WorkflowException(TEMPLATE_ERROR, "Saved template does not apply to object class");
            }
            return template;
        } catch (IOException failure) {
            throw new WorkflowException(PERSISTENCE_ERROR, "Cannot read template", failure);
        } catch (IllegalArgumentException failure) {
            throw new WorkflowException(TEMPLATE_ERROR, "Invalid saved template", failure);
        }
    }

    private static StateDefinition state(ProcessTemplate template, String stateId) {
        return template.states().stream().filter(s -> s.id().equals(stateId)).findFirst()
                .orElseThrow(() -> new WorkflowException(TEMPLATE_ERROR, "Unknown saved state: " + stateId));
    }

    private static String actionTitle(ProcessTemplate template, String actionId) {
        return template.actions().stream().filter(a -> a.id().equals(actionId)).findFirst()
                .map(a -> a.title() == null ? actionId : a.title()).orElse(actionId);
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank() ? null : value.textValue();
    }
}
