package ru.moipis.workflow.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.RoutesConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.UnsupportedMediaTypeResponse;
import ru.moipis.workflow.engine.WorkflowEngine;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.web.dto.*;

public final class WorkflowApiController {
    private final WorkflowEngine engine;
    private final WorkflowQueryAdapter queries;
    private final ObjectMapper mapper;

    public WorkflowApiController(WorkflowEngine engine, WorkflowQueryAdapter queries, ObjectMapper mapper) {
        this.engine = engine;
        this.queries = queries;
        this.mapper = mapper;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/users", ctx -> ctx.json(queries.users()));
        routes.get("/api/classes", ctx -> ctx.json(queries.classes()));
        routes.get("/api/classes/{classId}/parameters", ctx -> ctx.json(queries.parameters(ctx.pathParam("classId"))));
        routes.get("/api/objects", ctx -> ctx.json(queries.objects()));
        routes.post("/api/objects", this::create);
        routes.get("/api/objects/{id}", ctx -> ctx.json(queries.object(ctx.pathParam("id"))));
        routes.get("/api/objects/{id}/process", ctx -> ctx.json(queries.process(ctx.pathParam("id"))));
        routes.get("/api/objects/{id}/access", ctx -> ctx.json(queries.access(
                identifier(ctx.queryParam("userId"), "userId"), ctx.pathParam("id"))));
        routes.get("/api/objects/{id}/history", ctx -> ctx.json(queries.history(ctx.pathParam("id"))));
        routes.patch("/api/objects/{id}/parameters/{parameterId}", this::update);
        routes.post("/api/objects/{id}/actions", this::perform);
    }

    private void create(Context ctx) {
        CreateObjectRequest request = body(ctx, CreateObjectRequest.class);
        identifier(request.userId(), "userId");
        identifier(request.classId(), "classId");
        if (request.parameters() == null) throw new BadRequestResponse("parameters must be a JSON object");
        ctx.future(() -> engine.createObject(request.userId(), request.classId(), request.parameters())
                .thenAccept(object -> ctx.status(201).header("Location", "/api/objects/" + object.id())
                        .json(queries.details(object)))
                .exceptionally(failure -> { WebExceptionMapper.write(failure, ctx); return null; }));
    }

    private void perform(Context ctx) {
        PerformActionRequest request = body(ctx, PerformActionRequest.class);
        identifier(request.userId(), "userId");
        identifier(request.actionId(), "actionId");
        String objectId = ctx.pathParam("id");
        ctx.future(() -> engine.executeAction(request.userId(), objectId, request.actionId())
                .thenAccept(result -> ctx.json(result(result)))
                .exceptionally(failure -> { WebExceptionMapper.write(failure, ctx); return null; }));
    }

    private void update(Context ctx) {
        UpdateParameterRequest request = body(ctx, UpdateParameterRequest.class, "value");
        identifier(request.userId(), "userId");
        String objectId = ctx.pathParam("id"), parameterId = ctx.pathParam("parameterId");
        ctx.future(() -> engine.updateParameter(request.userId(), objectId, parameterId, request.value())
                .thenAccept(result -> ctx.json(result(result)))
                .exceptionally(failure -> { WebExceptionMapper.write(failure, ctx); return null; }));
    }

    private <T> T body(Context ctx, Class<T> type, String... presentFields) {
        String contentType = ctx.contentType();
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new UnsupportedMediaTypeResponse("Expected application/json");
        }
        try {
            JsonNode json = mapper.readTree(ctx.body());
            if (json == null || !json.isObject()) throw new BadRequestResponse("Expected a JSON object");
            for (String field : presentFields) {
                if (!json.has(field)) throw new BadRequestResponse("Missing field: " + field);
            }
            return mapper.treeToValue(json, type);
        } catch (JsonProcessingException failure) {
            throw new BadRequestResponse("Invalid JSON request");
        }
    }

    private static String identifier(String value, String field) {
        if (value == null || value.isBlank()) throw new BadRequestResponse("Missing or blank field: " + field);
        return value;
    }

    private static ActionResultDto result(EventResult result) {
        return new ActionResultDto(result.previousStateId(), result.currentStateId(), result.handlerResult());
    }
}
