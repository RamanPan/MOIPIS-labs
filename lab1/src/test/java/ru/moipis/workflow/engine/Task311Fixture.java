package ru.moipis.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.util.function.Consumer;

final class Task311Fixture {
    private Task311Fixture() {}

    static CoordinationScenario change(Consumer<ObjectNode> change) throws Exception {
        var mapper = new ObjectMapper();
        try (var input = Task311Fixture.class.getResourceAsStream("/seed/task-3-1-1.json")) {
            var config = (ObjectNode) mapper.readTree(input);
            change.accept(config);
            return CoordinationScenario.read(new ByteArrayInputStream(mapper.writeValueAsBytes(config)));
        }
    }

    static ObjectNode values(ObjectNode config) {
        return (ObjectNode) config.path("objects").get(0).path("values");
    }
}
