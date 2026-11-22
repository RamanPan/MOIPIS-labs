package ru.moipis.workflow.autotests;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.autotests.utils.CoordinationProcess;
import ru.moipis.workflow.autotests.utils.CoordinationProcess.Step;
import ru.moipis.workflow.autotests.utils.Fixtures;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тест-план для лабораторной работы 3-1-2 («взаимодействующие процессы»), проверяемый только снаружи:
 * консольное приложение запускается отдельным процессом ОС ({@code mvn -Pcoordination exec:java}),
 * команды подаются в stdin, результат проверяется по строкам stdout и по JSONL-журналу на диске.
 *
 * <p>Режим {@code -Pweb}, который использует {@link WorkflowApiBlackBoxTest}, координацию процессов не
 * поддерживает, поэтому эти сценарии вынесены в отдельный класс. Демонстрационная конфигурация: заказ
 * {@code order-1} (связь {@code customer} → {@code company-1}) и заказчик {@code company-1} (связь
 * {@code orders} → {@code order-1}); пользователи {@code manager} (ведёт заказ) и {@code reviewer}
 * (ведёт проверку заказчика).
 */
@Epic("Взаимодействующие процессы (3-1-2)")
@Feature("Координация процессов через консольное приложение")
class CoordinationBlackBoxTest {
    private static final String ORDER = "order-1";
    private static final String COMPANY = "company-1";

    @TempDir
    Path directory;

    private static void assertEntry(JsonNode entry, String kind, String objectId, String before, String after,
                                    String outcome, String reason) {
        String message = entry.toString();
        assertEquals(kind, entry.path("kind").asText(), message);
        assertEquals(objectId, entry.path("objectId").asText(), message);
        assertText(before, entry.path("before"), "before: " + message);
        assertText(after, entry.path("after"), "after: " + message);
        assertEquals(outcome, entry.path("outcome").asText(), message);
        assertText(reason, entry.path("reason"), "reason: " + message);
    }

    private static void assertText(String expected, JsonNode actual, String message) {
        if (expected == null) {
            assertTrue(actual.isNull() || actual.isMissingNode(), message);
        } else {
            assertEquals(expected, actual.asText(), message);
        }
    }

    private static void assertConsoleContains(Step step, String fragment) {
        assertTrue(step.console().stream().anyMatch(line -> line.contains(fragment)),
                "Console output of '" + step.input() + "' does not contain '" + fragment + "': " + step.console());
    }

    /**
     * Тест-кейс: условие по связанному процессу блокирует и затем пропускает переход. Пока заказчик в
     * {@code checking}, {@code submit} заказа отклоняется с {@code CONDITION_NOT_MET} и заказ остаётся в
     * {@code preparing}. После {@code approve} заказчика заказ сам не двигается; менеджер повторяет
     * {@code submit}, и он проходит.
     */
    @Test
    @Description
    @DisplayName("submit отклоняется, пока заказчик не одобрен, и проходит после approve и повторной команды")
    @Story("Условия по связанному процессу")
    @Severity(SeverityLevel.CRITICAL)
    void submitIsRejectedUntilTheCustomerIsApprovedAndThenSucceedsOnRetry() throws Exception {
        try (var app = CoordinationProcess.start()) {
            assertEntry(app.run("start manager " + ORDER).command(), "START", ORDER, null, "preparing", "SUCCESS", null);
            assertEntry(app.run("start reviewer " + COMPANY).command(), "START", COMPANY, null, "checking", "SUCCESS", null);

            Step rejected = app.run("action manager " + ORDER + " submit");
            assertEntry(rejected.command(), "ACTION", ORDER, "preparing", "preparing", "REJECTED", "CONDITION_NOT_MET");
            assertTrue(rejected.deliveries().isEmpty(), "A rejected command must not produce events");
            assertConsoleContains(rejected, "REJECTED reason=CONDITION_NOT_MET");

            Step approved = app.run("action reviewer " + COMPANY + " approve");
            assertEntry(approved.command(), "ACTION", COMPANY, "checking", "verified", "SUCCESS", null);
            assertTrue(approved.deliveries().isEmpty(), "approve has no event configured");

            // Изменение связанного процесса само заказ не двигает: переход делает повторная команда менеджера.
            Step retried = app.run("action manager " + ORDER + " submit");
            assertEntry(retried.command(), "ACTION", ORDER, "preparing", "ready", "SUCCESS", null);
        }
    }

