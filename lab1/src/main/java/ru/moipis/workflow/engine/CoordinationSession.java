package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;
import ru.moipis.workflow.engine.CoordinationJournal.Entry;
import ru.moipis.workflow.engine.CoordinationJournal.Kind;
import ru.moipis.workflow.engine.CoordinationJournal.Outcome;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.template.model.EventEmission;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ru.moipis.workflow.engine.CoordinationJournal.Kind.*;
import static ru.moipis.workflow.engine.CoordinationJournal.Outcome.*;

public final class CoordinationSession {
    public record ProcessSnapshot(String stateId, String lastActionId) {}
    public record Result(Entry command, List<Entry> deliveries) {}

    private record ProcessMessage(String id, String parentId, String event, String source, String recipient) {}
    private record Step(String id, Kind kind, String objectId, String userId, String action,
                        ProcessMessage message, JsonNode command) {}
    private record Transition(String target, List<EventEmission> emit) {}
    private record ConditionResult(boolean matches, List<String> failedPredicates) {}

    @FunctionalInterface
    private interface Aggregation {
        boolean matches(List<String> objects, java.util.function.Predicate<String> predicate);
    }

    private static final Map<String, Aggregation> AGGREGATIONS =
            Map.of("all", (objects, predicate) -> objects.stream().allMatch(predicate));

    private final Map<String, ConfiguredObject> objects;
    private final Map<String, UserDefinition> users;
    private final Map<String, ProcessTemplate> templates;
    private final Map<String, ProcessSnapshot> processes = new HashMap<>();
    private final Queue<ProcessMessage> queue = new ArrayDeque<>();
    private final DefaultConditionEvaluator evaluator;
    private final CoordinationJournal journal;
    private final CatalogService classes;

    CoordinationSession(Map<String, ConfiguredObject> objects, List<UserDefinition> users,
                        CatalogService classes, List<ProcessTemplate> templates, CoordinationJournal journal,
                        boolean allowAbsentParameters) {
        this.objects = Map.copyOf(objects);
        this.users = index(users, UserDefinition::id);
        this.templates = index(templates, ProcessTemplate::classId);
        this.evaluator = new DefaultConditionEvaluator(classes, allowAbsentParameters);
        this.journal = journal;
        this.classes = classes;
    }

    public synchronized Optional<ProcessSnapshot> findProcess(String objectId) {
        return Optional.ofNullable(processes.get(objectId));
    }

    synchronized Map<String, ProcessSnapshot> processSnapshots() {
        return Map.copyOf(processes);
    }

    public synchronized Result start(String userId, String objectId) throws IOException {
        return execute(new Step(id(), START, objectId, userId, null, null, null));
    }

    public synchronized Result action(String userId, String objectId, String actionId) throws IOException {
        return execute(new Step(id(), ACTION, objectId, userId, actionId, null, null));
    }

    synchronized Entry submit(Kind kind, String userId, String objectId, String actionId, JsonNode command) throws IOException {
        var step = new Step(id(), kind, objectId, userId, actionId, null, command);
        if (kind == null || userId == null || objectId == null || kind == ACTION && actionId == null) {
            var before = processes.get(objectId);
            return record(step, before, before, ERROR, "INVALID_COMMAND");
        }
        return execute(step).command();
    }

    private Result execute(Step step) throws IOException {
        Entry command = process(step);
        var deliveries = new ArrayList<Entry>();
        ProcessMessage message;
        while ((message = queue.poll()) != null) {
            deliveries.add(process(new Step(message.id(), EVENT, message.recipient(), null, null, message, null)));
        }
        return new Result(command, List.copyOf(deliveries));
    }

