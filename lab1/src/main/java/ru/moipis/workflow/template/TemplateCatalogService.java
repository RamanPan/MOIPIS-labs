package ru.moipis.workflow.template;

import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.ObjectClassDefinition;
import ru.moipis.workflow.catalog.model.RoleDefinition;
import ru.moipis.workflow.condition.model.ConditionLiteral;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.factory.StateMachineConfiguration;
import ru.moipis.workflow.template.model.ActionDefinition;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class TemplateCatalogService {

    private final CatalogService classes;
    private final Repository repository;

    public TemplateCatalogService(CatalogService classes, Repository repository) throws IOException {
        this.classes = required(classes, "Class catalog");
        this.repository = required(repository, "Repository");
        loadTemplates();
    }

    public ProcessTemplate getTemplate(String templateId) throws IOException {
        return snapshot(findTemplate(loadTemplates(), templateId));
    }

    public ProcessTemplate resolveTemplate(String classId) throws IOException {
        return snapshot(resolveTemplate(loadTemplates(), classId));
    }

    public ProcessTemplate copyTemplate(String sourceId, String targetClassId, String newTemplateId) throws IOException {
        StoreData committed = repository.update(store -> prepareTemplateCopy(store, sourceId, targetClassId, newTemplateId));
        return snapshot(committed.templates().stream().filter(template -> template.id().equals(newTemplateId))
                .findFirst().orElseThrow());
    }

    private StoreData prepareTemplateCopy(StoreData store, String sourceId, String targetClassId, String newTemplateId) {
        Templates loaded = indexTemplates(store);
        ProcessTemplate source = findTemplate(loaded, sourceId);
        classes.getClass(targetClassId);
        requireId(newTemplateId, "Template");
        if (loaded.byId().containsKey(newTemplateId)) {
            throw new IllegalArgumentException("Duplicate template id: " + newTemplateId);
        }
        if (loaded.byClass().containsKey(targetClassId)) {
            throw new IllegalArgumentException("Class already has its own template: " + targetClassId);
        }
        ProcessTemplate copied = snapshot(new ProcessTemplate(newTemplateId, source.title(), targetClassId,
                source.createRoles(), source.actions(), source.predicates(), source.states(), source.transitions()));
        validateReferences(copied, indexById(store.roles(), RoleDefinition::id, "Role").keySet());
        List<ProcessTemplate> templates = new ArrayList<>(store.templates());
        templates.add(copied);
        return new StoreData(store.schemaVersion(), store.classes(), store.roles(), store.users(),
                List.copyOf(templates), store.objects(), store.history());
    }

    public List<ProcessTemplate> getTemplates() throws IOException {
        return loadTemplates().byId().values().stream().map(TemplateCatalogService::snapshot).toList();
    }

    public StateMachineConfiguration prepareStateMachineConfiguration(String classId) throws IOException {
        ProcessTemplate template = resolveTemplate(classId);
        List<StateDefinition> initialStates = template.states().stream().filter(StateDefinition::initial).toList();
        if (initialStates.size() != 1) {
            throw new IllegalArgumentException("Template must have exactly one initial state: " + template.id());
        }
        return new StateMachineConfiguration(classId, initialStates.getFirst().id(), template,
                classes.getEffectiveParameters(classId));
    }

    private Templates loadTemplates() throws IOException {
        return indexTemplates(required(repository.load(), "Store"));
    }

    private Templates indexTemplates(StoreData store) {
        Map<String, ProcessTemplate> byId = indexById(store.templates(), ProcessTemplate::id, "Template");
        Set<String> roleIds = indexById(store.roles(), RoleDefinition::id, "Role").keySet();
        Map<String, ProcessTemplate> byClass = new LinkedHashMap<>();
        for (ProcessTemplate template : byId.values()) {
            if (byClass.putIfAbsent(template.classId(), template) != null) {
                throw new IllegalArgumentException("Multiple own templates for class: " + template.classId());
            }
            validateReferences(template, roleIds);
        }
        return new Templates(store, byId, byClass);
    }

    private static ProcessTemplate findTemplate(Templates templates, String templateId) {
        ProcessTemplate template = templates.byId().get(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateId);
        }
        return template;
    }

    private ProcessTemplate resolveTemplate(Templates templates, String classId) {
        classes.getClass(classId);
        ProcessTemplate own = templates.byClass().get(classId);
        if (own != null) {
            return own;
        }
        for (ObjectClassDefinition ancestor : classes.getAncestors(classId)) {
            ProcessTemplate inherited = templates.byClass().get(ancestor.id());
            if (inherited != null) {
                return inherited;
            }
        }
        throw new IllegalArgumentException("No template for class or its ancestors: " + classId);
    }

    private void validateReferences(ProcessTemplate template, Set<String> roleIds) {
        Set<String> parameterIds = classes.getEffectiveParameters(template.classId()).keySet();
        Set<String> actionIds = indexById(template.actions(), ActionDefinition::id, "Action").keySet();
        Map<String, PredicateDefinition> predicates = indexById(template.predicates(), PredicateDefinition::id, "Predicate");
        Map<String, StateDefinition> states = indexById(template.states(), StateDefinition::id, "State");
        requireReferences(template.createRoles(), roleIds, "create roles of template " + template.id());
        for (PredicateDefinition predicate : predicates.values()) {
            if (predicate.classId() != null) {
                if (predicate.parameterId() != null) {
                    throw new IllegalArgumentException("Predicate cannot reference both a class and a parameter: " + predicate.id());
                }
                classes.getClass(predicate.classId());
            } else {
                requireReference(predicate.parameterId(), parameterIds, "parameter of predicate " + predicate.id());
            }
        }
        for (StateDefinition state : states.values()) {
            requireReferences(state.roles(), roleIds, "roles of state " + state.id());
            requireReferences(state.editParameters().parameterIds(), parameterIds, "edit parameters of state " + state.id());
        }
        for (TransitionDefinition transition : required(template.transitions(), "Transitions")) {
            required(transition, "Transition");
            requireReference(transition.from(), states.keySet(), "source state in template " + template.id());
            requireReference(transition.to(), states.keySet(), "target state in template " + template.id());
            requireReference(transition.action(), actionIds, "action in template " + template.id());
            if (transition.condition() != null) {
                for (List<ConditionLiteral> group : required(transition.condition().groups(), "Condition groups")) {
                    for (ConditionLiteral literal : required(group, "Condition group")) {
                        requireReference(required(literal, "Condition literal").predicateId(), predicates.keySet(),
                                "predicate in template " + template.id());
                    }
                }
            }
        }
    }

    private static <Element> Map<String, Element> indexById(
            Collection<Element> elements, Function<Element, String> identifier, String kind) {
        Map<String, Element> indexed = new LinkedHashMap<>();
        for (Element element : required(elements, kind + " definitions")) {
            String id = identifier.apply(required(element, kind));
            requireId(id, kind);
            if (indexed.putIfAbsent(id, element) != null) {
                throw new IllegalArgumentException("Duplicate " + kind + " id: " + id);
            }
        }
        return indexed;
    }

    private static void requireId(String id, String kind) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(kind + " must have a non-blank id");
        }
    }

    private static <Value> Value required(Value value, String description) {
        if (value == null) {
            throw new IllegalArgumentException(description + " must not be null");
        }
        return value;
    }

    private static void requireReference(String id, Set<String> knownIds, String context) {
        if (id == null || !knownIds.contains(id)) {
            throw new IllegalArgumentException("Unknown reference '%s': %s".formatted(id, context));
        }
    }

    private static void requireReferences(Set<String> references, Set<String> knownIds, String context) {
        for (String reference : required(references, context)) {
            requireReference(reference, knownIds, context);
        }
    }

    private static ProcessTemplate snapshot(ProcessTemplate template) {
        return new ProcessTemplate(template.id(), template.title(), template.classId(), copySet(template.createRoles()),
                template.actions().stream().map(action -> new ActionDefinition(action.id(), action.title())).toList(),
                template.predicates().stream().map(predicate -> new PredicateDefinition(predicate.id(), predicate.title(),
                        predicate.parameterId(), predicate.operator(),
                        predicate.value() == null ? null : predicate.value().deepCopy(), predicate.classId())).toList(),
                template.states().stream().map(state -> new StateDefinition(state.id(), state.title(), state.initial(),
                        state.terminal(), copySet(state.roles()), state.editParameters())).toList(),
                template.transitions().stream().map(transition -> new TransitionDefinition(transition.from(),
                        transition.action(), transition.to(), copyCondition(transition.condition()))).toList());
    }

    private static Set<String> copySet(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static DnfCondition copyCondition(DnfCondition condition) {
        if (condition == null) {
            return null;
        }
        return new DnfCondition(condition.groups().stream().map(group -> group.stream()
                .map(literal -> new ConditionLiteral(literal.predicateId(), literal.negated())).toList()).toList());
    }

    private record Templates(
            StoreData store,
            Map<String, ProcessTemplate> byId,
            Map<String, ProcessTemplate> byClass
    ) {
    }
}
