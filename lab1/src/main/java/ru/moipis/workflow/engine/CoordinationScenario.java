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

public record CoordinationScenario(StoreData catalog, List<ConfiguredObject> objects) {
    public static CoordinationScenario read(InputStream input) throws IOException {
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(input, CoordinationScenario.class);
    }

    public static CoordinationScenario demo() throws IOException {
        try (InputStream input = CoordinationScenario.class.getResourceAsStream("/seed/coordination-3-1-2.json")) {
            return read(Objects.requireNonNull(input, "Coordination demo configuration"));
        }
    }

    public CoordinationSession open(Path log) throws IOException {
        CatalogService classes = new CatalogService(catalog.classes());
        TemplateCatalogService templates = new TemplateCatalogService(classes, new CatalogSnapshot(catalog));
        Map<String, ConfiguredObject> indexed = new LinkedHashMap<>();
        for (ConfiguredObject object : objects) {
            if (indexed.putIfAbsent(object.id(), object) != null) {
                throw new IllegalArgumentException("Duplicate object: " + object.id());
            }
            var parameters = classes.getEffectiveParameters(object.classId());
            object.values().forEach((id, value) -> ParameterValues.validate(id, value, parameters));
        }
        for (ConfiguredObject object : objects) {
            object.links().forEach((link, targets) -> {
                if (link.isBlank() || !indexed.keySet().containsAll(targets)) {
                    throw new IllegalArgumentException("Invalid link " + link + " on " + object.id());
                }
            });
        }
        return new CoordinationSession(indexed, catalog.users(), classes, templates.getTemplates(),
                new CoordinationJournal(log));
    }

    private record CatalogSnapshot(StoreData data) implements Repository {
        @Override public StoreData load() { return data; }
        @Override public StoreData loadOrInitialize() { return data; }
        @Override public void save(StoreData replacement) { throw new UnsupportedOperationException(); }
        @Override public StoreData update(UnaryOperator<StoreData> change) { throw new UnsupportedOperationException(); }
        @Override public HistoryEntry commit(ObjectChange change) { throw new UnsupportedOperationException(); }
    }
}
