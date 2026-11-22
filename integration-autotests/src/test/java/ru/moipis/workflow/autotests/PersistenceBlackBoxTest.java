package ru.moipis.workflow.autotests;

import io.qameta.allure.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.autotests.utils.WorkflowServerProcess;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тест-план: поведение запуска/перезапуска, проверяемое только снаружи — по HTTP-ответам и файлу
 * {@code store.json} на диске, с сервером, каждый раз запускаемым как отдельный процесс ОС.
 */
@Epic("Workflow REST API")
@Feature("Запуск и устойчивость хранения")
class PersistenceBlackBoxTest {
    @TempDir
    Path directory;

    /**
     * Тест-кейс: значения и история, записанные в одной сессии сервера, переживают полный перезапуск.
     * После PATCH и action процесс останавливается; при следующем старте файл не должен быть
     * перезаписан самим актом запуска, состояние/параметры объекта читаются обратно корректно, а
     * дальнейшее действие на перезапущенном сервере продолжает ту же историю (4 записи всего: CREATE
     * из сида плюс две команды до перезапуска плюс эта).
     */
    @Test
    @Description
    @DisplayName("Значения и история переживают полный перезапуск приложения")
    @Story("Перезапуск сохраняет данные")
    @Severity(SeverityLevel.CRITICAL)
    void restartingTheApplicationPreservesValuesHistoryAndTheSavedState() throws Exception {
        Path store = directory.resolve("persistent.json");
        try (var server = WorkflowServerProcess.start(store)) {
            assertEquals(200, server.send("PATCH", "/api/objects/demo-office-valid/parameters/quantity",
                    "{\"userId\":\"user_manager\",\"value\":321}").statusCode());
            assertEquals(200, server.send("POST", "/api/objects/demo-office-valid/actions",
                    "{\"userId\":\"user_manager\",\"actionId\":\"complete\"}").statusCode());
        }
        byte[] saved = Files.readAllBytes(store);
        try (var server = WorkflowServerProcess.start(store)) {
            assertArrayEquals(saved, Files.readAllBytes(store), "Startup must not rewrite existing user data");

            var response = server.send("GET", "/api/objects/demo-office-valid", null);
            assertEquals(200, response.statusCode(), response.body());
            var json = server.json(response);
            assertEquals("composition_approval", json.path("stateId").asText());
            assertEquals(321, json.path("parameters").path("quantity").intValue());

            assertEquals(200, server.send("POST", "/api/objects/demo-office-valid/actions",
                    "{\"userId\":\"user_manager\",\"actionId\":\"approve\"}").statusCode());

            var afterApproval = server.json(server.send("GET", "/api/objects/demo-office-valid", null));
            assertEquals("price_approval", afterApproval.path("stateId").asText());
            var history = server.json(server.send("GET", "/api/objects/demo-office-valid/history", null));
            assertEquals(4, history.size());
        }
    }

    /**
     * Тест-кейс: указание серверу файла, который уже существует, но не является валидным JSON
     * ("user-owned invalid json"), должно провалить запуск, а не молча сбросить файл до чистого
     * сида — защищая то, что у пользователя там уже было, даже если оно сломано.
     */
    @Test
    @Description
    @DisplayName("Повреждённый существующий store.json не сбрасывается при старте")
    @Story("Защита существующего хранилища при сбое")
    @Severity(SeverityLevel.CRITICAL)
    void corruptedExistingStoreIsNotResetOnStartup() throws Exception {
        Path store = directory.resolve("corrupted.json");
        Files.writeString(store, "user-owned invalid json");
        var failure = WorkflowServerProcess.expectStartupFailure(store);
        assertNotEquals(0, failure.exitCode());
        assertEquals("user-owned invalid json", Files.readString(store));
    }

    /**
     * Тест-кейс: запуск второго сервера на порту, который уже занят другим процессом, должен чисто
     * провалиться (без попытки на другом порту, без падения посреди записи) и не трогать существующий,
     * уже засеянный {@code store.json} — неудачный bind должен быть чистым no-op на диске.
     */
    @Test
    @Description
    @DisplayName("Занятый порт не даёт стартовать и не трогает существующий store.json")
    @Story("Защита существующего хранилища при сбое")
    @Severity(SeverityLevel.NORMAL)
    void occupiedPortDoesNotRewriteAnExistingStore() throws Exception {
        Path store = directory.resolve("occupied-port.json");
        try (var server = WorkflowServerProcess.start(store)) {
            // Первый запуск только для того, чтобы реальная логика сидирования создала файл; больше ничего не нужно.
            assertEquals(200, server.send("GET", "/api/objects", null).statusCode());
        }
        byte[] before = Files.readAllBytes(store);
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var failure = WorkflowServerProcess.expectStartupFailure(store, socket.getLocalPort());
            assertNotEquals(0, failure.exitCode());
        }
        assertArrayEquals(before, Files.readAllBytes(store));
    }
}