    private Entry process(Step step) throws IOException {
        ProcessSnapshot before = processes.get(step.objectId());
        var object = step.objectId() == null ? null : objects.get(step.objectId());
        ProcessSnapshot after;
        List<ProcessMessage> outgoing = List.of();
        try {
            require(object != null, "UNKNOWN_OBJECT");
            ProcessTemplate template = templates.get(object.classId());
            if (step.kind() == START) {
                require(before == null, "PROCESS_ALREADY_STARTED");
                var user = user(step.userId());
                require(template != null, "NO_EXACT_TEMPLATE");
                require(WorkflowAccessPolicy.hasRole(user, template.createRoles()), "ACCESS_DENIED");
                StateDefinition initial = exactlyOne(template.states().stream()
                        .filter(StateDefinition::initial).toList(), "INVALID_INITIAL_STATE", ERROR);
                after = new ProcessSnapshot(initial.id(), null);
            } else {
                require(before != null, "PROCESS_NOT_STARTED");
                var state = template.states().stream().filter(candidate -> candidate.id().equals(before.stateId()))
                        .findFirst().orElseThrow();
                require(!state.terminal(), "FINAL_STATE");
                Transition transition = step.kind() == ACTION
                        ? selectAction(step, object, template, state) : selectEvent(step, object, template, state);
                outgoing = outgoing(step, object, transition.emit());
                after = new ProcessSnapshot(transition.target(),
                        step.kind() == ACTION ? step.action() : before.lastActionId());
            }
        } catch (StepFailure failure) {
            return record(step, before, before, failure.outcome, failure.getMessage(), failure.explanation);
        } catch (WorkflowException failure) {
            return record(step, before, before, REJECTED, failure.code().name());
        } catch (IllegalArgumentException failure) {
            return record(step, before, before, ERROR, "TEMPLATE_ERROR: " + failure.getMessage());
        }
        Entry entry = record(step, before, after, SUCCESS, null);
        processes.put(step.objectId(), after);
        queue.addAll(outgoing);
        return entry;
    }

    private Transition selectAction(Step step, ConfiguredObject object, ProcessTemplate template,
                                    StateDefinition state) {
        WorkflowAccessPolicy.requireStateAccess(user(step.userId()), state);
        var candidates = template.transitions().stream()
                .filter(t -> t.from().equals(state.id()) && t.action().equals(step.action())).toList();
        require(!candidates.isEmpty(), "INVALID_ACTION");
        var rejectedConditions = new ArrayList<ConditionResult>();
        var matching = new ArrayList<ru.moipis.workflow.template.model.TransitionDefinition>();
        for (var candidate : candidates) {
            var result = evaluate(candidate.condition(), object, template);
            if (result.matches()) matching.add(candidate);
            else rejectedConditions.add(result);
        }
        if (matching.isEmpty() && !rejectedConditions.isEmpty()) {
            String explanation = rejectedConditions.stream()
                    .map(result -> String.join(". ", result.failedPredicates()))
                    .collect(Collectors.joining(". Другой переход. "));
            throw new StepFailure(REJECTED, "CONDITION_NOT_MET", explanation);
        }
        var transition = exactlyOne(matching.stream()
                .toList(), "CONDITION_NOT_MET", REJECTED);
        return new Transition(transition.to(), transition.emit());
    }

    private Transition selectEvent(Step step, ConfiguredObject object, ProcessTemplate template,
                                   StateDefinition state) {
        ProcessMessage event = step.message();
        var transition = exactlyOne(template.eventTransitions().stream()
                .filter(t -> t.from().equals(state.id()) && t.event().equals(event.event()))
                .filter(t -> object.linkedObjects(t.sourceLink()).contains(event.source()))
                .filter(t -> matches(t.condition(), object, template))
                .toList(), "NO_APPLICABLE_EVENT_TRANSITION", REJECTED);
        return new Transition(transition.to(), transition.emit());
    }

    private boolean matches(DnfCondition condition, ConfiguredObject object, ProcessTemplate template) {
        return evaluate(condition, object, template).matches();
    }

    private ConditionResult evaluate(DnfCondition condition, ConfiguredObject object, ProcessTemplate template) {
        if (condition == null) {
            return new ConditionResult(true, List.of());
        }
        var predicates = index(template.predicates(), PredicateDefinition::id);
        var context = new ConditionContext(object.classId(), object.values());
        var failed = new ArrayList<String>();
        for (int groupIndex = 0; groupIndex < condition.groups().size(); groupIndex++) {
            var group = condition.groups().get(groupIndex);
            var groupFailures = new ArrayList<String>();
            for (var literal : group) {
                var predicate = predicates.get(literal.predicateId());
                boolean value = predicate.relatedProcess() == null
                        ? evaluator.evaluatePredicate(predicate, context)
                        : relatedMatches(predicate, object);
                if (literal.negated()) value = !value;
                if (!value) groupFailures.add(describe(predicate, object, literal.negated()));
            }
            if (groupFailures.isEmpty()) return new ConditionResult(true, List.of());
            failed.add("Вариант " + (groupIndex + 1) + ": " + String.join(", ", groupFailures));
        }
        return new ConditionResult(false, failed);
    }