    /**
     * Тест-кейс: если процесс заказчика ещё не запущен, проверка по связи ложна — {@code submit}
     * отклоняется. После {@code start} и {@code approve} заказчика та же команда проходит.
     */
    @Test
    @Description
    @DisplayName("Незапущенный процесс заказчика делает условие ложным")
    @Story("Условия по связанному процессу")
    @Severity(SeverityLevel.NORMAL)
    void submitIsRejectedWhileTheCustomerProcessIsNotStarted() throws Exception {
        try (var app = CoordinationProcess.start()) {
            app.run("start manager " + ORDER);

            Step rejected = app.run("action manager " + ORDER + " submit");
            assertEntry(rejected.command(), "ACTION", ORDER, "preparing", "preparing", "REJECTED", "CONDITION_NOT_MET");

            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");
            assertEntry(app.run("action manager " + ORDER + " submit").command(),
                    "ACTION", ORDER, "preparing", "ready", "SUCCESS", null);
        }
    }

    /**
     * Тест-кейс: основной маршрут целиком. Успешный {@code revise} заказчика порождает событие
     * {@code customer_changed}; заказ в {@code ready} возвращается в {@code preparing} без команды менеджера.
     * Для повторного прохода заказа до {@code done} снова нужны {@code approve} заказчика, {@code submit} и
     * {@code confirm}. Всего в журнале 10 записей — 9 команд и одно событие.
     */
    @Test
    @Description
    @DisplayName("revise порождает событие, которое возвращает заказ из ready в preparing; маршрут доходит до done")
    @Story("События между процессами")
    @Severity(SeverityLevel.CRITICAL)
    void reviseEmitsAnEventThatReturnsTheOrderToPreparingAndTheRouteReachesDone() throws Exception {
        try (var app = CoordinationProcess.start()) {
            app.run("start manager " + ORDER);
            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");
            assertEntry(app.run("action manager " + ORDER + " submit").command(),
                    "ACTION", ORDER, "preparing", "ready", "SUCCESS", null);

            Step revise = app.run("action reviewer " + COMPANY + " revise");
            assertEntry(revise.command(), "ACTION", COMPANY, "verified", "checking", "SUCCESS", null);
            assertEquals(1, revise.deliveries().size(), "One recipient is linked through 'orders'");
            JsonNode event = revise.deliveries().getFirst();
            assertEntry(event, "EVENT", ORDER, "ready", "preparing", "SUCCESS", null);
            assertEquals("customer_changed", event.path("eventName").asText());
            assertEquals(COMPANY, event.path("sourceId").asText());
            assertEquals(ORDER, event.path("recipientId").asText());
            assertTrue(event.path("userId").isNull(), "An event is not executed on behalf of a user");
            assertConsoleContains(revise, "EVENT " + ORDER + " customer_changed source=" + COMPANY);

            // Заказ действительно в preparing, а заказчик снова в checking: submit опять отклоняется.
            assertEntry(app.run("action manager " + ORDER + " submit").command(),
                    "ACTION", ORDER, "preparing", "preparing", "REJECTED", "CONDITION_NOT_MET");

            app.run("action reviewer " + COMPANY + " approve");
            assertEntry(app.run("action manager " + ORDER + " submit").command(),
                    "ACTION", ORDER, "preparing", "ready", "SUCCESS", null);
            assertEntry(app.run("action manager " + ORDER + " confirm").command(),
                    "ACTION", ORDER, "ready", "done", "SUCCESS", null);

            assertEquals(10, app.journal().size());
        }
    }

