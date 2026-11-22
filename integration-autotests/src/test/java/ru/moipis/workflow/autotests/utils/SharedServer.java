package ru.moipis.workflow.autotests.utils;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Поднимает единственный {@link WorkflowServerProcess} на весь прогон тестов вместо отдельного процесса
 * на каждый тест. Тест-классы, которым не нужен свой собственный seed/конфигурация (не используют
 * {@link Fixtures}), подключают его через {@code @ExtendWith(SharedServer.class)} и берут процесс через
 * {@link #get()}. Процесс стартует лениво при первом обращении и останавливается ровно один раз — когда
 * JUnit закрывает корневой {@link ExtensionContext} после завершения всего прогона, а не после класса.
 *
 * <p>Тесты, которым нужен собственный {@code store.json} (запуск/перезапуск, битый файл, занятый порт,
 * нестандартный шаблон процесса), не используют этот класс и продолжают поднимать процесс сами.
 */
public final class SharedServer implements BeforeAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(SharedServer.class);
    private static volatile WorkflowServerProcess instance;

    public static WorkflowServerProcess get() {
        if (instance == null) {
            throw new IllegalStateException(
                    "SharedServer is not started yet; annotate the test class with @ExtendWith(SharedServer.class)");
        }
        return instance;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (instance != null) return;
        synchronized (SharedServer.class) {
            if (instance != null) return;
            Path directory = Files.createTempDirectory("workflow-shared-server");
            WorkflowServerProcess started = WorkflowServerProcess.start(directory.resolve("store.json"));
            // Регистрируется в сторе КОРНЕВОГО контекста, а не текущего класса, поэтому close() вызовется
            // один раз в самом конце всего прогона, а не после первого же тест-класса.
            context.getRoot().getStore(NAMESPACE).put("process", new ShutdownHook(started));
            instance = started;
        }
    }

    private record ShutdownHook(
            WorkflowServerProcess process) implements ExtensionContext.Store.CloseableResource, AutoCloseable {
        @Override
        public void close() {
            process.close();
        }
    }
}