    private boolean relatedMatches(PredicateDefinition predicate, ConfiguredObject object) {
        var related = predicate.relatedProcess();
        List<String> linked = object.linkedObjects(related.link());
        String expected = predicate.value().textValue();
        return !linked.isEmpty() && AGGREGATIONS.get(related.aggregate()).matches(linked, target -> {
            ProcessSnapshot process = processes.get(target);
            if (process == null) return false;
            String actual = "state".equals(related.field()) ? process.stateId() : process.lastActionId();
            return actual != null && (predicate.operator().equals("=") == actual.equals(expected));
        });
    }

    private String describe(PredicateDefinition predicate, ConfiguredObject object, boolean negated) {
        String expectation = negated ? "должно быть ложным" : "должно быть истинным";
        if (predicate.relatedProcess() != null) return predicate.title() + " — " + expectation;
        if (predicate.classId() != null) return predicate.title() + " (класс " + predicate.classId() + ") — " + expectation;
        var definition = classes.getEffectiveParameters(object.classId()).get(predicate.parameterId());
        var actual = object.values().get(predicate.parameterId());
        String value = actual == null || actual.isNull() ? "не задано" : actual.asText(actual.toString());
        String title = definition == null ? predicate.parameterId() : definition.title();
        String rule = predicate.operator().equals("in_range") && definition != null
                ? "диапазон " + (definition.min() == null ? "без нижней границы" : "от " + definition.min())
                    + " до " + (definition.max() == null ? "без верхней границы" : definition.max())
                : predicate.title();
        return title + " = " + value + ". Условие «" + rule + "» " + expectation;
    }

    private List<ProcessMessage> outgoing(Step step, ConfiguredObject object, List<EventEmission> emissions) {
        List<ProcessMessage> messages = new ArrayList<>();
        for (EventEmission emission : emissions) {
            List<String> targets = object.linkedObjects(emission.recipientLink());
            require(!targets.isEmpty(), "EMPTY_RECIPIENT_LINK: " + emission.recipientLink());
            for (String target : targets) {
                messages.add(new ProcessMessage(id(), step.id(), emission.event(), object.id(), target));
            }
        }
        return messages;
    }

    private UserDefinition user(String id) {
        var user = id == null ? null : users.get(id);
        require(user != null, "UNKNOWN_USER");
        return user;
    }

    private Entry record(Step step, ProcessSnapshot before, ProcessSnapshot after, Outcome outcome, String reason)
            throws IOException {
        return record(step, before, after, outcome, reason, null);
    }

    private Entry record(Step step, ProcessSnapshot before, ProcessSnapshot after, Outcome outcome, String reason,
                         String explanation) throws IOException {
        var message = step.message();
        var entry = new Entry(step.id(), Instant.now().toString(), step.kind(), step.objectId(), step.userId(),
                step.action(), message == null ? null : message.event(), message == null ? null : message.source(),
                message == null ? null : message.recipient(), message == null ? null : message.parentId(),
                before == null ? null : before.stateId(), after == null ? null : after.stateId(), outcome, reason,
                step.command(), explanation);
        journal.append(entry);
        return entry;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static <T> Map<String, T> index(List<T> items, Function<T, String> key) {
        return items.stream().collect(Collectors.toUnmodifiableMap(key, Function.identity()));
    }

    private static void require(boolean rule, String reason) {
        if (!rule) {
            throw new StepFailure(REJECTED, reason);
        }
    }

    private static <T> T exactlyOne(List<T> matches, String missing, Outcome missingOutcome) {
        if (matches.isEmpty()) {
            throw new StepFailure(missingOutcome, missing);
        }
        if (matches.size() > 1) {
            throw new StepFailure(ERROR, "AMBIGUOUS_TRANSITION");
        }
        return matches.getFirst();
    }

    private static final class StepFailure extends RuntimeException {
        private final Outcome outcome;
        private final String explanation;

        private StepFailure(Outcome outcome, String reason) {
            this(outcome, reason, null);
        }

        private StepFailure(Outcome outcome, String reason, String explanation) {
            super(reason);
            this.explanation = explanation;
            this.outcome = outcome;
        }
    }
}
