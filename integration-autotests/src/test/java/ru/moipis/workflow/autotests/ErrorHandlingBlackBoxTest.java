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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тест-план: контракт ошибок REST — код статуса, форма {@code code}/{@code message}/{@code details},
 * отсутствие утечки стектрейсов, и что отклонённая команда никогда не трогает {@code store.json}.
 *
 * <p>Два сценария из исходного in-process набора (маппинг {@code HANDLER_ERROR}/{@code ENGINE_CLOSED},
 * и доказательство того, что хендлер не был вызван) нуждаются в хуке на уровне Java без REST-эквивалента
 * и остаются white-box тестами в {@code lab1/src/test}.
 *
 * <p>Сценарии со стандартным seed-набором делят один процесс сервера на весь прогон ({@link SharedServer})
 * и создают себе собственные объекты вместо мутации общих seed-объектов; сценарии с собственной фикстурой
 * ({@link Fixtures}) продолжают поднимать отдельный процесс под свой {@code store.json}.
 */
@Epic("Workflow REST API")
@Feature("Контракт ошибок REST")
@ExtendWith(SharedServer.class)
class ErrorHandlingBlackBoxTest {
    @TempDir
    Path directory;

    /**
     * Создаёт изолированный {@code office_order} с заданным {@code quantity} и прочими полями заполнены
     * так же, как у демо-данных, и возвращает его путь {@code /api/objects/<id>} — так тест не делит
     * мутируемый объект с другими тестами на общем процессе.
     */
    private static String createOfficeOrder(WorkflowServerProcess server, int quantity) throws Exception {
        var created = server.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{
                "order_number":"Б3-ERR","product_name":"Офисные кресла","quantity":%d,
                "total_amount":360000,"supplier_name":"ООО «Офис»","delivery_days":14,
                "prepayment_percent":40,"office_location":"Москва, центральный офис"}}
                """.formatted(quantity));
        assertEquals(201, created.statusCode(), created.body());
        return "/api/objects/" + server.json(created).path("id").asText();
    }

    private static void action(WorkflowServerProcess server, String path, String action) throws Exception {
        var response = server.send("POST", path + "/actions",
                "{\"userId\":\"user_coordinator\",\"actionId\":\"" + action + "\"}");
        assertEquals(200, response.statusCode(), response.body());
    }

    /**
     * Тест-кейс: чужая роль отклоняется с 403 ещё до вычисления условия; правильная роль затем
     * проходит успешно; и уже в новом состоянии действие с ложным условием отклоняется с 409
     * {@code CONDITION_NOT_MET}. Ни один отклонённый вызов не оставляет следа в {@code store.json}.
     */
    @Test
    @Description
    @DisplayName("Чужая роль и ложное условие отклоняются без изменения объекта/истории/файла")
    @Story("Права и условия перехода")
    @Severity(SeverityLevel.CRITICAL)
    void wrongRoleAndFalseConditionDoNotChangeObjectsHistoryOrFile() throws Exception {
        var server = SharedServer.get();
        // quantity=700 превышает max=500 класса office_order, поэтому у "approve" заведомо ложное
        // условие "quantity_in_range" — именно это и должно дать CONDITION_NOT_MET, а не что-то другое.
        String root = createOfficeOrder(server, 700);
        unchanged(server, "POST", root + "/actions", """
                {"userId":"user_finance","actionId":"complete"}
                """, 403, "ACCESS_DENIED");
        assertEquals(200, server.send("POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"}
                """).statusCode());
        unchanged(server, "POST", root + "/actions", """
                {"userId":"user_manager","actionId":"approve"}
                """, 409, "CONDITION_NOT_MET");
    }

    /**
     * Тест-кейс: некорректные или семантически неверные тела запросов все возвращаются как 400 в
     * одном консистентном JSON-формате — невалидный/отсутствующий JSON, неожиданное поле
     * {@code stateId} при создании, значение параметра неверного типа или отсутствующее, неизвестный
     * id параметра, отсутствующий query-параметр и мусор после JSON-тела.
     */
    @Test
    @Description
    @DisplayName("Некорректные тела запросов и типы параметров дают единый формат 400")
    @Story("Валидация входных данных")
    @Severity(SeverityLevel.CRITICAL)
    void requestShapeAndParameterTypeErrorsHaveOneJsonFormat() throws Exception {
        var server = SharedServer.get();
        String root = createOfficeOrder(server, 100);
        unchanged(server, "POST", "/api/objects", "{", 400, "INVALID_REQUEST");
        unchanged(server, "POST", "/api/objects", "null", 400, "INVALID_REQUEST");
        unchanged(server, "POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{},"stateId":"completed"}
                """, 400, "INVALID_REQUEST");
        unchanged(server, "PATCH", root + "/parameters/quantity", """
                {"userId":"user_manager","value":"100"}
                """, 400, "INVALID_PARAMETER_TYPE");
        unchanged(server, "PATCH", root + "/parameters/quantity", """
                {"userId":"user_manager"}
                """, 400, "INVALID_REQUEST");
        unchanged(server, "PATCH", root + "/parameters/no_such_parameter", """
                {"userId":"user_manager","value":100}
                """, 400, "UNKNOWN_PARAMETER");
        unchanged(server, "GET", root + "/access", null, 400, "INVALID_REQUEST");
        unchanged(server, "POST", root + "/actions", """
                {"userId":"user_manager","actionId":"complete"} {}
                """, 400, "INVALID_REQUEST");
    }

    /**
     * Тест-кейс: неизвестный id объекта, неизвестный класс в списке параметров, незарегистрированный
     * маршрут, неизвестный {@code userId} в проверке доступа и создание для неизвестного id класса —
     * всё это возвращается как 404 {@code NOT_FOUND}, а не, скажем, 400 или 500.
     */
    @Test
    @Description
    @DisplayName("Отсутствующие объекты, классы, маршруты и пользователи дают 404")
    @Story("404 для отсутствующих ресурсов")
    @Severity(SeverityLevel.NORMAL)
    void missingResourcesAndUsersReturn404() throws Exception {
        var server = SharedServer.get();
        String root = createOfficeOrder(server, 100);
        unchanged(server, "GET", "/api/objects/missing", null, 404, "NOT_FOUND");
        unchanged(server, "GET", "/api/classes/missing/parameters", null, 404, "NOT_FOUND");
        unchanged(server, "GET", "/api/not-a-route", null, 404, "NOT_FOUND");
        unchanged(server, "GET", root + "/access?userId=missing", null, 404, "NOT_FOUND");
        unchanged(server, "POST", "/api/objects", """
                {"userId":"user_manager","classId":"missing","parameters":{}}
                """, 404, "NOT_FOUND");
    }

    /**
     * Тест-кейс: создание объекта прямо в корневом классе {@code order}, у которого нет собственного
     * шаблона процесса, — это конфликт 409 {@code NO_PROCESS_TEMPLATE} (проблема конфигурации, а не
     * 404 или 500), и ничего не записывается.
     */
    @Test
    @Description
    @DisplayName("Создание в классе без собственного шаблона — конфликт, а не 404/500")
    @Story("Конфликты конфигурации процесса")
    @Severity(SeverityLevel.NORMAL)
    void classWithoutAProcessIsAConflictAndDoesNotChangeStore() throws Exception {
        unchanged(SharedServer.get(), "POST", "/api/objects", """
                {"userId":"user_manager","classId":"order","parameters":{"order_number":"ROOT-FAIL"}}
                """, 409, "NO_PROCESS_TEMPLATE");
    }

    /**
     * Тест-кейс: шаблон, который присутствует, но неверно сконфигурирован (фикстура сидирует
     * {@code standard_order_supply} со сброшенным флагом {@code initial} у всех состояний, так что
     * движок не может определить начальное состояние), проявляется как 500 {@code TEMPLATE_ERROR}, а
     * не роняет сервер и не подставляет молча какое-то состояние; хранилище остаётся нетронутым.
     */
    @Test
    @Description
    @DisplayName("Шаблон без initial-состояния остаётся серверной ошибкой TEMPLATE_ERROR")
    @Story("Конфликты конфигурации процесса")
    @Severity(SeverityLevel.NORMAL)
    void invalidConfiguredTemplateRemainsAServerError() throws Exception {
        Path store = Fixtures.copy("broken-template-store.json", directory.resolve("store.json"));
        try (var server = WorkflowServerProcess.start(store)) {
            unchanged(server, "POST", "/api/objects", """
                    {"userId":"user_manager","classId":"office_order","parameters":{}}
                    """, 500, "TEMPLATE_ERROR");
        }
    }

    /**
     * Тест-кейс: проведение объекта до самого финального состояния {@code completed} с проверкой, что
     * read-only состояния по пути отклоняют редактирование (403), а финальное состояние отклоняет и
     * редактирование, и действия (409 {@code FINAL_STATE}) — а {@code /access} для финального
     * состояния сообщает об отсутствии редактируемых параметров и доступных действий вовсе.
     */
    @Test
    @Description
    @DisplayName("Read-only и финальное состояние блокируют редактирование/действия")
    @Story("Права и условия перехода")
    @Severity(SeverityLevel.CRITICAL)
    void readOnlyAndTerminalStatesAreCheckedByTheBackend() throws Exception {
        var server = SharedServer.get();
        String objectRoot = createOfficeOrder(server, 120);
        action(server, objectRoot, "complete");
        action(server, objectRoot, "approve");
        unchanged(server, "PATCH", objectRoot + "/parameters/quantity", """
                {"userId":"user_coordinator","value":10}
                """, 403, "ACCESS_DENIED");
        action(server, objectRoot, "approve");
        action(server, objectRoot, "approve");
        action(server, objectRoot, "approve");
        action(server, objectRoot, "execute");
        action(server, objectRoot, "complete");

        var access = server.json(server.send("GET", objectRoot + "/access?userId=user_coordinator", null));
        assertFalse(access.path("canEditParameters").asBoolean());
        assertEquals(0, access.path("availableActions").size());

        unchanged(server, "POST", objectRoot + "/actions", """
                {"userId":"user_coordinator","actionId":"complete"}
                """, 409, "FINAL_STATE");
        unchanged(server, "PATCH", objectRoot + "/parameters/quantity", """
                {"userId":"user_coordinator","value":10}
                """, 409, "FINAL_STATE");
    }

    /**
     * Тест-кейс: установка параметра в JSON {@code null} действительно очищает его (а не оставляет
     * неизменным и не даёт 400), а числа далеко за пределами точности {@code long}/{@code double} —
     * 30-значное целое и 29-значное дробное — переживают полный HTTP round-trip (создание, затем
     * обратное чтение) с сохранением каждой цифры, и в ответе на создание, и в последующем GET.
     */
    @Test
    @Description
    @DisplayName("null очищает значение; большие числа не теряют точность через HTTP")
    @Story("Точность данных через HTTP")
    @Severity(SeverityLevel.NORMAL)
    void nullClearsAValueAndExactJsonNumbersSurviveHttp() throws Exception {
        var server = SharedServer.get();
        var withBlockReason = server.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{"quantity":100,"block_reason":"На паузе"}}
                """);
        assertEquals(201, withBlockReason.statusCode(), withBlockReason.body());
        String root = "/api/objects/" + server.json(withBlockReason).path("id").asText();

        var clear = server.send("PATCH", root + "/parameters/block_reason", """
                {"userId":"user_manager","value":null}
                """);
        assertEquals(200, clear.statusCode(), clear.body());
        assertTrue(server.json(server.send("GET", root, null)).path("parameters").path("block_reason").isNull());

        var created = server.send("POST", "/api/objects", """
                {"userId":"user_manager","classId":"office_order","parameters":{
                "quantity":123456789012345678901234567890,"total_amount":0.12345678901234567890123456789}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        var object = server.json(server.send("GET", "/api/objects/" + server.json(created).path("id").asText(), null));
        assertEquals("123456789012345678901234567890", object.path("parameters").path("quantity").asText());
        assertEquals("0.12345678901234567890123456789", object.path("parameters").path("total_amount").asText());
        assertTrue(created.body().contains("0.12345678901234567890123456789"));
    }

    /**
     * Тест-кейс: нестроковый {@code userId} (число в JSON) отклоняется как ошибка формы 400 ещё до
     * запуска проверки роли, а корректно оформленный запрос от пользователя без нужной роли
     * отклоняется как 403 — эти два вида отказа не должны путаться друг с другом.
     */
    @Test
    @Description
    @DisplayName("Числовой userId — 400, корректный userId без роли — 403")
    @Story("Валидация входных данных")
    @Severity(SeverityLevel.NORMAL)
    void numericIdentifiersAndCreationWithoutARoleAreRejected() throws Exception {
        var server = SharedServer.get();
        unchanged(server, "POST", "/api/objects", """
                {"userId":123,"classId":"office_order","parameters":{}}
                """, 400, "INVALID_REQUEST");
        unchanged(server, "POST", "/api/objects", """
                {"userId":"user_finance","classId":"office_order","parameters":{}}
                """, 403, "ACCESS_DENIED");
    }

    /**
     * Тест-кейс: шаблон с двумя одновременно истинными условными переходами из одного состояния
     * (фикстура сидирует дополнительный шаблон {@code ambiguous-workflow}) сообщается через REST как
     * 409 {@code TEMPLATE_ERROR}, а не молча выбирает первое совпадение, и отклонённое действие не
     * меняет хранилище.
     */
    @Test
    @Description
    @DisplayName("Неоднозначный переход — 409 TEMPLATE_ERROR, а не первый совпавший")
    @Story("Конфликты конфигурации процесса")
    @Severity(SeverityLevel.NORMAL)
    void ambiguousTransitionsAreReportedAsAConflictWithoutChangingTheStore() throws Exception {
        Path store = Fixtures.copy("ambiguous-template-store.json", directory.resolve("store.json"));
        try (var server = WorkflowServerProcess.start(store)) {
            var created = server.send("POST", "/api/objects", """
                    {"userId":"user_manager","classId":"office_order","parameters":{"quantity":100}}
                    """);
            assertEquals(201, created.statusCode(), created.body());
            unchanged(server, "POST", "/api/objects/" + server.json(created).path("id").asText() + "/actions", """
                    {"userId":"user_manager","actionId":"choose"}
                    """, 409, "TEMPLATE_ERROR");
        }
    }

    private void unchanged(WorkflowServerProcess server, String method, String path, String body, int status, String code)
            throws Exception {
        byte[] before = Files.readAllBytes(server.storePath());
        var response = server.send(method, path, body);
        assertEquals(status, response.statusCode(), response.body());
        JsonNode json = server.json(response);
        assertEquals(code, json.path("code").asText());
        assertFalse(json.path("message").asText().isBlank());
        assertTrue(json.path("details").isObject());
        assertFalse(response.body().contains("stackTrace"));
        assertArrayEquals(before, Files.readAllBytes(server.storePath()));
    }
}
