package ru.moipis.workflow.repository.file;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface AtomicFileMover {

    void move(Path source, Path target) throws IOException;
}
