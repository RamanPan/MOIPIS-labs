package ru.moipis.workflow.web;

import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.statemachine.exception.AmbiguousTransitionException;
import ru.moipis.workflow.web.dto.ApiError;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class WebExceptionMapper {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WebExceptionMapper.class);
    private WebExceptionMapper() { }

    public static void write(Throwable failure, Context ctx) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        int status;
        String code;
        String message;
        if (failure instanceof WorkflowException workflow) {
            status = switch (workflow.code()) {
                case NOT_FOUND -> 404;
                case ACCESS_DENIED -> 403;
                case UNKNOWN_PARAMETER, INVALID_PARAMETER_TYPE -> 400;
                case INVALID_ACTION, CONDITION_NOT_MET, FINAL_STATE, CONCURRENT_MODIFICATION -> 409;
                case TEMPLATE_ERROR -> workflow.getCause() instanceof AmbiguousTransitionException ? 409 : 500;
                case ENGINE_CLOSED -> 503;
                case PERSISTENCE_ERROR, HANDLER_ERROR -> 500;
            };
            code = workflow.code().name();
            message = status >= 500 ? "Операция временно недоступна" : workflow.getMessage();
        } else if (failure instanceof HttpResponseException http) {
            status = http.getStatus();
            code = switch (status) {
                case 404 -> "NOT_FOUND";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "INVALID_REQUEST";
            };
            message = status >= 500 ? "Внутренняя ошибка сервера" : http.getMessage();
        } else {
            status = 500;
            code = "INTERNAL_ERROR";
            message = "Внутренняя ошибка сервера";
        }
        if (status >= 500) LOG.error("HTTP operation failed: {} {}", ctx.method(), ctx.path(), failure);
        ctx.status(status).json(new ApiError(code, message, Map.of()));
    }
}
