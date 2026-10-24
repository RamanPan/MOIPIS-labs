package ru.moipis.workflow.repository;

import ru.moipis.workflow.engine.model.HistoryEntry;
import ru.moipis.workflow.repository.model.ObjectChange;
import ru.moipis.workflow.repository.model.StoreData;

import java.io.IOException;
import java.util.function.UnaryOperator;

public interface Repository {

    StoreData loadOrInitialize() throws IOException;

    StoreData load() throws IOException;

    void save(StoreData data) throws IOException;

    StoreData update(UnaryOperator<StoreData> change) throws IOException;

    HistoryEntry commit(ObjectChange change) throws IOException;
}
