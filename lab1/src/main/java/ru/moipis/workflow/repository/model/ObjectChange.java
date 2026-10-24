package ru.moipis.workflow.repository.model;

import ru.moipis.workflow.engine.model.BusinessObject;

public record ObjectChange(
        BusinessObject before,
        BusinessObject after,
        String userId,
        String operation,
        String actionId,
        String parameterId
) {

    public static ObjectChange create(BusinessObject object, String userId) {
        return new ObjectChange(null, object, userId, "CREATE", null, null);
    }

    public static ObjectChange action(BusinessObject before, BusinessObject after, String userId, String actionId) {
        return new ObjectChange(before, after, userId, "ACTION", actionId, null);
    }

    public static ObjectChange updateParameter(BusinessObject before, BusinessObject after, String userId, String parameterId) {
        return new ObjectChange(before, after, userId, "UPDATE_PARAMETER", null, parameterId);
    }
}
