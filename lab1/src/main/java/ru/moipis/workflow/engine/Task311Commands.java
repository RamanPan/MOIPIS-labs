package ru.moipis.workflow.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import ru.moipis.workflow.engine.CoordinationJournal.Entry;
import ru.moipis.workflow.engine.CoordinationJournal.Kind;
import ru.moipis.workflow.repository.model.StoreData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One command and audit boundary shared by the CLI and HTTP application. */
public final class Task311Commands {
    public record Snapshot(Map<String, CoordinationSession.ProcessSnapshot> processes, List<Entry> journal) {}

    private final StoreData catalog;
    private final List<ConfiguredObject> objects;
    private final CoordinationSession session;
    private final CoordinationJournal journal;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    Task311Commands(StoreData catalog, List<ConfiguredObject> objects, CoordinationSession session,
                    CoordinationJournal journal) {
        this.catalog = catalog;
        this.objects = objects;
        this.session = session;
        this.journal = journal;
    }

    public static Path newJournalPath() throws IOException {
        var folder = Files.createDirectories(Path.of("demo", "task-3-1-1"));
        return Files.createTempDirectory(folder, "run-").resolve("journal.jsonl");
    }

    public StoreData catalog() { return catalog; }

    public List<ConfiguredObject> objects() { return objects; }

    public synchronized Optional<CoordinationSession.ProcessSnapshot> findProcess(String objectId) {
        return session.findProcess(objectId);
    }

    /**
     * Разбирает JSON-команду запуска или действия и передаёт её в сеанс.
     * Если JSON повреждён, сохраняет исходную строку для записи об ошибке.
     */
    public synchronized Entry submit(String rawCommand) throws IOException {
        JsonNode command;
        try {
            command = mapper.readTree(rawCommand);
        } catch (JsonProcessingException invalid) {
            command = TextNode.valueOf(rawCommand);
        }
        if (command == null || command.isMissingNode()) {
            command = TextNode.valueOf(rawCommand);
        }
        String type = argument(command, "type");
        Kind kind = switch (type == null ? "" : type) {
            case "start" -> Kind.START;
            case "action" -> Kind.ACTION;
            default -> null;
        };
        return session.submit(kind, argument(command, "userId"), argument(command, "objectId"),
                argument(command, "actionId"), command);
    }

    public synchronized List<Entry> journal() throws IOException {
        return journal.entries();
    }

    /** Читает состояния процессов и журнал для отображения текущего сеанса. */
    public synchronized Snapshot snapshot() throws IOException {
        return new Snapshot(session.processSnapshots(), journal.entries());
    }

    public synchronized String journalText() throws IOException {
        return journal.text();
    }

    /** Возвращает непустую строку из поля команды или {@code null}, если поле некорректно. */
    private static String argument(JsonNode command, String key) {
        JsonNode value = command.path(key);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }
}
