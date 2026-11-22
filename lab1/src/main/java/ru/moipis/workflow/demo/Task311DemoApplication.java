package ru.moipis.workflow.demo;

import ru.moipis.workflow.engine.CoordinationScenario;
import ru.moipis.workflow.engine.Task311Commands;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Task311DemoApplication {
    private Task311DemoApplication() {}

    public static void main(String[] args) throws IOException {
        var scenario = loadScenario(args);
        var path = Task311Commands.newJournalPath();
        var commands = scenario.openTask311(path);
        System.out.println("Journal: " + path.toAbsolutePath());
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            var entry = commands.submit(line);
            System.out.printf("%s | %s -> %s | %s | %s%n",
                    entry.outcome(), entry.before(), entry.after(), entry.reason(), entry.id());
        }
    }

    private static CoordinationScenario loadScenario(String[] args) throws IOException {
        if (args.length == 0) {
            return CoordinationScenario.task311();
        }
        if (args.length != 2 || !args[0].equals("--config")) {
            throw new IllegalArgumentException("Usage: Task311DemoApplication [--config path]");
        }
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            return CoordinationScenario.read(input);
        }
    }
}
