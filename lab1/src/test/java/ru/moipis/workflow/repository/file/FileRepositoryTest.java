package ru.moipis.workflow.repository.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.catalog.CatalogService;
import ru.moipis.workflow.catalog.model.RoleDefinition;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.engine.model.BusinessObject;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;
import ru.moipis.workflow.template.TemplateCatalogService;
import ru.moipis.workflow.template.model.ProcessTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-22T09:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path directory;

    @BeforeEach
    void prepareIsolatedRepository() throws IOException {
        Repository repository = repository();
        StoreData seed = repository.loadOrInitialize();
        repository.save(new StoreData(seed.schemaVersion(), seed.classes(), seed.roles(), seed.users(),
                seed.templates(), List.of(), List.of()));
    }

    @Test
    void initializesMissingStoreFromClasspathSeed() throws IOException {
        Path path = directory.resolve("nested/data/store.json");
        Repository repository = new FileRepository(path, CLOCK);

        StoreData store = repository.loadOrInitialize();

        assertTrue(Files.isRegularFile(path));
        assertEquals(List.of("order", "standard_order", "office_order", "consumables_order"),
                store.classes().stream().map(definition -> definition.id()).toList());
        assertEquals(5, store.objects().size());
        assertEquals(5, store.history().size());
        assertEquals(store, new FileRepository(path, CLOCK).load());
    }

    @Test
    void existingStoreAndHistoryAreNeverReplacedWithSeed() throws IOException {
        Repository repository = repository();
        repository.loadOrInitialize();
        BusinessObject object = object("first", "start", 10);
        repository.commit(ObjectChange.create(object, "user"));
        byte[] original = Files.readAllBytes(path());

        StoreData restored = new FileRepository(path(), CLOCK).loadOrInitialize();

        assertEquals(List.of(object), restored.objects());
        assertEquals(1, restored.history().size());
        assertArrayEquals(original, Files.readAllBytes(path()));
    }

    @Test
    void createAtomicallyStoresObjectAndExactlyOneHistoryEntryWithInjectedTime() throws IOException {
        Repository repository = repository();
        repository.loadOrInitialize();
        BusinessObject object = object("first", "start", 10);

        HistoryEntry entry = repository.commit(ObjectChange.create(object, "creator"));

        StoreData loaded = repository.load();
        assertEquals(List.of(object), loaded.objects());
        assertEquals(List.of(entry), loaded.history());
        assertNotNull(entry.id());
        assertEquals("first", entry.objectId());
        assertEquals("creator", entry.userId());
        assertEquals("CREATE", entry.operation());
        assertEquals(NOW, entry.timestamp());
        assertEquals("start", entry.details().get("initialState").textValue());
        assertEquals(10, entry.details().get("parameters").get("quantity").intValue());
        String json = Files.readString(path());
        assertTrue(json.contains("2026-10-22T09:30:00Z"));
    }

    @Test
    void actionAndParameterUpdateEachCreateOneCorrespondingHistoryEntry() throws IOException {
        Repository repository = repository();
        repository.loadOrInitialize();
        BusinessObject original = object("first", "start", 10);
        repository.commit(ObjectChange.create(original, "creator"));
        BusinessObject advanced = object("first", "next", 10);
        HistoryEntry action = repository.commit(ObjectChange.action(original, advanced, "actor", "advance"));
        BusinessObject updated = object("first", "next", 20);
        HistoryEntry parameter = repository.commit(ObjectChange.updateParameter(advanced, updated, "editor", "quantity"));

        assertEquals("ACTION", action.operation());
        assertEquals("actor", action.userId());
        assertEquals("advance", action.details().get("actionId").textValue());
        assertEquals("start", action.details().get("oldState").textValue());
        assertEquals("next", action.details().get("newState").textValue());
        assertEquals("UPDATE_PARAMETER", parameter.operation());
        assertEquals("editor", parameter.userId());
        assertEquals("quantity", parameter.details().get("parameterId").textValue());
        assertEquals(10, parameter.details().get("oldValue").intValue());
        assertEquals(20, parameter.details().get("newValue").intValue());
        assertEquals(NOW, action.timestamp());
        assertEquals(NOW, parameter.timestamp());
        assertEquals(List.of(updated), repository.load().objects());
        assertEquals(3, repository.load().history().size());
        assertEquals(repository.load(), new FileRepository(path(), CLOCK).load());
    }

    @Test
    void rejectedStaleAndInvalidChangesDoNotModifyObjectsOrHistory() throws IOException {
        Repository repository = repository();
        repository.loadOrInitialize();
        BusinessObject original = object("first", "start", 10);
        repository.commit(ObjectChange.create(original, "creator"));
        BusinessObject advanced = object("first", "next", 10);
        repository.commit(ObjectChange.action(original, advanced, "actor", "advance"));
        StoreData before = repository.load();
        byte[] bytes = Files.readAllBytes(path());

        assertThrows(ConcurrentModificationException.class,
                () -> repository.commit(ObjectChange.updateParameter(original, object("first", "start", 20), "user", "quantity")));
        assertThrows(IllegalArgumentException.class, () -> repository.commit(ObjectChange.create(advanced, "user")));
        assertThrows(IllegalArgumentException.class,
                () -> repository.commit(ObjectChange.updateParameter(advanced, object("first", "other", 20), "user", "quantity")));
        assertThrows(IllegalArgumentException.class,
                () -> repository.commit(ObjectChange.action(advanced, object("first", "other", 20), "user", "advance")));
        assertEquals(before, repository.load());
        assertArrayEquals(bytes, Files.readAllBytes(path()));
    }

    @Test
    void writeFailureLeavesBothDiskAndPublishedSnapshotUnchangedAndCleansTemporaryFile() throws IOException {
        Repository initial = repository();
        initial.loadOrInitialize();
        BusinessObject original = object("first", "start", 10);
        initial.commit(ObjectChange.create(original, "creator"));
        byte[] before = Files.readAllBytes(path());
        Repository failing = new FileRepository(path(), CLOCK, (source, target) -> {
            assertEquals(target.getParent(), source.getParent());
            assertTrue(Files.size(source) > 0);
            throw new IOException("Simulated atomic write failure");
        });
        StoreData snapshot = failing.load();

        assertThrows(IOException.class,
                () -> failing.commit(ObjectChange.action(original, object("first", "next", 10), "actor", "advance")));

        assertEquals(snapshot, failing.load());
        assertArrayEquals(before, Files.readAllBytes(path()));
        try (var files = Files.list(directory)) {
            assertEquals(List.of("store.json"), files.map(file -> file.getFileName().toString()).toList());
        }
    }

    @Test
    void unavailableAtomicMoveIsReportedWithoutFallback() throws IOException {
        Repository initial = repository();
        initial.loadOrInitialize();
        byte[] before = Files.readAllBytes(path());
        Repository unsupported = new FileRepository(path(), CLOCK, (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "Not supported");
        });

        assertThrows(AtomicMoveNotSupportedException.class,
                () -> unsupported.commit(ObjectChange.create(object("first", "start", 10), "user")));
        assertArrayEquals(before, Files.readAllBytes(path()));
        assertTrue(unsupported.load().history().isEmpty());
    }

    @Test
    void atomicReplacementReceivesObjectAndHistoryInTheSameTemporaryDocument() throws IOException {
        repository().loadOrInitialize();
        Repository repository = new FileRepository(path(), CLOCK, (source, target) -> {
            JsonNode prepared = new ObjectMapper().readTree(source.toFile());
            assertEquals(1, prepared.get("objects").size());
            assertEquals(1, prepared.get("history").size());
            assertEquals(prepared.get("objects").get(0).get("id"), prepared.get("history").get(0).get("objectId"));
            assertTrue(new ObjectMapper().readTree(target.toFile()).get("objects").isEmpty());
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });

        repository.commit(ObjectChange.create(object("first", "start", 10), "user"));
        assertEquals(1, repository.load().objects().size());
        assertEquals(1, repository.load().history().size());
    }

    @Test
    void parallelCommitsForDifferentObjectsDoNotLoseAnyChanges() throws Exception {
        Repository repository = repository();
        repository.loadOrInitialize();
        BusinessObject first = object("first", "start", 10);
        BusinessObject second = object("second", "start", 20);
        repository.commit(ObjectChange.create(first, "creator"));
        repository.commit(ObjectChange.create(second, "creator"));
        Repository anotherInstance = new FileRepository(path(), CLOCK);
        CountDownLatch gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstCommit = CompletableFuture.runAsync(() -> {
                await(gate);
                commit(repository, ObjectChange.updateParameter(first, object("first", "start", 100), "one", "quantity"));
            }, executor);
            var secondCommit = CompletableFuture.runAsync(() -> {
                await(gate);
                commit(anotherInstance, ObjectChange.updateParameter(second, object("second", "start", 200), "two", "quantity"));
            }, executor);
            gate.countDown();
            CompletableFuture.allOf(firstCommit, secondCommit).get(5, TimeUnit.SECONDS);
        }

        StoreData result = new FileRepository(path(), CLOCK).load();
        assertEquals(List.of(object("first", "start", 100), object("second", "start", 200)), result.objects());
        assertEquals(4, result.history().size());
    }

    @Test
    void copiedTemplatesUsersRolesObjectsAndHistorySurviveRestart() throws IOException {
        Repository repository = repository();
        StoreData seed = repository.loadOrInitialize();
        ProcessTemplate template = new ProcessTemplate("standard", "Standard", "standard_order", Set.of(),
                List.of(), List.of(), List.of(), List.of());
        repository.save(new StoreData(1, seed.classes(), List.of(new RoleDefinition("role", "Role")),
                List.of(new UserDefinition("user", "User", Set.of("role"))), List.of(template), List.of(), List.of()));
        repository.commit(ObjectChange.create(object("first", "start", 10), "user"));
        TemplateCatalogService templates = new TemplateCatalogService(new CatalogService(seed.classes()), repository);

        templates.copyTemplate("standard", "office_order", "office");
        StoreData restored = new FileRepository(path(), CLOCK).loadOrInitialize();

        assertEquals(List.of("standard", "office"), restored.templates().stream().map(ProcessTemplate::id).toList());
        assertEquals(List.of(object("first", "start", 10)), restored.objects());
        assertEquals(1, restored.history().size());
        assertEquals(Set.of("role"), restored.users().getFirst().roles());
        assertEquals("role", restored.roles().getFirst().id());
    }

    @Test
    void exactDecimalValuesAndAllNestedDataAreIsolatedFromCallers() throws IOException {
        Repository repository = repository();
        repository.loadOrInitialize();
        Map<String, JsonNode> parameters = new HashMap<>();
        parameters.put("total_amount", DecimalNode.valueOf(new BigDecimal("123.12345678901234567890123456789")));
        BusinessObject original = new BusinessObject("first", "order", "start", parameters, null);
        repository.commit(ObjectChange.create(original, "user"));
        parameters.put("total_amount", IntNode.valueOf(0));
        StoreData loaded = repository.load();
        loaded.objects().clear();
        loaded.history().getFirst().details().withObject("/parameters").put("total_amount", 0);

        StoreData restored = new FileRepository(path(), CLOCK).load();
        assertEquals(new BigDecimal("123.12345678901234567890123456789"),
                restored.objects().getFirst().parameters().get("total_amount").decimalValue());
        assertEquals(new BigDecimal("123.12345678901234567890123456789"),
                repository.load().history().getFirst().details().get("parameters").get("total_amount").decimalValue());
    }

    @Test
    void corruptedExistingStoreIsNeverResetFromSeed() throws IOException {
        Files.writeString(path(), "{broken json");
        byte[] before = Files.readAllBytes(path());

        assertThrows(IOException.class, () -> repository().loadOrInitialize());
        assertArrayEquals(before, Files.readAllBytes(path()));
    }

    @Test
    void failedPreparationDoesNotPublishMutations() throws IOException {
        Repository repository = repository();
        repository.loadOrInitialize();
        repository.commit(ObjectChange.create(object("first", "start", 10), "user"));
        byte[] before = Files.readAllBytes(path());

        assertThrows(IllegalArgumentException.class, () -> repository.update(snapshot -> {
            snapshot.objects().clear();
            throw new IllegalArgumentException("Operation rejected");
        }));

        assertArrayEquals(before, Files.readAllBytes(path()));
        assertEquals(1, repository.load().objects().size());
        assertEquals(1, repository.load().history().size());
    }

    @Test
    void rejectsNestedWritesInsteadOfOverwritingAnInnerCommit() throws IOException {
        Repository repository = repository();
        repository.loadOrInitialize();
        byte[] before = Files.readAllBytes(path());

        assertThrows(IllegalStateException.class, () -> repository.update(snapshot -> {
            try {
                new FileRepository(path(), CLOCK).commit(ObjectChange.create(object("first", "start", 10), "user"));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            return snapshot;
        }));

        assertArrayEquals(before, Files.readAllBytes(path()));
        assertTrue(repository.load().objects().isEmpty());
        assertTrue(repository.load().history().isEmpty());
    }

    private Path path() {
        return directory.resolve("store.json");
    }

    private Repository repository() {
        return new FileRepository(path(), CLOCK);
    }

    private static BusinessObject object(String id, String state, int quantity) {
        return new BusinessObject(id, "standard_order", state, Map.of("quantity", IntNode.valueOf(quantity)), "standard");
    }

    private static void commit(Repository repository, ObjectChange change) {
        try {
            repository.commit(change);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