    /**
     * Тест-кейс: отказавшие исходные действия событий не порождают. {@code revise} от пользователя без нужной
     * роли ({@code ACCESS_DENIED}) и действие, которого нет в текущем состоянии ({@code INVALID_ACTION}),
     * отклоняются без записей доставки. Заказ в {@code ready} остаётся нетронутым: {@code confirm} проходит.
     */
    @Test
    @Description
    @DisplayName("Отклонённое исходное действие не порождает событий")
    @Story("События между процессами")
    @Severity(SeverityLevel.CRITICAL)
    void rejectedSourceActionsProduceNoEvents() throws Exception {
        try (var app = CoordinationProcess.start()) {
            app.run("start manager " + ORDER);
            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");
            app.run("action manager " + ORDER + " submit");

            Step wrongRole = app.run("action manager " + COMPANY + " revise");
            assertEntry(wrongRole.command(), "ACTION", COMPANY, "verified", "verified", "REJECTED", "ACCESS_DENIED");
            assertTrue(wrongRole.deliveries().isEmpty());

            Step wrongAction = app.run("action reviewer " + COMPANY + " approve");
            assertEntry(wrongAction.command(), "ACTION", COMPANY, "verified", "verified", "REJECTED", "INVALID_ACTION");
            assertTrue(wrongAction.deliveries().isEmpty());

            assertTrue(app.journal().stream().noneMatch(entry -> "EVENT".equals(entry.path("kind").asText())),
                    "No delivery records are expected");
            assertEntry(app.run("action manager " + ORDER + " confirm").command(),
                    "ACTION", ORDER, "ready", "done", "SUCCESS", null);
        }
    }

    /**
     * Тест-кейс: переход, предусматривающий отправку события, при пустой связи получателей отклоняется до
     * любых изменений. В конфигурации у {@code company-1} связь {@code orders} пуста: {@code revise}
     * отклоняется, заказчик остаётся в {@code verified}, записей доставки нет.
     */
    @Test
    @Description
    @DisplayName("Пустая связь получателей отклоняет исходный переход до изменений")
    @Story("События между процессами")
    @Severity(SeverityLevel.CRITICAL)
    void emptyRecipientLinkRejectsTheSourceTransitionBeforeAnyChange() throws Exception {
        Path configuration = Fixtures.copy("coordination-empty-orders.json", directory.resolve("empty-orders.json"));
        try (var app = CoordinationProcess.start(configuration)) {
            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");

            Step revise = app.run("action reviewer " + COMPANY + " revise");
            JsonNode entry = revise.command();
            assertEquals("REJECTED", entry.path("outcome").asText());
            assertTrue(entry.path("reason").asText().startsWith("EMPTY_RECIPIENT_LINK"), entry.toString());
            assertEquals("verified", entry.path("before").asText());
            assertEquals("verified", entry.path("after").asText());
            assertTrue(revise.deliveries().isEmpty());

            // Состояние заказчика не изменилось: в verified по-прежнему нет действия approve.
            assertEntry(app.run("action reviewer " + COMPANY + " approve").command(),
                    "ACTION", COMPANY, "verified", "verified", "REJECTED", "INVALID_ACTION");
            assertTrue(app.journal().stream().noneMatch(e -> "EVENT".equals(e.path("kind").asText())));
        }
    }

    /**
     * Тест-кейс: отказ доставки не откатывает источник. Процесс заказа не запущен: {@code revise} успешно
     * переводит заказчика в {@code checking}, а доставка — отдельная запись с отказом
     * {@code PROCESS_NOT_STARTED}. Дальше заказчик работает как обычно.
     */
    @Test
    @Description
    @DisplayName("Незапущенный получатель: источник выполнен, отказ доставки записан отдельно")
    @Story("Отказ доставки")
    @Severity(SeverityLevel.CRITICAL)
    void deliveryToANotStartedRecipientIsRejectedAndTheSourceStays() throws Exception {
        try (var app = CoordinationProcess.start()) {
            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");

            Step revise = app.run("action reviewer " + COMPANY + " revise");
            assertEntry(revise.command(), "ACTION", COMPANY, "verified", "checking", "SUCCESS", null);
            assertEquals(1, revise.deliveries().size());
            JsonNode delivery = revise.deliveries().getFirst();
            assertEntry(delivery, "EVENT", ORDER, null, null, "REJECTED", "PROCESS_NOT_STARTED");
            assertEquals(revise.command().path("id").asText(), delivery.path("parentId").asText());
            assertConsoleContains(revise, "REJECTED reason=PROCESS_NOT_STARTED");

            // Источник не откатился: заказчик действительно в checking и может одобрить заново.
            assertEntry(app.run("action reviewer " + COMPANY + " approve").command(),
                    "ACTION", COMPANY, "checking", "verified", "SUCCESS", null);
            assertEquals(5, app.journal().size());
        }
    }

