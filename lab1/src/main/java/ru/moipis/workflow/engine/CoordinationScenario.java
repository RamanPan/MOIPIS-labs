package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.template.TemplateCatalogService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Исходные данные лабораторного сценария: каталог моделей и готовые объекты. */
public record CoordinationScenario(StoreData catalog, List<ConfiguredObject> objects) {
    /** Читает сценарий из JSON, сохраняя точность дробных значений параметров. */
    public static CoordinationScenario read(InputStream input) throws IOException {
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(input, CoordinationScenario.class);
    }

    public static CoordinationScenario demo() throws IOException {
        return resource("/seed/coordination-3-1-2.json");
    }

    public static CoordinationScenario task311() throws IOException {
        return resource("/seed/task-3-1-1.json");
    }

    private static CoordinationScenario resource(String name) throws IOException {
        try (InputStream input = CoordinationScenario.class.getResourceAsStream(name)) {
            return read(Objects.requireNonNull(input, name));
        }
    }

    /** Открывает сеанс 3-1-2 с поддержкой связанных процессов и событий. */
    public CoordinationSession open(Path log) throws IOException {
        return open(new CoordinationJournal(log), false).session();
    }

    /** Открывает сеанс 3-1-1 и возвращает обработчик JSON-команд для консоли и HTTP. */
    public Task311Commands openTask311(Path log) throws IOException {
        var journal = new CoordinationJournal(log);
        var opened = open(journal, true);
        return new Task311Commands(catalog, opened.objects(), opened.session(), journal);
    }

    private record Opened(List<ConfiguredObject> objects, CoordinationSession session) {}

    /**
     * Собирает каталог и проверяет шаблоны, типы параметров, ID объектов и их связи.
     * В режиме 3-1-1 подставляет значения по умолчанию и отклоняет правила координации.
     */
    private Opened open(CoordinationJournal journal, boolean task311) throws IOException {
        CatalogService classes = new CatalogService(catalog.classes());
        var templates = new TemplateCatalogService(classes, new CatalogSnapshot(catalog), task311).getTemplates();
        if (task311) {
            for (var template : templates) {
                if (!template.eventTransitions().isEmpty()
                        || template.transitions().stream().anyMatch(t -> !t.emit().isEmpty())
                        || template.predicates().stream().anyMatch(p -> p.relatedProcess() != null)) {
                    throw new IllegalArgumentException("Process coordination requires the coordination profile");
                }
            }
        }
        Map<String, ConfiguredObject> indexed = new LinkedHashMap<>();
        for (ConfiguredObject source : objects) {
            ConfiguredObject object = source;
            var parameters = classes.getEffectiveParameters(object.classId());
            if (task311) {
                var values = ParameterValues.copy(source.values());
                parameters.forEach((id, definition) -> {
                    if (definition.defaultValue() != null) {
                        ParameterValues.validate(id, definition.defaultValue(), parameters);
                        // Значение по умолчанию действует только при отсутствии ключа. Явный null сохраняется.
                        if (!values.containsKey(id)) {
                            values.put(id, definition.defaultValue());
                        }
                    }
                });
                object = new ConfiguredObject(source.id(), source.classId(), values, source.links());
            }
            object.values().forEach((id, value) -> ParameterValues.validate(id, value, parameters));
            if (indexed.putIfAbsent(object.id(), object) != null) {
                throw new IllegalArgumentException("Duplicate object: " + object.id());
            }
        }
        for (ConfiguredObject object : indexed.values()) {
            object.links().forEach((link, targets) -> {
                if (link.isBlank() || !indexed.keySet().containsAll(targets)) {
                    throw new IllegalArgumentException("Invalid link " + link + " on " + object.id());
                }
            });
        }
        return new Opened(List.copyOf(indexed.values()),
                new CoordinationSession(indexed, catalog.users(), classes, templates, journal, task311));
    }

    private record CatalogSnapshot(StoreData data) implements Repository {
        @Override public StoreData load() { return data; }
        @Override public StoreData loadOrInitialize() { return data; }
        @Override public void save(StoreData replacement) { throw new UnsupportedOperationException(); }
        @Override public StoreData update(UnaryOperator<StoreData> change) { throw new UnsupportedOperationException(); }
        @Override public HistoryEntry commit(ObjectChange change) { throw new UnsupportedOperationException(); }
    }
}
