package ru.moipis.workflow.autotests;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.autotests.utils.Fixtures;
import ru.moipis.workflow.autotests.utils.SharedServer;
import ru.moipis.workflow.autotests.utils.WorkflowServerProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тест-план: REST-контракт {@code /api/**} целиком. Каждый запрос идёт по настоящему HTTP, а каждое
 * ожидание проверяется либо по HTTP-ответу, либо по чтению серверного {@code store.json} с диска как
 * обычного JSON — здесь нет ни одного импорта класса продакшена.
 *
 * <p>Сценарии, которым достаточно стандартного seed-набора, делят один процесс сервера на весь прогон
 * ({@link SharedServer}) и поэтому создают себе собственные объекты вместо мутации/подсчёта seed-данных,
 * общих с другими тестами. Сценарии с собственной фикстурой ({@link Fixtures}) продолжают поднимать
 * отдельный процесс, так как им нужен свой {@code store.json} с нестандартной конфигурацией шаблонов.
 */
@Epic("Workflow REST API")
@Feature("Классы, шаблоны и объекты процессов")
@ExtendWith(SharedServer.class)
class WorkflowApiBlackBoxTest {
    @TempDir
    Path directory;

    private static Map<String, JsonNode> classCatalog(WorkflowServerProcess server) throws Exception {
        var response = server.send("GET", "/api/classes", null);
        assertEquals(200, response.statusCode(), response.body());
        return StreamSupport.stream(server.json(response).spliterator(), false)
                .collect(Collectors.toMap(node -> node.path("id").asText(), Function.identity()));
    }

    /**
     * Тест-кейс: справочные эндпоинты возвращают реальные данные сида.
     * <p>GET {@code /api/users} возвращает все 7 пользователей сида, включая id, имя, роль и локализованное
     * название роли первого пользователя; GET {@code /api/classes} возвращает все 4 класса; GET
     * {@code /api/classes/office_order/parameters} включает параметр {@code quantity} с переопределённым в
     * подклассе {@code max}=500 (а не 10000 у родительского класса).
     */
    @Test
    @Description
    @DisplayName("Справочники пользователей и классов возвращают актуальные данные сида")
    @Story("Справочники: пользователи и классы")
    @Severity(SeverityLevel.NORMAL)
    void returnsActualUsersAndInheritedClassParameters() throws Exception {
        var server = SharedServer.get();
        var users = server.send("GET", "/api/users", null);
        assertEquals(200, users.statusCode());
        assertEquals(7, server.json(users).size());
        assertEquals("user_manager", server.json(users).get(0).path("id").asText());
        assertEquals("Анна — менеджер заказа", server.json(users).get(0).path("name").asText());
        assertEquals("order_manager", server.json(users).get(0).path("roles").get(0).asText());
        assertEquals("Менеджер заказа", server.json(users).get(0).path("roleTitles").path("order_manager").asText());

        var classes = server.send("GET", "/api/classes", null);
        assertEquals(200, classes.statusCode());
        assertEquals(4, server.json(classes).size());

        var parameters = server.send("GET", "/api/classes/office_order/parameters", null);
        assertEquals(200, parameters.statusCode());
        var quantity = StreamSupport.stream(server.json(parameters).spliterator(), false)
                .filter(node -> node.path("id").asText().equals("quantity")).findFirst().orElseThrow();
        assertEquals(500, quantity.path("max").intValue());
    }

    /**
     * Тест-кейс: список заказов не пуст (минимум 5 заказов из сида, плюс что успели создать другие
     * тесты, делящие с этим один процесс) и создание нового без срабатывания проверки диапазона.
     * <p>POST нового {@code office_order} с {@code quantity=700} (выше максимума класса 500) всё равно
     * успешен с кодом 201 — создание не проверяет диапазоны параметров — а в ответе есть заголовок
     * {@code Location}, начальное состояние/название {@code registration} и ровно те параметры, что были
     * переданы. Созданный объект затем виден и по GET по id, и прибавляет ровно одну запись к общему
     * числу объектов в {@code store.json}, с записью {@code CREATE} первой в его собственной истории.
     */
    @Test
    @Description
    @DisplayName("Создание объекта без проверки диапазона и его появление в списке/истории")
    @Story("Объекты: список и создание")
    @Severity(SeverityLevel.CRITICAL)
    void listsSeedOrdersAndCreatesWithoutRangeValidation() throws Exception {
        var server = SharedServer.get();
        var orders = server.send("GET", "/api/objects", null);
        assertEquals(200, orders.statusCode());
        int countBefore = server.json(orders).size();
        assertTrue(countBefore >= 5, "Expected at least the 5 seed orders, got " + countBefore);

        var created = server.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{"order_number":"HTTP-001","quantity":700}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        var object = server.json(created);
        String id = object.path("id").asText();
        assertFalse(id.isBlank());
        assertEquals("/api/objects/" + id, created.headers().firstValue("Location").orElseThrow());
        assertEquals("registration", object.path("stateId").asText());
        assertEquals("Регистрация", object.path("stateTitle").asText());
        assertEquals(700, object.path("parameters").path("quantity").intValue());
        assertEquals(object, server.json(server.send("GET", "/api/objects/" + id, null)));

        assertEquals(countBefore + 1, server.json(server.send("GET", "/api/objects", null)).size());
        var history = server.json(server.send("GET", "/api/objects/" + id + "/history", null));
        assertEquals("CREATE", history.get(0).path("operation").asText());
    }

    /**
     * Тест-кейс: пока шаблон никогда не копировался, каждый подкласс {@code standard_order} использует
     * собственный шаблон родительского класса, а базовый класс {@code order} (у которого шаблона нет вовсе)
     * возвращает {@code null} в {@code templateId}, а не отказ. Простое чтение каталога не должно писать в
     * {@code store.json}.
     */
    @Test
    @Description
    @DisplayName("Подклассы используют шаблон родителя, пока не скопирован свой")
    @Story("Наследование шаблонов процессов")
    @Severity(SeverityLevel.NORMAL)
    void inheritedSubclassesShareTheParentClassTemplateByDefault() throws Exception {
        var server = SharedServer.get();
        byte[] before = Files.readAllBytes(server.storePath());
        var classes = classCatalog(server);
        assertEquals(Set.of("order", "standard_order", "office_order", "consumables_order"), classes.keySet());
        assertTrue(classes.get("order").has("templateId"));
        assertTrue(classes.get("order").path("templateId").isNull());
        for (String id : List.of("standard_order", "office_order", "consumables_order")) {
            assertEquals("standard_order_supply", classes.get(id).path("templateId").asText());
        }
        assertArrayEquals(before, Files.readAllBytes(server.storePath()), "Reading the catalog must not write the store");
    }

    /**
     * Тест-кейс: как только у класса появляется собственный скопированный шаблон (фикстура уже содержит
     * {@code office_copy} для {@code office_order}), каталог классов сообщает эту копию для
     * {@code office_order}, в то время как соседний {@code consumables_order} продолжает использовать
     * оригинальный шаблон {@code standard_order_supply} — копирование шаблона одного подкласса не должно
     * затрагивать другой.
     */
    @Test
    @Description
    @DisplayName("Скопированный шаблон виден только у своего подкласса, не у соседа")
    @Story("Наследование шаблонов процессов")
    @Severity(SeverityLevel.CRITICAL)
    void classCatalogReportsACopiedTemplateWithoutDroppingTheOriginalForSiblings() throws Exception {
        Path store = Fixtures.copy("copied-template-store.json", directory.resolve("store.json"));
        try (var server = WorkflowServerProcess.start(store)) {
            var classes = classCatalog(server);
            assertEquals("office_copy", classes.get("office_order").path("templateId").asText());
            assertEquals("standard_order_supply", classes.get("consumables_order").path("templateId").asText());
        }
    }

    /**
     * Тест-кейс: базовый класс без собственного шаблона становится доступным для создания объектов, как
     * только получает настоящий шаблон (фикстура сидирует шаблон {@code root_process} для класса
     * {@code order}). Каталог классов после этого сообщает этот шаблон для {@code order}, и новый объект
     * можно создать прямо на базовом классе — он попадает в начальное состояние {@code draft} этого шаблона.
     */
    @Test
    @Description
    @DisplayName("Базовый класс можно создавать после появления у него своего шаблона")
    @Story("Наследование шаблонов процессов")
    @Severity(SeverityLevel.NORMAL)
    void baseClassBecomesAvailableWhenItHasAnActualTemplate() throws Exception {
        Path store = Fixtures.copy("root-template-store.json", directory.resolve("store.json"));
        try (var server = WorkflowServerProcess.start(store)) {
            assertEquals("root_process", classCatalog(server).get("order").path("templateId").asText());
            var created = server.send("POST", "/api/objects", """
                    {"userId":"user_manager","classId":"order","parameters":{"order_number":"ROOT-001"}}
                    """);
            assertEquals(201, created.statusCode(), created.body());
            assertEquals("root_process", server.json(created).path("templateId").asText());
            assertEquals("draft", server.json(created).path("stateId").asText());
        }
    }

    /**
     * Тест-кейс: граф процесса и доступ по пользователям для унаследованного класса. GET {@code /process}
     * на объекте {@code office_order} сообщает унаследованный шаблон {@code standard_order_supply} со всеми
     * его 10 состояниями и 19 переходами. После выполнения первого действия представление
     * {@code /access} менеджера разрешает редактирование и перечисляет ровно действия
     * {@code approve}/{@code postpone}, доступные из нового состояния, в то время как не связанный с
     * процессом финансовый пользователь не может редактировать и не видит доступных действий — доступ
     * должен отражать роль, а не только состояние.
     */
    @Test
    @Description
    @DisplayName("Граф процесса и доступ по ролям отражают реальное состояние объекта")
    @Story("Граф процесса и права доступа")
    @Severity(SeverityLevel.CRITICAL)
    void returnsInheritedProcessAndAccessWithoutEvaluatingFalseConditions() throws Exception {
        var server = SharedServer.get();
        // quantity=700 превышает max=500 класса office_order — условие "quantity_in_range" у перехода
        // approve ложно; availableActions всё равно должен перечислять approve, не вычисляя условие.
        var created = server.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{
                "order_number":"Б3-003","product_name":"Офисные кресла","quantity":700,
                "total_amount":360000,"supplier_name":"ООО «Офис»","delivery_days":14,
                "prepayment_percent":40,"office_location":"Москва, центральный офис"}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        String root = "/api/objects/" + server.json(created).path("id").asText();

        var graph = server.send("GET", root + "/process", null);
        assertEquals(200, graph.statusCode());
        var process = server.json(graph);
        assertEquals("standard_order_supply", process.path("templateId").asText());
        assertEquals(10, process.path("states").size());
        assertEquals(19, process.path("transitions").size());

        var action = server.send("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"}
                """);
        assertEquals(200, action.statusCode(), action.body());

        var access = server.send("GET", root + "/access?userId=user_manager", null);
        assertEquals(200, access.statusCode());
        assertTrue(server.json(access).path("canEditParameters").asBoolean());
        assertEquals(List.of("approve", "postpone"), StreamSupport.stream(
                        server.json(access).path("availableActions").spliterator(), false)
                .map(n -> n.path("id").asText()).toList());

        var denied = server.json(server.send("GET", root + "/access?userId=user_finance", null));
        assertFalse(denied.path("canEditParameters").asBoolean());
        assertEquals(0, denied.path("availableActions").size());
    }

    /**
     * Тест-кейс: PATCH, за которым следует action, коммитят ровно по одной записи истории каждый, по
     * порядку, и обе видны ещё до того, как вернётся HTTP-ответ на более позднюю команду. Запись истории
     * обновления параметра несёт правильный {@code oldValue}, а запись action — новое состояние и
     * ISO-таймстамп — команды должны быть надёжно и полностью записаны к моменту ответа, а не когда-нибудь
     * потом.
     */
    @Test
    @Description
    @DisplayName("Команды PATCH/action дают ровно одну запись истории каждая, видимую сразу")
    @Story("Команды изменения состояния и история")
    @Severity(SeverityLevel.CRITICAL)
    void commandsPersistStateValuesAndExactlyOneHistoryEntryBeforeResponding() throws Exception {
        var server = SharedServer.get();
        var created = server.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{
                "order_number":"Б3-002","product_name":"Офисные кресла","quantity":120,
                "total_amount":360000,"supplier_name":"ООО «Офис»","delivery_days":14,
                "prepayment_percent":40,"office_location":"Москва, центральный офис"}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        String root = "/api/objects/" + server.json(created).path("id").asText();

        var update = server.send("PATCH", root + "/parameters/quantity", """
                {"userId":"user_manager","value":100}
                """);
        assertEquals(200, update.statusCode(), update.body());
        assertEquals(100, server.json(server.send("GET", root, null)).path("parameters").path("quantity").intValue());

        var action = server.send("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"}
                """);
        assertEquals(200, action.statusCode());
        assertEquals("composition_approval", server.json(action).path("currentStateId").asText());

        var history = server.send("GET", root + "/history", null);
        assertEquals(200, history.statusCode());
        assertEquals(3, server.json(history).size());
        assertEquals("UPDATE_PARAMETER", server.json(history).get(1).path("operation").asText());
        assertEquals(120, server.json(history).get(1).path("details").path("oldValue").intValue());
        assertEquals("composition_approval", server.json(history).get(2).path("details").path("newState").asText());
        assertTrue(server.json(history).get(2).path("timestamp").isTextual());
    }

    /**
     * Тест-кейс: объект, созданный до появления копии шаблона, продолжает использовать тот templateId, с
     * которым он был сохранён, даже после того как его класс получает новую скопированную копию (фикстура
     * сидирует {@code office_copy} для {@code office_order} с самого начала, а уже существующий объект
     * {@code demo-office-valid} был сохранён ещё против {@code standard_order_supply}). Однако совершенно
     * новый объект того же класса получает именно новый шаблон {@code office_copy} — сохранённый у объекта
     * id побеждает над тем, что сейчас резолвится для класса.
     */
    @Test
    @Description
    @DisplayName("Сохранённый templateId объекта не меняется при появлении новой копии шаблона")
    @Story("Наследование шаблонов процессов")
    @Severity(SeverityLevel.NORMAL)
    void savedTemplateIdWinsOverNewSubclassTemplate() throws Exception {
        Path store = Fixtures.copy("copied-template-store.json", directory.resolve("store.json"));
        try (var server = WorkflowServerProcess.start(store)) {
            var oldGraph = server.json(server.send("GET", "/api/objects/demo-office-valid/process", null));
            assertEquals("standard_order_supply", oldGraph.path("templateId").asText());

            var created = server.json(server.send("POST", "/api/objects", """
                    {"userId":"user_manager","classId":"office_order","parameters":{}}
                    """));
            var graph = server.json(server.send("GET", "/api/objects/" + created.path("id").asText() + "/process", null));
            assertEquals("office_copy", graph.path("templateId").asText());
        }
    }
}
