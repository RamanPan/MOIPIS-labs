package ru.moipis.workflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Файловый журнал команд и попыток доставки. Каждая операция занимает одну строку JSONL. */
public final class CoordinationJournal {
    public enum Kind { START, ACTION, EVENT }
    public enum Outcome { SUCCESS, REJECTED, ERROR }

    public record Entry(String id, String timestamp, Kind kind, String objectId, String userId, String actionId,
                        String eventName, String sourceId, String recipientId, String parentId,
                        String before, String after, Outcome outcome, String reason,
                        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode command,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String explanation) {}

    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    CoordinationJournal(Path path) throws IOException {
        this.path = path.toAbsolutePath();
        Files.createDirectories(this.path.getParent());
    }

    /** Дописывает запись в UTF-8. При сбое записи пытается вернуть файлу прежнюю длину. */
    void append(Entry entry) throws IOException {
        var line = StandardCharsets.UTF_8.encode(mapper.writeValueAsString(entry) + "\n");
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long previousLength = channel.size();
            channel.position(previousLength);
            try {
                while (line.hasRemaining()) {
                    channel.write(line);
                }
            } catch (IOException failure) {
                try {
                    channel.truncate(previousLength);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    /** Возвращает исходный текст журнала для выгрузки или пустую строку до первой записи. */
    String text() throws IOException {
        return Files.notExists(path) ? "" : Files.readString(path);
    }

    /** Читает журнал построчно и возвращает записи в порядке их добавления. */
    List<Entry> entries() throws IOException {
        var result = new ArrayList<Entry>();
        for (String line : text().lines().toList()) {
            result.add(mapper.readValue(line, Entry.class));
        }
        return List.copyOf(result);
    }
}
