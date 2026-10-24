package ru.moipis.workflow.repository.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;

import java.time.Clock;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ObjectChangeApplier {

    private ObjectChangeApplier() {
    }

    static StoreData apply(StoreData snapshot, ObjectChange change, Clock clock) {
        BusinessObject after = change.after();
        if (after == null || after.parameters() == null) {
            throw new IllegalArgumentException("Prepared object and parameters are required");
        }
        requireId(after.id(), "Object id");
        requireId(after.classId(), "Object class");
        requireId(after.stateId(), "Object state");
        requireId(change.userId(), "Initiator");
        requireId(change.operation(), "Operation");
        BusinessObject existing = snapshot.objects().stream()
                .filter(object -> object.id().equals(after.id())).findFirst().orElse(null);
        ObjectNode details;
        if (change.operation().equals("CREATE")) {
            if (change.before() != null || existing != null || change.actionId() != null || change.parameterId() != null) {
                throw new IllegalArgumentException("CREATE requires a new object: " + after.id());
            }
            details = JsonNodeFactory.instance.objectNode().put("initialState", after.stateId());
            ObjectNode parameters = details.putObject("parameters");
            after.parameters().forEach((parameterId, value) -> parameters.set(parameterId, jsonValue(value)));
        } else {
            if (existing == null || change.before() == null) {
                throw new IllegalArgumentException("Existing object and expected snapshot are required: " + after.id());
            }
            if (!existing.equals(change.before())) {
                throw new ConcurrentModificationException("Object has changed since preparation: " + after.id());
            }
            if (!existing.id().equals(after.id()) || !existing.classId().equals(after.classId())
                    || !Objects.equals(existing.templateId(), after.templateId())) {
                throw new IllegalArgumentException("Operation cannot change object identity, class or template");
            }
            details = switch (change.operation()) {
                case "ACTION" -> actionDetails(change, existing, after);
                case "UPDATE_PARAMETER" -> parameterDetails(change, existing, after);
                default -> throw new IllegalArgumentException("Unsupported operation: " + change.operation());
            };
        }
        HistoryEntry entry = new HistoryEntry(UUID.randomUUID().toString(), after.id(), change.userId(),
                clock.instant(), change.operation(), details);
        List<BusinessObject> objects = new ArrayList<>(snapshot.objects());
        if (existing == null) {
            objects.add(after);
        } else {
            objects.set(objects.indexOf(existing), after);
        }
        List<HistoryEntry> history = new ArrayList<>(snapshot.history());
        history.add(entry);
        return new StoreData(snapshot.schemaVersion(), snapshot.classes(), snapshot.roles(), snapshot.users(),
                snapshot.templates(), objects, history);
    }

    private static ObjectNode actionDetails(ObjectChange change, BusinessObject before, BusinessObject after) {
        requireId(change.actionId(), "Action id");
        if (change.parameterId() != null || !before.parameters().equals(after.parameters())) {
            throw new IllegalArgumentException("ACTION cannot change parameters");
        }
        return JsonNodeFactory.instance.objectNode().put("actionId", change.actionId())
                .put("oldState", before.stateId()).put("newState", after.stateId());
    }

    private static ObjectNode parameterDetails(ObjectChange change, BusinessObject before, BusinessObject after) {
        requireId(change.parameterId(), "Parameter id");
        Map<String, JsonNode> oldOtherValues = new HashMap<>(before.parameters());
        Map<String, JsonNode> newOtherValues = new HashMap<>(after.parameters());
        oldOtherValues.remove(change.parameterId());
        newOtherValues.remove(change.parameterId());
        if (change.actionId() != null || !before.stateId().equals(after.stateId()) || !oldOtherValues.equals(newOtherValues)) {
            throw new IllegalArgumentException("UPDATE_PARAMETER may change only its named parameter");
        }
        ObjectNode details = JsonNodeFactory.instance.objectNode().put("parameterId", change.parameterId());
        details.set("oldValue", jsonValue(before.parameters().get(change.parameterId())));
        details.set("newValue", jsonValue(after.parameters().get(change.parameterId())));
        return details;
    }

    private static JsonNode jsonValue(JsonNode value) {
        return value == null ? NullNode.instance : value.deepCopy();
    }

    private static void requireId(String id, String description) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(description + " must have a non-blank id");
        }
    }
}
