package ru.moipis.workflow.web;

import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import ru.moipis.workflow.engine.CoordinationJournal;
import ru.moipis.workflow.engine.Task311Commands;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.web.dto.ApiError;

import java.io.IOException;
import java.util.Map;

import static ru.moipis.workflow.engine.exception.WorkflowError.PERSISTENCE_ERROR;

final class Task311ApiController {
    private static final Map<String, CommandFailure> FAILURES = Map.ofEntries(
            Map.entry("INVALID_COMMAND", new CommandFailure(400, "Некорректная команда.")),
            Map.entry("UNKNOWN_OBJECT", new CommandFailure(404, "Заказ не найден.")),
            Map.entry("UNKNOWN_USER", new CommandFailure(404, "Пользователь не найден.")),
            Map.entry("ACCESS_DENIED", new CommandFailure(403, "У выбранного пользователя нет необходимой роли.")),
            Map.entry("PROCESS_ALREADY_STARTED", new CommandFailure(409, "Процесс заказа уже запущен.")),
            Map.entry("PROCESS_NOT_STARTED", new CommandFailure(409, "Сначала запустите процесс заказа.")),
            Map.entry("NO_EXACT_TEMPLATE", new CommandFailure(409, "Для класса заказа не настроен шаблон.")),
            Map.entry("INVALID_ACTION", new CommandFailure(409, "Действие недоступно на этом этапе.")),
            Map.entry("CONDITION_NOT_MET", new CommandFailure(409, "Условие перехода не выполнено.")),
            Map.entry("FINAL_STATE", new CommandFailure(409, "Процесс уже завершён.")),
            Map.entry("AMBIGUOUS_TRANSITION", new CommandFailure(409, "Условия допускают несколько переходов.")));
    private static final CommandFailure INTERNAL_FAILURE = new CommandFailure(500, "Не удалось обработать команду.");

    private final Task311Commands commands;
    private final Task311QueryAdapter queries;

    Task311ApiController(Task311Commands commands) {
        this.commands = commands;
        queries = new Task311QueryAdapter(commands);
    }

    void register(RoutesConfig routes) {
        routes.get("/api/meta", ctx -> ctx.json(Map.of("mode", "task-3-1-1")));
        routes.get("/api/users", ctx -> ctx.json(queries.users()));
        routes.get("/api/objects", ctx -> ctx.json(queries.objects()));
        routes.get("/api/objects/{id}", ctx -> ctx.json(queries.details(ctx.pathParam("id"))));
        routes.get("/api/objects/{id}/process", ctx -> {
            var graph = queries.process(ctx.pathParam("id"));
            if (graph == null) ctx.result("null");
            else ctx.json(graph);
        });
        routes.get("/api/objects/{id}/access", ctx -> ctx.json(
                queries.access(ctx.pathParam("id"), ctx.queryParam("userId"))));
        routes.post("/api/commands", this::command);
        routes.get("/api/journal", this::journal);
        routes.get("/api/journal/download", ctx -> ctx.contentType("application/x-ndjson; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"journal.jsonl\"")
                .result(commands.journalText()));
    }

    private void command(Context ctx) {
        CoordinationJournal.Entry entry;
        try {
            entry = commands.submit(ctx.body());
        } catch (IOException failure) {
            throw new WorkflowException(PERSISTENCE_ERROR, "Cannot record command outcome", failure);
        }
        if (entry.outcome() == CoordinationJournal.Outcome.SUCCESS) {
            ctx.json(entry);
            return;
        }
        var failure = FAILURES.getOrDefault(entry.reason(), INTERNAL_FAILURE);
        String code = failure == INTERNAL_FAILURE ? "INTERNAL_ERROR" : entry.reason();
        ctx.status(failure.status()).json(new ApiError(code, entry.explanation() == null ? failure.message() : failure.message() + " " + entry.explanation(), Map.of("entryId", entry.id())));
    }

    private void journal(Context ctx) throws IOException {
        String objectId = ctx.queryParam("objectId");
        var entries = commands.journal();
        ctx.json(objectId == null ? entries : entries.stream().filter(entry -> objectId.equals(entry.objectId())).toList());
    }

    private record CommandFailure(int status, String message) { }
}
