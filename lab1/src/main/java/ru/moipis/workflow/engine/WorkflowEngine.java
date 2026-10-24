package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.statemachine.handler.EventHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface WorkflowEngine extends AutoCloseable {

    CompletableFuture<BusinessObject> createObject(String userId, String classId, Map<String, JsonNode> parameters);

    CompletableFuture<EventResult> executeAction(String userId, String objectId, String actionId);

    CompletableFuture<EventResult> updateParameter(String userId, String objectId, String parameterId, JsonNode value);

    BusinessObject getObject(String objectId);

    List<String> getAvailableActions(String userId, String objectId);

    List<HistoryEntry> getHistory(String objectId);

    void registerHandler(String actionId, EventHandler handler);

    @Override
    void close();
}
