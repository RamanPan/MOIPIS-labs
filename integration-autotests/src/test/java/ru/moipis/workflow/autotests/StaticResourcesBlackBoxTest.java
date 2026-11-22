package ru.moipis.workflow.autotests;

import io.qameta.allure.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.moipis.workflow.autotests.utils.SharedServer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тест-план: всё, что сервер отдаёт помимо {@code /api/**} — страница dashboard и её локальные
 * CSS/JS/WebJar-ресурсы — плюс граница между статическими файлами и JSON API.
 *
 * <p>Ни один из сценариев не зависит от состояния объектов или seed-данных, поэтому все они делят
 * один процесс сервера на весь прогон ({@link SharedServer}) вместо отдельного процесса на тест.
 */
@Epic("Workflow REST API")
@Feature("Статика и dashboard")
@ExtendWith(SharedServer.class)
class StaticResourcesBlackBoxTest {
    /**
     * Тест-кейс: встроенный Cytoscape WebJar и каждый локальный JS-модуль dashboard (graph,
     * order-detail, parameter-input, history) отдаются с типом content JavaScript и непустым телом;
     * отдельно проверяется, что бандл Cytoscape — настоящая библиотека ~100KB+, а не заглушка.
     */
    @Test
    @Description
    @DisplayName("Cytoscape WebJar и JS-модули dashboard отдаются с правильным типом")
    @Story("JS-модули и библиотеки")
    @Severity(SeverityLevel.MINOR)
    void servesLocalCytoscapeAndOrderModules() throws Exception {
        var server = SharedServer.get();
        for (String path : List.of("/webjars/cytoscape/3.34.1/dist/cytoscape.min.js",
                "/js/graph.js", "/js/order-detail.js", "/js/parameter-input.js", "/js/history.js")) {
            var response = server.send("GET", path, null);
            assertEquals(200, response.statusCode(), path);
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("javascript"));
            assertFalse(response.body().isBlank());
        }
        var cytoscape = server.send("GET", "/webjars/cytoscape/3.34.1/dist/cytoscape.min.js", null);
        assertTrue(cytoscape.body().length() > 100_000, "Expected the real bundled library, not a stub");
    }

    /**
     * Тест-кейс: статический ресурс отдаётся без заголовка CORS — dashboard работает только в
     * рамках same-origin, как и API, которому кросс-origin доступ тоже явно не нужен.
     */
    @Test
    @Description
    @DisplayName("Статический ресурс отдаётся без заголовка CORS")
    @Story("Заголовки статических ресурсов")
    @Severity(SeverityLevel.MINOR)
    void servesStaticResourcesWithoutCors() throws Exception {
        var server = SharedServer.get();
        var resource = server.send("GET", "/css/app.css", null);
        assertEquals(200, resource.statusCode(), resource.body());
        assertTrue(resource.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    /**
     * Тест-кейс: корневой путь отдаёт HTML-страницу dashboard, а каждый её локальный CSS/JS-ресурс
     * отдаётся с непустым телом и соответствующим content type (CSS для {@code .css}, иначе
     * JavaScript).
     */
    @Test
    @Description
    @DisplayName("Главная страница dashboard и её CSS/JS отдаются с верным Content-Type")
    @Story("Главная страница dashboard")
    @Severity(SeverityLevel.NORMAL)
    void servesDashboardAndItsLocalCssAndJavascript() throws Exception {
        var server = SharedServer.get();
        var page = server.send("GET", "/", null);
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().contains("text/html"));
        for (String path : List.of("/css/app.css", "/js/api.js", "/js/app.js")) {
            var response = server.send("GET", path, null);
            assertEquals(200, response.statusCode(), path);
            assertFalse(response.body().isBlank(), path);
            String mime = response.headers().firstValue("Content-Type").orElseThrow();
            assertTrue(path.endsWith(".css") ? mime.contains("text/css") : mime.contains("javascript"), mime);
        }
    }

    /**
     * Тест-кейс: неизвестный путь {@code /api/**} всё равно возвращает собственную JSON-форму 404
     * API ({@code code=NOT_FOUND}), а не проваливается в общую HTML-страницу 404 от обработчика
     * статики — эти два обработчика должны оставаться чётко разделёнными.
     */
    @Test
    @Description
    @DisplayName("Неизвестный путь /api/** остаётся JSON 404, не HTML-страницей")
    @Story("Разделение API и статики")
    @Severity(SeverityLevel.NORMAL)
    void keepsApi404AsJson() throws Exception {
        var server = SharedServer.get();
        var missing = server.send("GET", "/api/missing", null);
        assertEquals(404, missing.statusCode());
        assertTrue(missing.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
        assertEquals("NOT_FOUND", server.json(missing).path("code").asText());
    }
}
