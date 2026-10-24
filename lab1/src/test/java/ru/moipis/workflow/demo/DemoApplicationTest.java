package ru.moipis.workflow.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.file.FileRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DemoApplicationTest {
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path directory;

    @Test
    void runsTheRealProcessWithExpectedRejectionsBranchesAndHistory() throws Exception {
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        DemoApplication.Result result;
        try (PrintStream output = new PrintStream(transcript, true, StandardCharsets.UTF_8)) {
            result = DemoApplication.run(directory, output, CLOCK);
        }
        var store = new FileRepository(result.storePath(), CLOCK).load();
        var office = store.objects().stream().filter(object -> object.id().equals(result.officeId())).findFirst().orElseThrow();
        var standard = store.objects().stream().filter(object -> object.id().equals(result.standardId())).findFirst().orElseThrow();
        assertEquals("office_order", office.classId());
        assertEquals("standard_order", standard.classId());
        assertEquals("completed", office.stateId());
        assertEquals("completed", standard.stateId());
        assertEquals(100, office.parameters().get("quantity").intValue());
        assertEquals(40, office.parameters().get("prepayment_percent").intValue());
        assertEquals(20, standard.parameters().get("prepayment_percent").intValue());
        assertEquals(List.of(false, true), result.officeBranches());
        assertEquals(List.of(true, false), result.standardBranches());
        assertEquals("configured", result.dynamicState());
        assertEquals(19, store.templates().getFirst().transitions().size());
        assertEquals(7, store.objects().size());
        List<HistoryEntry> history = store.history().stream().filter(entry -> entry.objectId().equals(office.id())).toList();
        assertEquals(13, history.size());
        assertEquals(8, store.history().stream().filter(entry -> entry.objectId().equals(standard.id())).count());
        assertTrue(history.stream().allMatch(entry -> entry.timestamp().equals(NOW)));
        assertEquals(List.of("CREATE", "UPDATE_PARAMETER", "ACTION", "UPDATE_PARAMETER", "ACTION", "ACTION", "ACTION",
                "ACTION", "ACTION", "ACTION", "ACTION", "ACTION", "ACTION"), history.stream().map(HistoryEntry::operation).toList());
        assertEquals(700, history.get(1).details().get("newValue").intValue());
        assertEquals(100, history.get(3).details().get("newValue").intValue());
        assertEquals("user_logistics", history.get(6).userId());
        assertEquals("correction", history.get(6).details().get("actionId").asText());
        assertEquals("delivery_approval", history.get(6).details().get("oldState").asText());
        assertEquals("composition_approval", history.get(6).details().get("newState").asText());
        String text = transcript.toString(StandardCharsets.UTF_8);
        assertEquals(4, text.lines().filter(line -> line.startsWith("EXPECTED REJECTION")).count());
        assertTrue(text.contains("CONDITION_NOT_MET"));
        assertTrue(text.contains("ACCESS_DENIED"));
        assertTrue(text.contains("FINAL_STATE"));
        assertTrue(text.contains("HISTORY office_order"));
        assertTrue(text.contains("DYNAMIC idle -> configured"));
    }

    @Test
    void repeatedRunsUseNewDirectoriesAndNeverOverwriteAnExistingStore() throws Exception {
        Path protectedStore = directory.resolve("store.json");
        byte[] sentinel = "User data must not be overwritten".getBytes(StandardCharsets.UTF_8);
        Files.write(protectedStore, sentinel);
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            var first = DemoApplication.run(directory, output, CLOCK);
            byte[] firstSnapshot = Files.readAllBytes(first.storePath());
            var second = DemoApplication.run(directory, output, CLOCK);
            assertNotEquals(first.storePath(), second.storePath());
            assertEquals(directory.toAbsolutePath(), first.storePath().getParent().getParent());
            assertEquals(directory.toAbsolutePath(), second.storePath().getParent().getParent());
            assertArrayEquals(sentinel, Files.readAllBytes(protectedStore));
            assertArrayEquals(firstSnapshot, Files.readAllBytes(first.storePath()));
        }
    }

    @Test
    void mainWritesUtf8AndKeepsDefaultFilesInsideItsOwnWorkingDirectory() throws Exception {
        Path protectedStore = directory.resolve("data/store.json");
        Files.createDirectories(protectedStore.getParent());
        byte[] sentinel = "Protected user store".getBytes(StandardCharsets.UTF_8);
        Files.write(protectedStore, sentinel);
        Path transcript = directory.resolve("main-output.log");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-Dfile.encoding=windows-1251", "-Dstdout.encoding=windows-1251",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DemoApplication.class.getName()).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(transcript.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Demo main did not terminate");
            assertEquals(0, process.exitValue());
            String output = Files.readString(transcript, StandardCharsets.UTF_8);
            assertTrue(output.contains("Инициализация отдельного хранилища"));
            assertTrue(output.contains("DYNAMIC idle -> configured"));
            assertTrue(output.contains("DEMO OK"));
            assertArrayEquals(sentinel, Files.readAllBytes(protectedStore));
            try (var files = Files.walk(directory.resolve("demo"))) {
                assertEquals(1, files.filter(file -> file.getFileName().toString().equals("store.json")).count());
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void refusesAnExplicitStoreArgumentRatherThanRiskingUserData() {
        assertThrows(IllegalArgumentException.class, () -> DemoApplication.main(new String[]{"data/store.json"}));
    }
}