    /**
     * Тест-кейс: получатель запущен, но в его текущем состоянии нет подходящего обработчика события.
     * Заказ в {@code preparing} (обработчик настроен только для {@code ready}): доставка отклоняется с
     * {@code NO_APPLICABLE_EVENT_TRANSITION}, заказ не меняется, источник остаётся в {@code checking}.
     */
    @Test
    @Description
    @DisplayName("Нет подходящего обработчика события в состоянии получателя")
    @Story("Отказ доставки")
    @Severity(SeverityLevel.NORMAL)
    void deliveryWithoutAMatchingHandlerIsRejectedAndTheRecipientIsUnchanged() throws Exception {
        try (var app = CoordinationProcess.start()) {
            app.run("start manager " + ORDER);
            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");

            Step revise = app.run("action reviewer " + COMPANY + " revise");
            assertEntry(revise.command(), "ACTION", COMPANY, "verified", "checking", "SUCCESS", null);
            assertEquals(1, revise.deliveries().size());
            assertEntry(revise.deliveries().getFirst(), "EVENT", ORDER,
                    "preparing", "preparing", "REJECTED", "NO_APPLICABLE_EVENT_TRANSITION");
        }
    }

    /**
     * Тест-кейс: доставка завершённому процессу отклоняется. Заказ доведён до {@code done}; следующий
     * {@code revise} заказчика проходит, а доставка отклонена с {@code FINAL_STATE} без изменения заказа.
     */
    @Test
    @Description
    @DisplayName("Завершённый получатель отклоняет доставку события")
    @Story("Отказ доставки")
    @Severity(SeverityLevel.NORMAL)
    void deliveryToAFinishedRecipientIsRejected() throws Exception {
        try (var app = CoordinationProcess.start()) {
            app.run("start manager " + ORDER);
            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");
            app.run("action manager " + ORDER + " submit");
            app.run("action manager " + ORDER + " confirm");

            Step revise = app.run("action reviewer " + COMPANY + " revise");
            assertEntry(revise.command(), "ACTION", COMPANY, "verified", "checking", "SUCCESS", null);
            assertEquals(1, revise.deliveries().size());
            assertEntry(revise.deliveries().getFirst(), "EVENT", ORDER, "done", "done", "REJECTED", "FINAL_STATE");
        }
    }

    /**
     * Тест-кейс: отказ одного получателя не мешает доставке остальным и не меняет порядок. В конфигурации у
     * заказчика связь {@code orders} — {@code [order-2, order-1]}, процесс {@code order-2} не запущен,
     * {@code order-1} в {@code ready}. После {@code revise} идут две записи доставки в порядке связи:
     * отказ для {@code order-2}, затем успех для {@code order-1}.
     */
    @Test
    @Description
    @DisplayName("Отказ одного получателя не препятствует доставке другому")
    @Story("Отказ доставки")
    @Severity(SeverityLevel.CRITICAL)
    void oneRejectedRecipientDoesNotBlockDeliveryToTheOthers() throws Exception {
        Path configuration = Fixtures.copy("coordination-two-orders.json", directory.resolve("two-orders.json"));
        try (var app = CoordinationProcess.start(configuration)) {
            app.run("start manager " + ORDER);
            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");
            app.run("action manager " + ORDER + " submit");

            Step revise = app.run("action reviewer " + COMPANY + " revise");
            assertEntry(revise.command(), "ACTION", COMPANY, "verified", "checking", "SUCCESS", null);
            List<JsonNode> deliveries = revise.deliveries();
            assertEquals(2, deliveries.size());
            assertEntry(deliveries.get(0), "EVENT", "order-2", null, null, "REJECTED", "PROCESS_NOT_STARTED");
            assertEntry(deliveries.get(1), "EVENT", ORDER, "ready", "preparing", "SUCCESS", null);
            String commandId = revise.command().path("id").asText();
            assertEquals(commandId, deliveries.get(0).path("parentId").asText());
            assertEquals(commandId, deliveries.get(1).path("parentId").asText());
        }
    }

