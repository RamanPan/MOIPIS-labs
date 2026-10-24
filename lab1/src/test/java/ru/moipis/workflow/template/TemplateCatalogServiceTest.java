package ru.moipis.workflow.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.RoleDefinition;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.condition.model.ConditionLiteral;
import ru.moipis.workflow.condition.model.DnfCondition;
import ru.moipis.workflow.condition.model.PredicateDefinition;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.statemachine.factory.StateMachineConfiguration;
import ru.moipis.workflow.template.model.ActionDefinition;
import ru.moipis.workflow.template.model.ProcessTemplate;
import ru.moipis.workflow.template.model.StateDefinition;
import ru.moipis.workflow.template.model.TransitionDefinition;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateCatalogServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private CatalogService classes;
    private StoreData seed;
    private MemoryRepository repository;
    private TemplateCatalogService catalog;

    @BeforeEach
    void setUp() throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/seed/initial-data.json"))) {
            seed = mapper.readValue(input, StoreData.class);
        }
        classes = new CatalogService(seed.classes());
        repository = new MemoryRepository(store(List.of(template("standard", "standard_order"))));
        catalog = new TemplateCatalogService(classes, repository);
    }

    @Test
    void resolvesOwnTemplateAndInheritedTemplate() throws IOException {
        assertEquals("standard", catalog.getTemplate("standard").id());
        assertEquals("standard", catalog.resolveTemplate("standard_order").id());
        assertEquals("standard_order", catalog.resolveTemplate("office_order").classId());
        assertEquals("standard", catalog.resolveTemplate("consumables_order").id());
    }

    @Test
    void choosesTheClosestAncestor() throws IOException {
        repository.data = store(List.of(template("root", "order"), template("standard", "standard_order")));

        assertEquals("root", catalog.resolveTemplate("order").id());
        assertEquals("standard", catalog.resolveTemplate("office_order").id());
    }

    @Test
    void rejectsResolutionWithoutAnyTemplate() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> catalog.resolveTemplate("order"));
        repository.data = store(List.of());

        assertTrue(catalog.getTemplates().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> catalog.resolveTemplate("office_order"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.prepareStateMachineConfiguration("office_order"));
    }

    @Test
    void rejectsUnknownTemplateAndClass() {
        assertThrows(IllegalArgumentException.class, () -> catalog.getTemplate("missing"));
        assertThrows(IllegalArgumentException.class, () -> catalog.resolveTemplate("missing"));
        assertThrows(IllegalArgumentException.class, () -> catalog.copyTemplate("missing", "office_order", "office"));
        assertThrows(IllegalArgumentException.class, () -> catalog.copyTemplate("standard", "missing", "office"));
    }

    @Test
    void copiesTemplateAndPersistsItWithoutChangingTheOriginalOrSibling() throws IOException {
        ProcessTemplate original = catalog.getTemplate("standard");

        ProcessTemplate copied = catalog.copyTemplate("standard", "office_order", "office");

        assertEquals("office", copied.id());
        assertEquals("office_order", copied.classId());
        assertEquals(original.title(), copied.title());
        assertEquals(original.createRoles(), copied.createRoles());
        assertEquals(original.actions(), copied.actions());
        assertEquals(original.predicates(), copied.predicates());
        assertEquals(original.states(), copied.states());
        assertEquals(original.transitions(), copied.transitions());
        assertEquals(original, catalog.getTemplate("standard"));
        assertEquals("office", catalog.resolveTemplate("office_order").id());
        assertEquals("standard", catalog.resolveTemplate("consumables_order").id());
        assertEquals(List.of("standard", "office"), repository.load().templates().stream()
                .map(ProcessTemplate::id).toList());
        assertEquals("office", new TemplateCatalogService(classes, repository)
                .resolveTemplate("office_order").id());
    }

    @Test
    void cannotCreateASecondOwnTemplate() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.copyTemplate("standard", "standard_order", "duplicate"));
        catalog.copyTemplate("standard", "office_order", "office");

        assertThrows(IllegalArgumentException.class,
                () -> catalog.copyTemplate("standard", "office_order", "office2"));
        assertEquals(2, repository.load().templates().size());
    }

    @Test
    void rejectsDuplicateAndBlankNewTemplateIdsWithoutSaving() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.copyTemplate("standard", "office_order", "standard"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.copyTemplate("standard", "office_order", " "));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.copyTemplate("standard", "office_order", null));
        assertEquals(1, repository.data.templates().size());
    }

    @Test
    void deepCopiesMutablePredicateValuesAndNestedCollections() throws IOException {
        ProcessTemplate original = catalog.getTemplate("standard");
        ProcessTemplate copied = catalog.copyTemplate("standard", "office_order", "office");

        assertNotSame(original.actions(), copied.actions());
        assertNotSame(original.actions().getFirst(), copied.actions().getFirst());
        assertNotSame(original.states().getFirst(), copied.states().getFirst());
        assertNotSame(original.transitions().getFirst().condition().groups(),
                copied.transitions().getFirst().condition().groups());
        assertNotSame(original.transitions().getFirst().condition().groups().getFirst(),
                copied.transitions().getFirst().condition().groups().getFirst());
        ((ObjectNode) copied.predicates().getFirst().value()).put("limit", 7);

        assertEquals(100, catalog.getTemplate("standard").predicates().getFirst().value().get("limit").intValue());
        assertEquals(100, catalog.getTemplate("office").predicates().getFirst().value().get("limit").intValue());
        assertEquals(100, repository.data.templates().getFirst().predicates().getFirst().value().get("limit").intValue());
    }

    @Test
    void changingTheStoredSourceAfterCopyDoesNotChangeTheStoredCopy() throws IOException {
        catalog.copyTemplate("standard", "office_order", "office");
        ProcessTemplate storedSource = repository.data.templates().getFirst();
        ProcessTemplate storedCopy = repository.data.templates().get(1);

        assertNotSame(storedSource.predicates().getFirst().value(), storedCopy.predicates().getFirst().value());
        assertNotSame(storedSource.transitions().getFirst().condition().groups().getFirst(),
                storedCopy.transitions().getFirst().condition().groups().getFirst());
        ((ObjectNode) storedSource.predicates().getFirst().value()).put("limit", 12);

        assertEquals(12, catalog.getTemplate("standard").predicates().getFirst().value().get("limit").intValue());
        assertEquals(100, catalog.getTemplate("office").predicates().getFirst().value().get("limit").intValue());
    }

    @Test
    void rejectsCopyWhenTheTargetClassLacksAReferencedParameter() throws IOException {
        ProcessTemplate source = withPredicates(template("office", "office_order"), List.of(
                new PredicateDefinition("limit", "Location", "office_location", "eq", null)));
        repository.data = store(List.of(source));

        assertThrows(IllegalArgumentException.class,
                () -> catalog.copyTemplate("office", "consumables_order", "consumables"));
        assertEquals(List.of("office"), catalog.getTemplates().stream().map(ProcessTemplate::id).toList());
    }

    @Test
    void isolatesReturnedTemplatesFromRepositoryState() throws IOException {
        List<ProcessTemplate> listed = catalog.getTemplates();
        assertThrows(UnsupportedOperationException.class, listed::clear);
        assertThrows(UnsupportedOperationException.class, () -> listed.getFirst().states().clear());
        ((ObjectNode) listed.getFirst().predicates().getFirst().value()).put("limit", -1);

        assertEquals(100, catalog.getTemplate("standard").predicates().getFirst().value().get("limit").intValue());
    }

    @Test
    void preservesMetadataObjectsAndHistoryWhenSavingACopy() throws IOException {
        BusinessObject object = new BusinessObject("object", "standard_order", "draft", Map.of(), "standard");
        HistoryEntry history = new HistoryEntry("history", "object", "user", Instant.EPOCH, "created", null);
        repository.data = new StoreData(1, seed.classes(), List.of(new RoleDefinition("manager", "Manager")),
                List.of(new UserDefinition("user", "User", Set.of("manager"))), repository.data.templates(),
                List.of(object), List.of(history));
        StoreData before = repository.data;

        catalog.copyTemplate("standard", "office_order", "office");

        assertEquals(before.schemaVersion(), repository.data.schemaVersion());
        assertEquals(before.classes(), repository.data.classes());
        assertEquals(before.roles(), repository.data.roles());
        assertEquals(before.users(), repository.data.users());
        assertEquals(before.objects(), repository.data.objects());
        assertEquals(before.history(), repository.data.history());
    }

    @Test
    void doesNotPublishACopyWhenRepositorySaveFails() {
        repository.failOnSave = true;

        assertThrows(IOException.class, () -> catalog.copyTemplate("standard", "office_order", "office"));
        assertEquals(1, repository.data.templates().size());
        assertThrows(IllegalArgumentException.class, () -> catalog.getTemplate("office"));
    }

    @Test
    void preparesConfigurationForTheActualClassWithoutExecutingPredicates() throws IOException {
        StateMachineConfiguration configuration = catalog.prepareStateMachineConfiguration("office_order");

        assertEquals("office_order", configuration.classId());
        assertEquals("standard_order", configuration.template().classId());
        assertEquals("draft", configuration.initialStateId());
        assertEquals(new BigDecimal("500"), configuration.effectiveParameters().get("quantity").max());
        assertTrue(configuration.effectiveParameters().containsKey("office_location"));
        assertEquals("quantity", configuration.template().predicates().getFirst().parameterId());
        assertTrue(configuration.template().transitions().getFirst().condition().groups().getFirst().getFirst().negated());
        assertNull(configuration.template().transitions().get(1).condition());
        assertThrows(UnsupportedOperationException.class, () -> configuration.effectiveParameters().clear());
        assertEquals(new BigDecimal("10000"), catalog.prepareStateMachineConfiguration("consumables_order")
                .effectiveParameters().get("quantity").max());
    }

    @Test
    void allowsIncompleteTemplatesAtLoadButRequiresOneInitialStateToPrepareConfiguration() throws IOException {
        ProcessTemplate empty = new ProcessTemplate("empty", "Empty", "order", Set.of(),
                List.of(), List.of(), List.of(), List.of());
        TemplateCatalogService service = new TemplateCatalogService(classes, new MemoryRepository(store(List.of(empty))));

        assertEquals("empty", service.resolveTemplate("office_order").id());
        assertThrows(IllegalArgumentException.class, () -> service.prepareStateMachineConfiguration("office_order"));
        ProcessTemplate multiple = withStates(template("multiple", "order"), List.of(
                state("draft", true), state("done", true)));
        TemplateCatalogService multipleService = new TemplateCatalogService(classes,
                new MemoryRepository(store(List.of(multiple))));
        assertThrows(IllegalArgumentException.class, () -> multipleService.prepareStateMachineConfiguration("order"));
    }

    @Test
    void persistsCopiesThroughTheRepositoryAndSupportsJsonRoundTrip(@TempDir Path directory) throws IOException {
        Path storePath = directory.resolve("store.json");
        Repository jsonRepository = new Repository() {
            @Override
            public synchronized StoreData update(UnaryOperator<StoreData> change) throws IOException {
                StoreData prepared = change.apply(load());
                save(prepared);
                return prepared;
            }

            @Override
            public HistoryEntry commit(ObjectChange change) {
                throw new UnsupportedOperationException("Object operations are not used in template tests");
            }

            @Override
            public StoreData loadOrInitialize() throws IOException {
                return load();
            }

            @Override
            public StoreData load() throws IOException {
                return mapper.readValue(storePath.toFile(), StoreData.class);
            }

            @Override
            public void save(StoreData data) throws IOException {
                mapper.writeValue(storePath.toFile(), data);
            }
        };
        jsonRepository.save(repository.data);
        TemplateCatalogService service = new TemplateCatalogService(classes, jsonRepository);
        service.copyTemplate("standard", "office_order", "office");

        TemplateCatalogService reloaded = new TemplateCatalogService(classes, jsonRepository);
        assertEquals("office", reloaded.resolveTemplate("office_order").id());
        assertEquals("standard", reloaded.resolveTemplate("consumables_order").id());
        assertEquals(repository.data.templates().getFirst(), reloaded.getTemplate("standard"));
        assertTrue(reloaded.getTemplate("office").states().get(1).terminal());
    }

    @Test
    void rejectsDuplicateTemplateIdsAndMultipleOwnTemplatesAtLoad() {
        assertThrows(IllegalArgumentException.class, () -> new TemplateCatalogService(classes,
                new MemoryRepository(store(List.of(template("same", "order"), template("same", "standard_order"))))));
        assertThrows(IllegalArgumentException.class, () -> new TemplateCatalogService(classes,
                new MemoryRepository(store(List.of(template("first", "order"), template("second", "order"))))));
    }

    @ParameterizedTest
    @MethodSource("templatesWithInvalidReferences")
    void rejectsBrokenReferencesAndDuplicateElementIdsAtLoad(ProcessTemplate invalid) {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateCatalogService(classes, new MemoryRepository(store(List.of(invalid)))));
    }

    static Stream<ProcessTemplate> templatesWithInvalidReferences() {
        ProcessTemplate valid = template("standard", "standard_order");
        List<ProcessTemplate> invalid = new ArrayList<>();
        invalid.add(new ProcessTemplate(valid.id(), valid.title(), "missing", valid.createRoles(), valid.actions(),
                valid.predicates(), valid.states(), valid.transitions()));
        invalid.add(new ProcessTemplate(valid.id(), valid.title(), valid.classId(), Set.of("missing"), valid.actions(),
                valid.predicates(), valid.states(), valid.transitions()));
        invalid.add(new ProcessTemplate(valid.id(), valid.title(), valid.classId(), valid.createRoles(),
                List.of(new ActionDefinition("finish", "First"), new ActionDefinition("finish", "Second")),
                valid.predicates(), valid.states(), valid.transitions()));
        invalid.add(withStates(valid, List.of(state("draft", true), state("draft", false))));
        invalid.add(withStates(valid, List.of(new StateDefinition("draft", "Draft", true, false,
                Set.of("missing"), Set.of()), state("done", false))));
        invalid.add(withStates(valid, List.of(new StateDefinition("draft", "Draft", true, false,
                Set.of(), Set.of("missing")), state("done", false))));
        invalid.add(withPredicates(valid, List.of(new PredicateDefinition("limit", "Limit", "missing", "le", null))));
        invalid.add(withPredicates(valid, List.of(valid.predicates().getFirst(), valid.predicates().getFirst())));
        invalid.add(withTransitions(valid, List.of(new TransitionDefinition("missing", "finish", "done", null))));
        invalid.add(withTransitions(valid, List.of(new TransitionDefinition("draft", "missing", "done", null))));
        invalid.add(withTransitions(valid, List.of(new TransitionDefinition("draft", "finish", "missing", null))));
        invalid.add(withTransitions(valid, List.of(new TransitionDefinition("draft", "finish", "done",
                new DnfCondition(List.of(List.of(new ConditionLiteral("missing", false))))))));
        return invalid.stream();
    }

    private StoreData store(List<ProcessTemplate> templates) {
        return new StoreData(1, seed.classes(), List.of(new RoleDefinition("manager", "Manager")),
                List.of(), templates, List.of(), List.of());
    }

    private static ProcessTemplate template(String id, String classId) {
        ObjectNode operand = new ObjectMapper().createObjectNode().put("limit", 100);
        return new ProcessTemplate(id, "Template", classId, Set.of("manager"),
                List.of(new ActionDefinition("finish", "Finish")),
                List.of(new PredicateDefinition("limit", "Limit", "quantity", "future_operator", operand)),
                List.of(state("draft", true), state("done", false)),
                List.of(new TransitionDefinition("draft", "finish", "done",
                                new DnfCondition(List.of(List.of(new ConditionLiteral("limit", true))))),
                        new TransitionDefinition("done", "finish", "done", null)));
    }

    private static StateDefinition state(String id, boolean initial) {
        return new StateDefinition(id, id, initial, id.equals("done"), Set.of("manager"), Set.of("quantity"));
    }

    private static ProcessTemplate withStates(ProcessTemplate template, List<StateDefinition> states) {
        return new ProcessTemplate(template.id(), template.title(), template.classId(), template.createRoles(),
                template.actions(), template.predicates(), states, template.transitions());
    }

    private static ProcessTemplate withPredicates(ProcessTemplate template, List<PredicateDefinition> predicates) {
        return new ProcessTemplate(template.id(), template.title(), template.classId(), template.createRoles(),
                template.actions(), predicates, template.states(), template.transitions());
    }

    private static ProcessTemplate withTransitions(ProcessTemplate template, List<TransitionDefinition> transitions) {
        return new ProcessTemplate(template.id(), template.title(), template.classId(), template.createRoles(),
                template.actions(), template.predicates(), template.states(), transitions);
    }

    private static final class MemoryRepository implements Repository {

        @Override
        public synchronized StoreData update(UnaryOperator<StoreData> change) throws IOException {
            StoreData prepared = change.apply(load());
            save(prepared);
            return prepared;
        }

        @Override
        public HistoryEntry commit(ObjectChange change) {
            throw new UnsupportedOperationException("Object operations are not used in template tests");
        }

        private StoreData data;
        private boolean failOnSave;

        private MemoryRepository(StoreData data) {
            this.data = data;
        }

        @Override
        public StoreData loadOrInitialize() {
            return load();
        }

        @Override
        public StoreData load() {
            return data;
        }

        @Override
        public void save(StoreData data) throws IOException {
            if (failOnSave) {
                throw new IOException("Save failed");
            }
            this.data = data;
        }
    }
}
