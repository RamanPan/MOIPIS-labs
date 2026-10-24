package ru.moipis.workflow.repository.file;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.Repository;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

public final class FileRepository implements Repository {

    private static final ConcurrentMap<Path, ReentrantLock> PATH_LOCKS = new ConcurrentHashMap<>();

    private final Path storePath;
    private final Clock clock;
    private final AtomicFileMover mover;
    private final ReentrantLock lock;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private StoreData current;

    public FileRepository() {
        this(Path.of("data/store.json"), Clock.systemUTC());
    }

    public FileRepository(Path storePath, Clock clock) {
        this(storePath, clock, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    public FileRepository(Path storePath, Clock clock, AtomicFileMover mover) {
        if (storePath == null || clock == null || mover == null) {
            throw new IllegalArgumentException("Store path, clock and atomic mover are required");
        }
        this.storePath = storePath.toAbsolutePath().normalize();
        this.clock = clock;
        this.mover = mover;
        lock = PATH_LOCKS.computeIfAbsent(this.storePath, ignored -> new ReentrantLock());
    }

    @Override
    public StoreData loadOrInitialize() throws IOException {
        lock.lock();
        try {
            if (Files.notExists(storePath, LinkOption.NOFOLLOW_LINKS)) {
                StoreData seed;
                try (var input = FileRepository.class.getResourceAsStream("/seed/initial-data.json")) {
                    if (input == null) {
                        throw new IOException("Seed resource not found: /seed/initial-data.json");
                    }
                    seed = mapper.readValue(input, StoreData.class);
                }
                return persist(seed);
            }
            current = readStore();
            return copy(current, StoreData.class);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public StoreData load() throws IOException {
        lock.lock();
        try {
            current = readStore();
            return copy(current, StoreData.class);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(StoreData data) throws IOException {
        rejectNestedWrite();
        lock.lock();
        try {
            persist(data);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public StoreData update(UnaryOperator<StoreData> change) throws IOException {
        if (change == null) {
            throw new IllegalArgumentException("Snapshot change must not be null");
        }
        rejectNestedWrite();
        lock.lock();
        try {
            StoreData latest = readStore();
            return persist(change.apply(copy(latest, StoreData.class)));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public HistoryEntry commit(ObjectChange change) throws IOException {
        if (change == null) {
            throw new IllegalArgumentException("Object change must not be null");
        }
        ObjectChange prepared = copy(change, ObjectChange.class);
        StoreData committed = update(snapshot -> ObjectChangeApplier.apply(snapshot, prepared, clock));
        return committed.history().getLast();
    }

    private StoreData readStore() throws IOException {
        try (var input = Files.newInputStream(storePath)) {
            StoreData loaded = mapper.readValue(input, StoreData.class);
            validateSnapshot(loaded);
            return loaded;
        }
    }

    private StoreData persist(StoreData data) throws IOException {
        validateSnapshot(data);
        StoreData prepared = copy(data, StoreData.class);
        byte[] json = mapper.writeValueAsBytes(prepared);
        StoreData result = copy(prepared, StoreData.class);
        Files.createDirectories(storePath.getParent());
        Path temporary = Files.createTempFile(storePath.getParent(), "." + storePath.getFileName() + "-", ".tmp");
        try {
            Files.write(temporary, json);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            mover.move(temporary, storePath);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        current = prepared;
        return result;
    }

    private <Value> Value copy(Value value, Class<Value> type) throws IOException {
        return mapper.readValue(mapper.writeValueAsBytes(value), type);
    }

    private void rejectNestedWrite() {
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Nested writes to the same store are not supported: " + storePath);
        }
    }

    private static void validateSnapshot(StoreData data) throws IOException {
        if (data == null || data.schemaVersion() != 1 || data.classes() == null || data.roles() == null
                || data.users() == null || data.templates() == null || data.objects() == null || data.history() == null) {
            throw new IOException("Invalid store document or unsupported schemaVersion; expected schemaVersion=1 and all collections");
        }
    }
}