    /**
     * Тест-кейс: по журналу можно проследить команду и порождённое ею событие. Запись {@code EVENT}
     * отличается от записи {@code ACTION}, её {@code parentId} равен {@code id} команды {@code revise}, а тот
     * же {@code id} виден и в консольном выводе. Идентификаторы всех записей уникальны.
     */
    @Test
    @Description
    @DisplayName("По parentId события в журнале находится породившая его команда")
    @Story("Журнал")
    @Severity(SeverityLevel.CRITICAL)
    void theJournalLinksTheEventToTheCommandThatCausedIt() throws Exception {
        try (var app = CoordinationProcess.start()) {
            app.run("start manager " + ORDER);
            app.run("start reviewer " + COMPANY);
            app.run("action reviewer " + COMPANY + " approve");
            app.run("action manager " + ORDER + " submit");
            Step revise = app.run("action reviewer " + COMPANY + " revise");

            List<JsonNode> journal = app.journal();
            JsonNode command = journal.stream()
                    .filter(e -> "ACTION".equals(e.path("kind").asText()) && "revise".equals(e.path("actionId").asText()))
                    .findFirst().orElseThrow();
            List<JsonNode> events = journal.stream().filter(e -> "EVENT".equals(e.path("kind").asText())).toList();
            assertEquals(1, events.size());
            JsonNode event = events.getFirst();

            assertEquals(command.path("id").asText(), event.path("parentId").asText());
            assertNotEquals(command.path("kind").asText(), event.path("kind").asText());
            assertEquals("customer_changed", event.path("eventName").asText());
            assertEquals("SUCCESS", command.path("outcome").asText());
            assertEquals("SUCCESS", event.path("outcome").asText());

            assertConsoleContains(revise, "id=" + command.path("id").asText());
            assertConsoleContains(revise, "parentId=" + command.path("id").asText());

            Set<String> ids = new HashSet<>();
            for (JsonNode entry : journal) {
                assertFalse(entry.path("id").asText().isBlank(), entry.toString());
                assertFalse(entry.path("timestamp").asText().isBlank(), entry.toString());
                assertTrue(ids.add(entry.path("id").asText()), "Duplicate id in " + entry);
            }
            assertEquals(journal.size(), ids.size());
        }
    }

    /**
     * Тест-кейс: правила запуска процесса. Менеджер не может запустить проверку заказчика
     * ({@code ACCESS_DENIED}); проверяющий запускает её; повторный {@code start} отклоняется
     * ({@code PROCESS_ALREADY_STARTED}); неизвестный объект даёт {@code UNKNOWN_OBJECT}; действие над
     * незапущенным заказом — {@code PROCESS_NOT_STARTED}. Все отказы записаны в журнал.
     */
    @Test
    @Description
    @DisplayName("Правила команды start: роль, повторный запуск, неизвестный объект")
    @Story("Запуск процессов")
    @Severity(SeverityLevel.NORMAL)
    void startCommandRulesAreEnforcedAndEveryRefusalIsLogged() throws Exception {
        try (var app = CoordinationProcess.start()) {
            assertEntry(app.run("start manager " + COMPANY).command(),
                    "START", COMPANY, null, null, "REJECTED", "ACCESS_DENIED");
            assertEntry(app.run("start reviewer " + COMPANY).command(),
                    "START", COMPANY, null, "checking", "SUCCESS", null);
            assertEntry(app.run("start reviewer " + COMPANY).command(),
                    "START", COMPANY, "checking", "checking", "REJECTED", "PROCESS_ALREADY_STARTED");
            assertEntry(app.run("start manager order-9").command(),
                    "START", "order-9", null, null, "REJECTED", "UNKNOWN_OBJECT");
            assertEntry(app.run("action manager " + ORDER + " submit").command(),
                    "ACTION", ORDER, null, null, "REJECTED", "PROCESS_NOT_STARTED");
            assertEquals(5, app.journal().size());
        }
    }
}
