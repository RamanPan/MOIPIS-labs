package ru.moipis.workflow.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.condition.ConditionEvaluator;
import ru.moipis.workflow.condition.DefaultConditionEvaluator;
import ru.moipis.workflow.condition.model.ConditionContext;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;
import ru.moipis.workflow.engine.DefaultWorkflowEngine;
import ru.moipis.workflow.engine.WorkflowEngine;
import ru.moipis.workflow.engine.exception.WorkflowError;
import ru.moipis.workflow.engine.exception.WorkflowException;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.file.FileRepository;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.DefaultStateMachine;
import ru.moipis.workflow.statemachine.StateMachine;
import ru.moipis.workflow.statemachine.event.Event;
import ru.moipis.workflow.statemachine.event.EventResult;
import ru.moipis.workflow.statemachine.factory.DefaultStateMachineFactory;
import ru.moipis.workflow.template.TemplateCatalogService;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class DemoApplication {
    private DemoApplication() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("DemoApplication does not accept a store path; each run uses a fresh demo directory");
        }
        PrintStream output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        run(Path.of("demo"), output, Clock.systemUTC());
        output.flush();
    }

    static Result run(Path demoRoot, PrintStream output, Clock clock) throws Exception {
        step(output, 1, "Инициализация отдельного хранилища и каталогов");
        Files.createDirectories(demoRoot);
        Path storePath = Files.createTempDirectory(demoRoot.toAbsolutePath(), "run-").resolve("store.json");
        FileRepository repository = new FileRepository(storePath, clock);
        StoreData seed = repository.loadOrInitialize();
        CatalogService classes = new CatalogService(seed.classes());
        TemplateCatalogService templates = new TemplateCatalogService(classes, repository);
        ConditionEvaluator evaluator = new DefaultConditionEvaluator(classes);
        output.println("DEMO STORE " + storePath);
        output.println("TEMPLATE " + templates.resolveTemplate("office_order").id() + " (наследуется от standard_order)");
        try (WorkflowEngine engine = new DefaultWorkflowEngine(classes, templates, evaluator, repository, new DefaultStateMachineFactory())) {
            step(output, 2, "Создание office_order менеджером заказа");
            Map<String, JsonNode> officeValues = parameters(seed, "demo-office-valid");
            officeValues.put("order_number", TextNode.valueOf("DEMO-OFFICE"));
            BusinessObject office = await(engine.createObject("user_manager", "office_order", officeValues));
            output.println("CREATED office_order " + office.id() + " state=" + office.stateId());

            step(output, 3, "Установка quantity=700 без проверки диапазона при записи");
            update(engine, office.id(), "user_manager", "quantity", IntNode.valueOf(700), output);
            step(output, 4, "Завершение регистрации");
            action(engine, office.id(), "user_manager", "complete", output);
            step(output, 5, "Согласование состава отклоняется: 700 не входит в 1..500");
            expectRejection(engine, office.id(), WorkflowError.CONDITION_NOT_MET,
                    () -> engine.executeAction("user_manager", office.id(), "approve"), output);
            step(output, 6, "Исправление quantity=100");
            update(engine, office.id(), "user_manager", "quantity", IntNode.valueOf(100), output);
            step(output, 7, "Успешное согласование состава");
            action(engine, office.id(), "user_manager", "approve", output);
            step(output, 8, "Согласование цены");
            action(engine, office.id(), "user_procurement", "approve", output);
            step(output, 9, "Логист возвращает заказ на согласование состава");
            action(engine, office.id(), "user_logistics", "correction", output);
            step(output, 10, "Повторные согласования состава, цены и графика поставки");
            action(engine, office.id(), "user_manager", "approve", output);
            action(engine, office.id(), "user_procurement", "approve", output);
            action(engine, office.id(), "user_logistics", "approve", output);
            step(output, 11, "Финансист не может редактировать параметры: editParameters=false");
            expectRejection(engine, office.id(), WorkflowError.ACCESS_DENIED,
                    () -> engine.updateParameter("user_finance", office.id(), "quantity", IntNode.valueOf(1)), output);
            step(output, 12, "Менеджер не имеет роли согласования оплаты");
            expectRejection(engine, office.id(), WorkflowError.ACCESS_DENIED,
                    () -> engine.executeAction("user_manager", office.id(), "approve"), output);

            step(output, 13, "Две независимые успешные ветви OR: standard 20%, office 40%");
            Map<String, JsonNode> standardValues = parameters(seed, "demo-standard-valid");
            standardValues.put("order_number", TextNode.valueOf("DEMO-STANDARD"));
            BusinessObject standard = await(engine.createObject("user_manager", "standard_order", standardValues));
            action(engine, standard.id(), "user_manager", "complete", output);
            action(engine, standard.id(), "user_manager", "approve", output);
            action(engine, standard.id(), "user_procurement", "approve", output);
            action(engine, standard.id(), "user_logistics", "approve", output);
            List<Boolean> officeBranches = paymentBranches(engine.getObject(office.id()), templates, evaluator, output);
            List<Boolean> standardBranches = paymentBranches(engine.getObject(standard.id()), templates, evaluator, output);

            step(output, 14, "Завершение обоих заказов через реальный WorkflowEngine");
            for (String objectId : List.of(office.id(), standard.id())) {
                action(engine, objectId, "user_finance", "approve", output);
                action(engine, objectId, "user_head", "execute", output);
                action(engine, objectId, "user_executor", "complete", output);
            }
            step(output, 15, "Действие после completed запрещено");
            expectRejection(engine, office.id(), WorkflowError.FINAL_STATE,
                    () -> engine.executeAction("user_executor", office.id(), "complete"), output);
            step(output, 16, "История принятых операций; отказы записей не добавляют");
            printHistory(engine, office.id(), "office_order", output);
            printHistory(engine, standard.id(), "standard_order", output);
            String dynamicState = demonstrateDynamicRegistration(output);
            output.println("DEMO OK: оба заказа completed; все workers будут закрыты");
            return new Result(storePath, office.id(), standard.id(), officeBranches, standardBranches, dynamicState);
        }
    }

    private static Map<String, JsonNode> parameters(StoreData seed, String sampleId) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        seed.objects().stream().filter(object -> object.id().equals(sampleId)).findFirst().orElseThrow()
                .parameters().forEach((id, value) -> values.put(id, value == null ? null : value.deepCopy()));
        return values;
    }

    private static void step(PrintStream output, int number, String title) {
        output.printf("%n%02d. %s%n", number, title);
    }

    private static void action(WorkflowEngine engine, String objectId, String userId, String actionId, PrintStream output) throws Exception {
        EventResult result = await(engine.executeAction(userId, objectId, actionId));
        output.printf("ACTION %s %s: %s -> %s%n", userId, actionId, result.previousStateId(), result.currentStateId());
    }

    private static void update(WorkflowEngine engine, String objectId, String userId, String parameterId,
                               JsonNode value, PrintStream output) throws Exception {
        EventResult result = await(engine.updateParameter(userId, objectId, parameterId, value));
        output.printf("UPDATE %s %s=%s; state=%s%n", userId, parameterId, value, result.currentStateId());
    }

    private static void expectRejection(WorkflowEngine engine, String objectId, WorkflowError expected,
                                         Supplier<? extends CompletableFuture<?>> operation, PrintStream output) throws Exception {
        BusinessObject before = engine.getObject(objectId);
        List<HistoryEntry> history = engine.getHistory(objectId);
        try {
            await(operation.get());
            throw new IllegalStateException("Expected rejection did not occur: " + expected);
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof WorkflowException rejection) || rejection.code() != expected) {
                throw failure;
            }
            if (!before.equals(engine.getObject(objectId)) || !history.equals(engine.getHistory(objectId))) {
                throw new IllegalStateException("Rejected operation changed the object or history", failure);
            }
            output.printf("EXPECTED REJECTION %s: %s; объект и история не изменены%n", rejection.code(), rejection.getMessage());
        }
    }

    private static List<Boolean> paymentBranches(BusinessObject object, TemplateCatalogService templates,
                                                  ConditionEvaluator evaluator, PrintStream output) throws Exception {
        ProcessTemplate template = templates.getTemplate(object.templateId());
        TransitionDefinition transition = template.transitions().stream().filter(candidate -> candidate.from().equals(object.stateId())
                && candidate.action().equals("approve")).findFirst().orElseThrow();
        Map<String, PredicateDefinition> predicates = template.predicates().stream()
                .collect(Collectors.toMap(PredicateDefinition::id, predicate -> predicate));
        List<Boolean> branches = new ArrayList<>();
        for (var group : transition.condition().groups()) {
            boolean value = evaluator.evaluate(new DnfCondition(List.of(group)), predicates, ConditionContext.from(object));
            branches.add(value);
            output.printf("OR %s branch %d = %s%n", object.classId(), branches.size(), value);
        }
        return List.copyOf(branches);
    }

    private static void printHistory(WorkflowEngine engine, String objectId, String classId, PrintStream output) {
        output.printf("%nHISTORY %s objectId=%s%n", classId, objectId);
        for (HistoryEntry entry : engine.getHistory(objectId)) {
            output.printf("%s | %s | %s | %s | %s%n", entry.id(), entry.timestamp(), entry.userId(), entry.operation(), entry.details());
        }
    }

    private static String demonstrateDynamicRegistration(PrintStream output) throws Exception {
        output.println("\nОтдельный динамический автомат: шаблон Б.3 не изменяется");
        try (StateMachine machine = new DefaultStateMachine("idle")) {
            machine.start();
            machine.addState(new StateDefinition("configured", "Добавлено во время работы", false, false, Set.of(), false));
            machine.registerHandler("idle", "configure", event -> TextNode.valueOf("Динамический handler выполнен"));
            machine.addTransition(new TransitionDefinition("idle", "configure", "configured", null));
            EventResult result = await(machine.send(new Event("configure", null, null)));
            output.printf("DYNAMIC %s -> %s; %s%n", result.previousStateId(), result.currentStateId(), result.handlerResult());
            return result.currentStateId();
        }
    }

    private static <Value> Value await(CompletableFuture<Value> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    record Result(Path storePath, String officeId, String standardId, List<Boolean> officeBranches,
                  List<Boolean> standardBranches, String dynamicState) {
    }
}
