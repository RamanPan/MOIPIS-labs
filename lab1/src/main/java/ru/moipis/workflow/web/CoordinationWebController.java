package ru.moipis.workflow.web;

import io.javalin.config.RoutesConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import ru.moipis.workflow.catalog.model.ObjectClassDefinition;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.engine.ConfiguredObject;
import ru.moipis.workflow.engine.CoordinationJournal;
import ru.moipis.workflow.engine.CoordinationScenario;
import ru.moipis.workflow.engine.CoordinationSession;
import ru.moipis.workflow.engine.WorkflowAccessPolicy;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class CoordinationWebController {
    private final List<ConfiguredObject> objects;
    private final List<UserDefinition> users;
    private final Map<String, ProcessTemplate> templates;
    private final Map<String, String> classTitles;
    private final CoordinationSession session;

    CoordinationWebController(CoordinationScenario scenario, CoordinationSession session) {
        objects = scenario.objects();
        users = scenario.catalog().users();
        templates = scenario.catalog().templates().stream()
                .collect(Collectors.toUnmodifiableMap(ProcessTemplate::classId, Function.identity()));
        classTitles = scenario.catalog().classes().stream()
                .collect(Collectors.toUnmodifiableMap(ObjectClassDefinition::id, ObjectClassDefinition::title));
        this.session = session;
    }

    void register(RoutesConfig routes) {
        routes.get("/", ctx -> ctx.redirect("/coordination.html"));
        routes.get("/api/coordination", this::snapshot);
        routes.post("/api/coordination/commands", this::command);
        routes.get("/api/coordination/journal/download", ctx -> ctx
                .contentType("application/x-ndjson; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"journal.jsonl\"")
                .result(session.journalText()));
    }

    /** Возвращает карточки обоих процессов и журнал из одного снимка сеанса. */
    private void snapshot(Context ctx) throws IOException {
        var snapshot = session.snapshot();
        ctx.json(new Dashboard(objects.stream()
                .map(object -> process(object, snapshot.processes().get(object.id()))).toList(), snapshot.journal()));
    }

    private ProcessView process(ConfiguredObject object, CoordinationSession.ProcessSnapshot snapshot) {
        String stateId = snapshot == null ? null : snapshot.stateId();
        String lastActionId = snapshot == null ? null : snapshot.lastActionId();
        var template = templates.get(object.classId());
        String classTitle = classTitles.get(object.classId());
        if (template == null) {
            return new ProcessView(object.id(), classTitle, classTitle, null, null, List.of(), List.of(), object.links());
        }
        var states = template.states().stream().map(state -> new Stage(state.id(), state.title(), state.terminal())).toList();
        return new ProcessView(object.id(), classTitle, template.title(), stateId, lastActionId, states,
                operations(template, stateId), object.links());
    }

    /** Подбирает пользователя по роли и формирует операции для текущего состояния. */
    private List<Operation> operations(ProcessTemplate template, String stateId) {
        if (stateId == null) {
            return users.stream().filter(user -> WorkflowAccessPolicy.hasRole(user, template.createRoles()))
                    .limit(1).map(user -> new Operation("start", null, "Запустить процесс", user.id(), user.name()))
                    .toList();
        }
        StateDefinition state = template.states().stream().filter(candidate -> stateId.equals(candidate.id()))
                .findFirst().orElseThrow();
        if (state.terminal()) return List.of();
        var actor = users.stream().filter(user -> WorkflowAccessPolicy.hasRole(user, state.roles())).findFirst();
        if (actor.isEmpty()) return List.of();
        UserDefinition user = actor.get();
        Set<String> outgoing = template.transitions().stream().filter(transition -> stateId.equals(transition.from()))
                .map(TransitionDefinition::action).collect(Collectors.toSet());
        return template.actions().stream().filter(action -> outgoing.contains(action.id()))
                .map(action -> new Operation("action", action.id(), action.title(), user.id(), user.name())).toList();
    }

    /**
     * Проверяет поля запроса и передаёт запуск или действие в сеанс 3-1-2.
     * Ответ включает исходную команду и все попытки доставки её событий.
     */
    private void command(Context ctx) throws IOException {
        var request = ctx.bodyAsClass(Command.class);
        if (request == null || blank(request.type()) || blank(request.userId()) || blank(request.objectId())) {
            throw new BadRequestResponse("Укажите тип команды, пользователя и объект.");
        }
        var result = switch (request.type()) {
            case "start" -> session.start(request.userId(), request.objectId());
            case "action" -> {
                if (blank(request.actionId())) throw new BadRequestResponse("Укажите действие.");
                yield session.action(request.userId(), request.objectId(), request.actionId());
            }
            default -> throw new BadRequestResponse("Поддерживаются команды start и action.");
        };
        ctx.status(httpStatus(result.command())).json(result);
    }

    private static int httpStatus(CoordinationJournal.Entry entry) {
        if (entry.outcome() == CoordinationJournal.Outcome.SUCCESS) return 200;
        return switch (entry.reason()) {
            case "UNKNOWN_OBJECT", "UNKNOWN_USER" -> 404;
            case "ACCESS_DENIED" -> 403;
            default -> 409;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record Command(String type, String userId, String objectId, String actionId) {}
    record Dashboard(List<ProcessView> processes, List<CoordinationJournal.Entry> journal) {}
    record ProcessView(String id, String classTitle, String title, String stateId, String lastActionId,
                       List<Stage> states, List<Operation> operations, Map<String, List<String>> links) {}
    record Stage(String id, String title, boolean terminal) {}
    record Operation(String type, String id, String title, String userId, String userName) {}
}
