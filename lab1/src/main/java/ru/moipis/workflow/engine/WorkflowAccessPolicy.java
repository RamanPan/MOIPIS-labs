package ru.moipis.workflow.engine;

import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.template.model.StateDefinition;

import java.util.Set;

import static ru.moipis.workflow.engine.exception.WorkflowError.*;

final class WorkflowAccessPolicy {
    private WorkflowAccessPolicy() { }

    static boolean hasRole(UserDefinition user, Set<String> roles) {
        return user.roles() != null && roles != null && user.roles().stream().anyMatch(roles::contains);
    }

    static boolean canAct(UserDefinition user, StateDefinition state) {
        return !state.terminal() && hasRole(user, state.roles());
    }

    static void requireStateAccess(UserDefinition user, StateDefinition state) {
        if (state.terminal()) {
            throw new WorkflowException(FINAL_STATE, "Object is in final state: " + state.id());
        }
        if (!hasRole(user, state.roles())) {
            throw new WorkflowException(ACCESS_DENIED, "User has no role for state: " + state.id());
        }
    }
}
