package ru.moipis.workflow.autotests.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Запускает настоящее {@code WebApplication} как отдельный процесс ОС (через {@code mvn -Pweb exec:java} —
 * ту же команду, которую README проекта описывает для запуска сервера) и общается с ним только по
 * настоящему HTTP, точно как любой другой REST-клиент. Здесь нет ни одного импорта класса из основного кода.
 */
public final class WorkflowServerProcess implements AutoCloseable {
    private static final Pattern READY_LINE = Pattern.compile("Workflow API: http://127\\.0\\.0\\.1:(\\d+)/api/objects");
    // Сохраняет полную точность чисел (точных decimal/больших целых сервера) при обратном чтении JSON.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final Process process;
    private final int port;
    private final Path storePath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private WorkflowServerProcess(Process process, int port, Path storePath) {
        this.process = process;
        this.port = port;
        this.storePath = storePath;
    }

    /**
     * Запускает сервер на эфемерном порту и ждёт готовности не дольше 60 секунд.
     */
    public static WorkflowServerProcess start(Path storePath) throws IOException {
        return start(storePath, 0, Duration.ofSeconds(60));
    }

    /**
     * Запускает сервер на заданном порту (0 — выбрать свободный) и ждёт строку готовности в его stdout.
     */
    public static WorkflowServerProcess start(Path storePath, int port, Duration readyTimeout) throws IOException {
        Process process = launch(storePath, port);
        BlockingQueue<String> lines = pump(process);
        Integer boundPort = awaitPort(lines, process, readyTimeout);
        if (boundPort == null) {
            process.destroyForcibly();
            throw new IllegalStateException("Server for " + storePath + " did not become ready within " + readyTimeout);
        }
        return new WorkflowServerProcess(process, boundPort, storePath);
    }

    /**
     * Запускает процесс на эфемерном порту и ожидает, что он упадёт, так и не напечатав строку готовности.
     */
    public static StartupFailure expectStartupFailure(Path storePath) throws IOException, InterruptedException {
        return expectStartupFailure(storePath, 0);
    }

    /**
     * Запускает процесс на заданном порту (например, уже занятом) и ожидает, что старт завершится ошибкой.
     */
    public static StartupFailure expectStartupFailure(Path storePath, int port) throws IOException, InterruptedException {
        Duration timeout = Duration.ofSeconds(30);
        Process process = launch(storePath, port);
        BlockingQueue<String> lines = pump(process);
        Integer boundPort = awaitPort(lines, process, timeout);
        if (boundPort != null) {
            new WorkflowServerProcess(process, boundPort, storePath).close();
            throw new AssertionError("Expected the server to fail to start, but it became ready on port " + boundPort);
        }
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Expected the server to fail to start, but it neither became ready nor exited");
        }
        List<String> output = new ArrayList<>();
        String line;
        while ((line = lines.poll()) != null) output.add(line);
        return new StartupFailure(process.exitValue(), output);
    }

    /**
     * Собирает и запускает процесс {@code mvn -Pweb exec:java} с нужным хранилищем и портом.
     */
    private static Process launch(Path storePath, int port) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("mvn", "-q", "-Pweb", "compile", "exec:java",
                "-Dexec.args=--store \"" + storePath.toAbsolutePath() + "\" --port " + port);
        builder.directory(lab1Directory().toFile());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    /**
     * Запускает фоновый поток, который построчно вычитывает stdout процесса в очередь.
     */
    private static BlockingQueue<String> pump(Process process) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            } catch (IOException ignored) {
                // Процесс завершился; больше нечего читать.
            }
        }, "workflow-server-stdout");
        pump.setDaemon(true);
        pump.start();
        return lines;
    }

    /**
     * Ждёт в очереди строку готовности с номером порта, пока процесс жив и не истёк таймаут.
     */
    private static Integer awaitPort(BlockingQueue<String> lines, Process process, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String line;
            try {
                line = lines.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (line != null) {
                var matcher = READY_LINE.matcher(line);
                if (matcher.find()) return Integer.parseInt(matcher.group(1));
            } else if (!process.isAlive()) {
                return null;
            }
        }
        return null;
    }

    /**
     * Определяет каталог проекта {@code lab1} (через system property, env переменную или путь по умолчанию).
     */
    static Path lab1Directory() {
        String override = System.getProperty("lab1.dir", System.getenv("LAB1_DIR"));
        Path directory = override != null ? Path.of(override) : Path.of("").toAbsolutePath().resolveSibling("lab1");
        if (!Files.isRegularFile(directory.resolve("pom.xml"))) {
            throw new IllegalStateException("Expected the workflow-engine project at " + directory
                    + "; set -Dlab1.dir or LAB1_DIR to override");
        }
        return directory;
    }

    /**
     * Файл, которым владеет этот процесс сервера — тот же путь, что был передан в {@link #start}.
     */
    public Path storePath() {
        return storePath;
    }

    /**
     * Отправляет настоящий HTTP-запрос запущенному серверу и возвращает его ответ.
     */
    public HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Разбирает тело HTTP-ответа как JSON-дерево.
     */
    public JsonNode json(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    /**
     * Корректно (а при необходимости — принудительно) останавливает процесс сервера и HTTP-клиент.
     */
    @Override
    public void close() {
        http.close();
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    public record StartupFailure(int exitCode, List<String> output) {
    }
}
