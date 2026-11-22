package ru.moipis.workflow.autotests.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Запускает настоящее консольное приложение координации как отдельный процесс ОС (через
 * {@code mvn -Pcoordination compile exec:java} — ту же команду, которую README проекта описывает для
 * лабораторной работы 3-1-2) и общается с ним только так, как это делает человек: пишет команды в stdin,
 * читает stdout и JSONL-журнал, путь к которому приложение печатает при старте. Здесь нет ни одного
 * импорта класса из основного кода.
 *
 * <p>Приложение не печатает «конец ответа» после команды: событий доставки может быть ноль, одно или
 * несколько. Поэтому после каждой команды тест отправляет заведомо некорректную строку {@code ?} —
 * приложение отвечает на неё подсказкой {@code Expected start ...} и ничего не пишет в журнал. Эта
 * подсказка служит маркером конца вывода предыдущей команды.
 */
public final class CoordinationProcess implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JOURNAL_PREFIX = "Journal: ";
    private static final String END_MARKER_COMMAND = "?";
    private static final String END_MARKER_OUTPUT = "Expected start";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final Process process;
    private final BlockingQueue<String> lines;
    private final BufferedWriter stdin;
    private final Path journalPath;
    private int journalLinesSeen;

    private CoordinationProcess(Process process, BlockingQueue<String> lines, Path journalPath) {
        this.process = process;
        this.lines = lines;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.journalPath = journalPath;
    }

    /**
     * Запускает приложение со встроенной демонстрационной конфигурацией (заказ {@code order-1}, заказчик
     * {@code company-1}).
     */
    public static CoordinationProcess start() throws IOException {
        return start(null);
    }

    /**
     * Запускает приложение с файлом конфигурации, переданным первым аргументом командной строки.
     */
    public static CoordinationProcess start(Path configuration) throws IOException {
        List<String> command = new ArrayList<>(List.of("mvn", "-q", "-Pcoordination", "compile", "exec:java"));
        if (configuration != null) {
            command.add("-Dexec.args=\"" + configuration.toAbsolutePath() + "\"");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(WorkflowServerProcess.lab1Directory().toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BlockingQueue<String> lines = pump(process);

        List<String> startupOutput = new ArrayList<>();
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String line = poll(lines, 200);
            if (line == null) {
                if (!process.isAlive()) break;
                continue;
            }
            if (line.startsWith(JOURNAL_PREFIX)) {
                return new CoordinationProcess(process, lines, Path.of(line.substring(JOURNAL_PREFIX.length()).strip()));
            }
            startupOutput.add(line);
        }
        process.destroyForcibly();
        throw new IllegalStateException("Coordination application did not print the journal path within "
                + READY_TIMEOUT + ". Output: " + startupOutput);
    }

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
        }, "coordination-stdout");
        pump.setDaemon(true);
        pump.start();
        return lines;
    }

    private static String poll(BlockingQueue<String> lines, long millis) {
        try {
            return lines.poll(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Interrupted while waiting for the application output"));
        }
    }

    /**
     * Отправляет одну команду ({@code start <user> <object>} или {@code action <user> <object> <action>}),
     * дожидается конца её вывода и возвращает напечатанные строки вместе с записями журнала, которые эта
     * команда добавила.
     */
    public Step run(String command) throws IOException {
        stdin.write(command);
        stdin.newLine();
        stdin.write(END_MARKER_COMMAND);
        stdin.newLine();
        stdin.flush();

        List<String> console = new ArrayList<>();
        long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
        while (true) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("No end of output for command '" + command + "'. Output so far: " + console);
            }
            String line = poll(lines, 200);
            if (line == null) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("Application exited while running '" + command + "'. Output: " + console);
                }
                continue;
            }
            if (line.startsWith(END_MARKER_OUTPUT)) break;
            console.add(line);
        }

        List<JsonNode> all = journal();
        List<JsonNode> added = List.copyOf(all.subList(journalLinesSeen, all.size()));
        journalLinesSeen = all.size();
        return new Step(command, List.copyOf(console), added);
    }

    /**
     * Читает журнал с диска целиком — как обычный JSONL-файл, не зная устройства приложения.
     */
    public List<JsonNode> journal() throws IOException {
        if (Files.notExists(journalPath)) return List.of();
        List<JsonNode> entries = new ArrayList<>();
        for (String line : Files.readAllLines(journalPath, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) entries.add(MAPPER.readTree(line));
        }
        return entries;
    }

    public Path journalPath() {
        return journalPath;
    }

    /**
     * Закрывает stdin (приложение завершает цикл чтения) и останавливает процесс.
     */
    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // Процесс мог уже завершиться.
        }
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * Результат одной команды: что она напечатала в консоль и какие записи добавила в журнал. Первая запись
     * журнала — сама команда, остальные — попытки доставки порождённых ею событий (в порядке создания).
     */
    public record Step(String input, List<String> console, List<JsonNode> entries) {
        public JsonNode command() {
            return entries.getFirst();
        }

        public List<JsonNode> deliveries() {
            return entries.subList(1, entries.size());
        }
    }
}
