package ru.moipis.workflow.statemachine.factory;

import ru.moipis.workflow.condition.ConditionEvaluator;
import ru.moipis.workflow.condition.model.PredicateDefinition;
import ru.moipis.workflow.statemachine.DefaultStateMachine;
import ru.moipis.workflow.statemachine.StateMachine;
import ru.moipis.workflow.template.model.StateDefinition;

import java.util.Map;
import java.util.stream.Collectors;

public final class DefaultStateMachineFactory implements StateMachineFactory {

    @Override
    public StateMachine create(String initialStateId) {
        return new DefaultStateMachine(initialStateId);
    }

    @Override
    public StateMachine create(StateMachineConfiguration configuration, ConditionEvaluator evaluator) {
        if (configuration == null || configuration.template() == null || evaluator == null) {
            throw new IllegalArgumentException("Configuration, template and evaluator are required");
        }
        Map<String, PredicateDefinition> predicates = configuration.template().predicates().stream()
                .collect(Collectors.toUnmodifiableMap(PredicateDefinition::id, predicate -> new PredicateDefinition(
                        predicate.id(), predicate.title(), predicate.parameterId(), predicate.operator(),
                        predicate.value() == null ? null : predicate.value().deepCopy(), predicate.classId())));
        StateDefinition initial = configuration.template().states().stream()
                .filter(state -> state.id().equals(configuration.initialStateId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown initial state: " + configuration.initialStateId()));
        StateMachine machine = new DefaultStateMachine(initial,
                (condition, event) -> evaluator.evaluate(condition, predicates, event.context()));
        for (StateDefinition state : configuration.template().states()) {
            if (!state.id().equals(initial.id())) {
                machine.addState(state);
            }
        }
        configuration.template().transitions().forEach(machine::addTransition);
        return machine;
    }
}
