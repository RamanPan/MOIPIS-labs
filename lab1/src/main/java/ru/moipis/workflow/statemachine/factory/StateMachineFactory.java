package ru.moipis.workflow.statemachine.factory;

import ru.moipis.workflow.condition.ConditionEvaluator;
import ru.moipis.workflow.statemachine.StateMachine;

public interface StateMachineFactory {

    StateMachine create(String initialStateId);

    StateMachine create(StateMachineConfiguration configuration, ConditionEvaluator evaluator);
}
