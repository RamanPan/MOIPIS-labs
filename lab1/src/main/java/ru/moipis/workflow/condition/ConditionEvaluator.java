package ru.moipis.workflow.condition;

import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;

import java.util.Map;

public interface ConditionEvaluator {

    boolean evaluate(
            DnfCondition condition,
            Map<String, PredicateDefinition> predicates,
            ConditionContext context
    );

    boolean evaluatePredicate(PredicateDefinition predicate, ConditionContext context);
}
