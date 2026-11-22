package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class CoordinationJournal {
    public enum Kind { START, ACTION, EVENT }
    public enum Outcome { SUCCESS, REJECTED, ERROR }

    public record Entry(String id, String timestamp, Kind kind, String objectId, String userId, String actionId,
                        String eventName, String sourceId, String recipientId, String parentId,
                        String before, String after, Outcome outcome, String reason) {}

    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper();

    CoordinationJournal(Path path) throws IOException {
        this.path = path.toAbsolutePath();
        Files.createDirectories(this.path.getParent());
    }

    void append(Entry entry) throws IOException {
        Files.writeString(path, mapper.writeValueAsString(entry) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
