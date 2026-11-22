package ru.moipis.workflow.demo;

import ru.moipis.workflow.engine.CoordinationJournal.Entry;
import ru.moipis.workflow.engine.CoordinationScenario;
import ru.moipis.workflow.engine.CoordinationSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CoordinationDemoApplication {
    private CoordinationDemoApplication() {}

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: [configuration.json]");
        }
        CoordinationScenario scenario;
        if (args.length == 0) {
            scenario = CoordinationScenario.demo();
        } else {
            try (var input = Files.newInputStream(Path.of(args[0]))) {
                scenario = CoordinationScenario.read(input);
            }
        }
        var folder = Files.createDirectories(Path.of("demo", "coordination"));
        Path log = Files.createTempDirectory(folder, "run-").resolve("journal.jsonl");
        var session = scenario.open(log);
        System.out.println("Journal: " + log.toAbsolutePath());
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] command = line.strip().split("\\s+");
            CoordinationSession.Result result;
            if (command.length == 3 && command[0].equals("start")) {
                result = session.start(command[1], command[2]);
            } else if (command.length == 4 && command[0].equals("action")) {
                result = session.action(command[1], command[2], command[3]);
            } else {
                System.out.println("Expected start <userId> <objectId> or action <userId> <objectId> <actionId>");
                continue;
            }
            print(result.command());
            result.deliveries().forEach(CoordinationDemoApplication::print);
        }
    }

    private static void print(Entry entry) {
        String operation = switch (entry.kind()) {
            case START -> entry.userId();
            case ACTION -> entry.userId() + " " + entry.actionId();
            case EVENT -> entry.eventName() + " source=" + entry.sourceId() + " parentId=" + entry.parentId();
        };
        String reason = entry.reason() == null ? "" : " reason=" + entry.reason();
        System.out.printf("%s %s %s | %s -> %s | %s%s | id=%s%n",
                entry.kind(), entry.objectId(), operation, entry.before(), entry.after(),
                entry.outcome(), reason, entry.id());
    }
}
