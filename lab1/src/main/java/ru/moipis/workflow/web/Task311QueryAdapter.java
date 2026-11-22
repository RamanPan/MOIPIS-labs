package ru.moipis.workflow.web;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.NotFoundResponse;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.engine.ConfiguredObject;
import ru.moipis.workflow.engine.CoordinationJournal;
import ru.moipis.workflow.engine.CoordinationSession;
import ru.moipis.workflow.engine.Task311Commands;
import ru.moipis.workflow.engine.WorkflowAccessPolicy;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.web.dto.ObjectDetailsDto;
import ru.moipis.workflow.web.dto.ObjectSummaryDto;
import ru.moipis.workflow.web.dto.ProcessGraphDto;
import ru.moipis.workflow.web.dto.Task311AccessDto;
import ru.moipis.workflow.web.dto.CatalogDtos.ActionDto;
import ru.moipis.workflow.web.dto.CatalogDtos.ParameterDto;
import ru.moipis.workflow.web.dto.CatalogDtos.UserDto;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static ru.moipis.workflow.web.WorkflowQueryAdapter.actionTitle;
import static ru.moipis.workflow.web.WorkflowQueryAdapter.text;

final class Task311QueryAdapter {
    private final Task311Commands commands;
    private final CatalogService classes;

    Task311QueryAdapter(Task311Commands commands) {
        this.commands = commands;
        classes = new CatalogService(commands.catalog().classes());
    }

    List<UserDto> users() {
        return WorkflowQueryAdapter.userDtos(commands.catalog());
    }

    List<ObjectSummaryDto> objects() throws IOException {
        var snapshot = commands.snapshot();
        var updated = new HashMap<String, Instant>();
        for (var entry : snapshot.journal()) {
            if (entry.outcome() == CoordinationJournal.Outcome.SUCCESS) {
                updated.put(entry.objectId(), Instant.parse(entry.timestamp()));
            }
        }
        return commands.objects().stream().map(object -> {
            var classTitle = classes.getClass(object.classId()).title();
            var process = snapshot.processes().get(object.id());
            var state = process == null ? null : state(object, process.stateId());
            var number = text(object.values().get("order_number"));
            var product = text(object.values().get("product_name"));
            var title = product != null ? product : number != null ? number : classTitle + " " + object.id();
            return new ObjectSummaryDto(object.id(), object.classId(), classTitle, number, title,
                    state == null ? null : state.id(), state == null ? null : state.title(), updated.get(object.id()));
        }).sorted(Comparator.comparing(ObjectSummaryDto::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(ObjectSummaryDto::id)).toList();
    }

    ObjectDetailsDto details(String objectId) {
        var object = object(objectId);
        var template = template(object);
        var state = currentState(object);
        var definitions = classes.getEffectiveParameters(object.classId()).values().stream()
                .map(parameter -> new ParameterDto(parameter.id(), parameter.title(), parameter.type(),
                        parameter.min(), parameter.max())).toList();
        return new ObjectDetailsDto(object.id(), object.classId(), classes.getClass(object.classId()).title(),
                state == null ? null : state.id(), state == null ? null : state.title(),
                template == null ? null : template.id(), object.values(), definitions);
    }

    ProcessGraphDto process(String objectId) {
        var object = object(objectId);
        var template = template(object);
        if (template == null) return null;
        return new ProcessGraphDto(template.id(), template.title(), currentStateId(objectId),
                template.states().stream().map(state -> new ProcessGraphDto.StateDto(state.id(), state.title(),
                        state.initial(), state.terminal(), false)).toList(),
                template.transitions().stream().map(transition -> new ProcessGraphDto.TransitionDto(
                        transition.from(), transition.action(), transition.to(),
                        actionTitle(template, transition.action()))).toList());
    }

    Task311AccessDto access(String objectId, String userId) {
        var object = object(objectId);
        var user = user(userId);
        var template = template(object);
        var state = currentState(object);
        var startBlockedReason = startBlockedReason(user, template, state);
        List<Task311AccessDto.StateActionDto> actions = List.of();
        if (state != null && !state.terminal()) {
            boolean allowed = WorkflowAccessPolicy.hasRole(user, state.roles());
            actions = template.transitions().stream().filter(transition -> transition.from().equals(state.id()))
                    .map(transition -> transition.action()).distinct()
                    .map(id -> new Task311AccessDto.StateActionDto(id, actionTitle(template, id), allowed)).toList();
        }
        return new Task311AccessDto(state == null ? null : state.id(), startBlockedReason == null, startBlockedReason,
                actions.stream().filter(Task311AccessDto.StateActionDto::allowedByRole)
                        .map(action -> new ActionDto(action.id(), action.title())).toList(),
                actions, false, Set.of());
    }

    private static String startBlockedReason(UserDefinition user, ProcessTemplate template, StateDefinition state) {
        if (state != null) return "Процесс уже запущен.";
        if (template == null) return "Для этого класса не настроен шаблон.";
        return WorkflowAccessPolicy.hasRole(user, template.createRoles()) ? null : "Недостаточно прав для запуска.";
    }

    private ConfiguredObject object(String id) {
        return commands.objects().stream().filter(object -> object.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundResponse("Заказ не найден"));
    }

    private UserDefinition user(String id) {
        if (id == null || id.isBlank()) throw new BadRequestResponse("Укажите userId");
        return commands.catalog().users().stream().filter(user -> user.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundResponse("Пользователь не найден"));
    }

    private ProcessTemplate template(ConfiguredObject object) {
        return commands.catalog().templates().stream().filter(template -> template.classId().equals(object.classId()))
                .findFirst().orElse(null);
    }

    private String currentStateId(String objectId) {
        return commands.findProcess(objectId).map(CoordinationSession.ProcessSnapshot::stateId).orElse(null);
    }

    private StateDefinition currentState(ConfiguredObject object) {
        String stateId = currentStateId(object.id());
        return stateId == null ? null : state(object, stateId);
    }

    private StateDefinition state(ConfiguredObject object, String stateId) {
        return template(object).states().stream().filter(state -> state.id().equals(stateId)).findFirst().orElseThrow();
    }
}
