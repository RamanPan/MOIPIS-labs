package ru.moipis.workflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Manual Chrome checks use an explicitly prepared fixture and an ephemeral port. */
public final class BrowserTestServer {
    private BrowserTestServer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected test store and URL output file");
        Path store = Path.of(args[0]);
        var data = new ObjectMapper().readTree(store.toFile());
        boolean fixture = false;
        for (var object : data.path("objects")) {
            if (object.path("id").asText().equals("ux-no-history")
                    && object.path("parameters").path("order_number").asText().equals("LEGACY-NO-HISTORY")) {
                fixture = true;
            }
        }
        if (!fixture) throw new IllegalArgumentException("Refusing to start without browser fixture marker");
        try (WebApplication app = WebApplication.start(store, 0)) {
            Runtime.getRuntime().addShutdownHook(new Thread(app::close, "browser-test-shutdown"));
            String url = "http://127.0.0.1:" + app.port();
            Files.writeString(Path.of(args[1]), url);
            System.out.println("Isolated browser test server: " + url);
            new CountDownLatch(1).await();
        }
    }
}
