package ru.moipis.workflow.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WebResourceTest {
    @TempDir Path directory;

    @Test void servesLocalCytoscapeAndOrderModules() throws Exception {
        try (var fixture = new WebTestFixture(directory.resolve("store.json"))) {
            for (String path : java.util.List.of("/webjars/cytoscape/3.34.1/dist/cytoscape.min.js",
                    "/js/graph.js", "/js/order-detail.js", "/js/parameter-input.js", "/js/history.js")) {
                var response = fixture.send("GET", path, null);
                assertEquals(200, response.statusCode(), path);
                assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("javascript"));
                assertFalse(response.body().isBlank());
            }
            try (var input = getClass().getResourceAsStream(
                    "/META-INF/resources/webjars/cytoscape/3.34.1/dist/cytoscape.min.js")) {
                assertNotNull(input);
                assertTrue(input.readAllBytes().length > 100_000);
            }
        }
    }

    @Test void servesDashboardAndItsLocalCssAndJavascript() throws Exception {
        try (var fixture = new WebTestFixture(directory.resolve("store.json"))) {
            var page = fixture.send("GET", "/", null);
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.headers().firstValue("Content-Type").orElseThrow().contains("text/html"));
            for (String path : java.util.List.of("/css/app.css", "/js/api.js", "/js/app.js")) {
                var response = fixture.send("GET", path, null);
                assertEquals(200, response.statusCode(), path);
                assertFalse(response.body().isBlank(), path);
                String mime = response.headers().firstValue("Content-Type").orElseThrow();
                assertTrue(path.endsWith(".css") ? mime.contains("text/css") : mime.contains("javascript"), mime);
            }
        }
    }

    @Test void servesPublicClasspathResourcesWithoutCorsAndKeepsApi404AsJson() throws Exception {
        try (var fixture = new WebTestFixture(directory.resolve("store.json"))) {
            var resource = fixture.send("GET", "/resource-probe.txt", null);
            assertEquals(200, resource.statusCode(), resource.body());
            assertEquals("workflow static resource", resource.body().strip());
            assertTrue(resource.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            var missing = fixture.send("GET", "/api/missing", null);
            assertEquals(404, missing.statusCode());
            assertTrue(missing.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
            assertEquals("NOT_FOUND", fixture.json(missing).path("code").asText());
        }
    }
}
