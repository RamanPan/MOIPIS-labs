package ru.moipis.workflow.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.http.HttpResponseException;
import io.javalin.json.JavalinJackson;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.engine.WorkflowEngine;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.template.TemplateCatalogService;
import ru.moipis.workflow.web.dto.ApiError;

import java.util.Map;

public final class WebServerFactory {
    private WebServerFactory() { }

    public static Javalin create(WorkflowEngine engine, Repository repository,
                                 CatalogService catalog, TemplateCatalogService templates) {
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        var controller = new WorkflowApiController(engine,
                new WorkflowQueryAdapter(repository, catalog, templates, engine), mapper);
        return Javalin.create(config -> {
            config.jetty.host = "127.0.0.1";
            config.jetty.port = 7070;
            config.jetty.modifyServer(server -> server.setStopTimeout(10_000));
            config.http.defaultContentType = "application/json";
            config.jsonMapper(new JavalinJackson(mapper, false));
            config.staticFiles.enableWebjars();
            config.staticFiles.add(files -> {
                files.hostedPath = "/";
                files.directory = "/public";
                files.location = Location.CLASSPATH;
            });
            controller.register(config.routes);
            config.routes.exception(Exception.class, WebExceptionMapper::write);
            config.routes.exception(HttpResponseException.class, WebExceptionMapper::write);
            config.routes.error(404, ctx -> ctx.json(new ApiError("NOT_FOUND", "Ресурс не найден", Map.of())));
        });
    }
}
