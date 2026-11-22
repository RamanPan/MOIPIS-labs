package ru.moipis.workflow.autotests.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Копирует подготовленный ресурс {@code fixtures/*.json} в новый файл, которым будет владеть процесс сервера.
 */
public final class Fixtures {
    private Fixtures() {
    }

    public static Path copy(String resourceName, Path target) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + resourceName)) {
            if (in == null) throw new IllegalArgumentException("No such fixture: " + resourceName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
