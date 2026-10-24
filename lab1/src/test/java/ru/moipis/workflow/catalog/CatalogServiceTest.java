package ru.moipis.workflow.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import ru.moipis.workflow.catalog.model.ObjectClassDefinition;
import ru.moipis.workflow.catalog.model.ParameterDefinition;
import ru.moipis.workflow.repository.model.StoreData;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogServiceTest {

    private List<ObjectClassDefinition> definitions;
    private CatalogService catalog;

    @BeforeEach
    void loadCatalog() throws IOException {
        try (var input = Objects.requireNonNull(
                getClass().getResourceAsStream("/seed/initial-data.json"))) {
            definitions = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(input, StoreData.class).classes();
        }
        catalog = new CatalogService(definitions);
    }

    @Test
    void returnsClassWithOnlyItsOwnParameters() {
        ObjectClassDefinition definition = catalog.getClass("office_order");

        assertEquals("standard_order", definition.parentId());
        assertEquals(List.of("quantity", "office_location"), definition.parameters().stream()
                .map(ParameterDefinition::id).toList());
    }

    @Test
    void inheritsParametersAcrossMultipleLevelsAndAddsOwnParameters() {
        Map<String, ParameterDefinition> parameters = catalog.getEffectiveParameters("office_order");

        assertEquals(List.of("order_number", "product_name", "quantity", "total_amount",
                "supplier_name", "delivery_days", "prepayment_percent", "block_reason",
                "office_location"), new ArrayList<>(parameters.keySet()));
        assertEquals("string", parameters.get("office_location").type());
        assertEquals(new BigDecimal("2000000"), parameters.get("total_amount").max());
        assertEquals(new BigDecimal("60"), parameters.get("delivery_days").max());
        assertEquals(BigDecimal.ZERO, parameters.get("prepayment_percent").min());
        assertEquals(new BigDecimal("100"), parameters.get("prepayment_percent").max());
    }

    @Test
    void overridesRangeWithoutChangingParentOrSibling() {
        ParameterDefinition officeQuantity = catalog.getEffectiveParameters("office_order").get("quantity");

        assertEquals("integer", officeQuantity.type());
        assertEquals(BigDecimal.ONE, officeQuantity.min());
        assertEquals(new BigDecimal("500"), officeQuantity.max());
        assertEquals(new BigDecimal("10000"),
                catalog.getEffectiveParameters("order").get("quantity").max());
        assertEquals(new BigDecimal("10000"),
                catalog.getEffectiveParameters("consumables_order").get("quantity").max());
        assertTrue(catalog.getEffectiveParameters("consumables_order").containsKey("storage_conditions"));
        assertFalse(catalog.getEffectiveParameters("consumables_order").containsKey("office_location"));
    }

    @Test
    void replacesEntireDefinitionIncludingTitleAndAbsentBounds() {
        CatalogService service = new CatalogService(List.of(
                definition("root", null, parameter("amount", "decimal", "1", "100")),
                new ObjectClassDefinition("child", "Child", "root", List.of(
                        new ParameterDefinition("amount", "New title", "decimal", null, null)))));

        ParameterDefinition effective = service.getEffectiveParameters("child").get("amount");

        assertEquals("New title", effective.title());
        assertNull(effective.min());
        assertNull(effective.max());
    }

    @Test
    void resolvesHierarchyRegardlessOfDefinitionOrder() {
        CatalogService service = new CatalogService(definitions.reversed());

        assertEquals(9, service.getEffectiveParameters("office_order").size());
        assertEquals(new BigDecimal("500"),
                service.getEffectiveParameters("office_order").get("quantity").max());
    }

    @Test
    void returnsAncestorsFromParentToRootWithoutTheClassItself() {
        assertEquals(List.of("standard_order", "order"), catalog.getAncestors("office_order")
                .stream().map(ObjectClassDefinition::id).toList());
        assertEquals(List.of("order"), catalog.getAncestors("standard_order")
                .stream().map(ObjectClassDefinition::id).toList());
        assertTrue(catalog.getAncestors("order").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "office_order, office_order, true",
            "office_order, standard_order, true",
            "office_order, order, true",
            "consumables_order, standard_order, true",
            "order, office_order, false",
            "office_order, consumables_order, false",
            "standard_order, office_order, false"
    })
    void checksClassMembershipInTheCorrectDirection(String classId, String expectedClassId, boolean expected) {
        assertEquals(expected, catalog.isInstanceOf(classId, expectedClassId));
    }

    @Test
    void rejectsUnknownClassInAllQueries() {
        assertThrows(IllegalArgumentException.class, () -> catalog.getClass("missing"));
        assertThrows(IllegalArgumentException.class, () -> catalog.getEffectiveParameters("missing"));
        assertThrows(IllegalArgumentException.class, () -> catalog.getAncestors("missing"));
        assertThrows(IllegalArgumentException.class, () -> catalog.isInstanceOf("missing", "order"));
        assertThrows(IllegalArgumentException.class, () -> catalog.isInstanceOf("order", "missing"));
        assertThrows(IllegalArgumentException.class, () -> catalog.isInstanceOf("missing", "missing"));
    }

    @Test
    void rejectsUnknownParentAtConstruction() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new CatalogService(List.of(definition("child", "missing"))));

        assertTrue(exception.getMessage().contains("child"));
        assertTrue(exception.getMessage().contains("missing"));
    }

    @ParameterizedTest
    @MethodSource("cyclicHierarchies")
    void rejectsCyclesEvenInAnUnrelatedBranch(List<ObjectClassDefinition> classes) {
        assertThrows(IllegalArgumentException.class, () -> new CatalogService(classes));
    }

    static Stream<Arguments> cyclicHierarchies() {
        return Stream.of(
                Arguments.of(List.of(definition("self", "self"))),
                Arguments.of(List.of(definition("first", "second"), definition("second", "first"))),
                Arguments.of(List.of(definition("root", null), definition("child", "first"),
                        definition("first", "second"), definition("second", "third"),
                        definition("third", "first"))));
    }

    @ParameterizedTest
    @CsvSource({"integer, decimal", "decimal, integer", "string, integer", "integer, string"})
    void rejectsTypeChangesAcrossAnIntermediateClass(String originalType, String overriddenType) {
        assertThrows(IllegalArgumentException.class, () -> new CatalogService(List.of(
                definition("root", null, parameter("value", originalType, null, null)),
                definition("middle", "root"),
                definition("child", "middle", parameter("value", overriddenType, null, null)))));
    }

    @Test
    void rejectsDuplicateClassIds() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogService(List.of(
                definition("same", null), definition("same", null, parameter("value", "string", null, null)))));
    }

    @Test
    void rejectsDuplicateParametersWithinOneClass() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogService(List.of(
                definition("root", null, parameter("same", "integer", null, null),
                        parameter("same", "integer", "1", "5")))));
    }

    @Test
    void allowsTheSameParameterIdWithDifferentTypesInUnrelatedClasses() {
        CatalogService service = new CatalogService(List.of(
                definition("first", null, parameter("value", "integer", null, null)),
                definition("second", null, parameter("value", "string", null, null))));

        assertEquals("integer", service.getEffectiveParameters("first").get("value").type());
        assertEquals("string", service.getEffectiveParameters("second").get("value").type());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "integer, NULL, NULL", "integer, 1, NULL", "integer, NULL, 10", "integer, 1, 1",
            "decimal, NULL, NULL", "decimal, 0.5, NULL", "decimal, NULL, 10.5",
            "decimal, 0.5, 10.5", "string, NULL, NULL"
    }, nullValues = "NULL")
    void supportsOptionalNumericBounds(String type, String min, String max) {
        CatalogService service = new CatalogService(List.of(
                definition("root", null, parameter("value", type, min, max))));

        ParameterDefinition effective = service.getEffectiveParameters("root").get("value");

        assertEquals(type, effective.type());
        assertEquals(decimal(min), effective.min());
        assertEquals(decimal(max), effective.max());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "boolean, NULL, NULL", "integer, 10, 1", "decimal, 10.5, 0.5",
            "string, 1, NULL", "string, NULL, 10", "integer, 0.5, 10", "integer, 1, 10.5"
    }, nullValues = "NULL")
    void rejectsUnsupportedTypesAndInvalidBounds(String type, String min, String max) {
        assertThrows(IllegalArgumentException.class, () -> new CatalogService(List.of(
                definition("root", null, parameter("value", type, min, max)))));
    }

    @Test
    void acceptsAnEmptyCatalogAndRejectsLookup() {
        CatalogService service = new CatalogService(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.getClass("missing"));
    }

    @Test
    void isolatesCatalogFromChangesToInputCollections() {
        List<ParameterDefinition> parameters = new ArrayList<>(List.of(parameter("value", "integer", null, null)));
        List<ObjectClassDefinition> classes = new ArrayList<>(List.of(
                new ObjectClassDefinition("root", "Root", null, parameters)));
        CatalogService service = new CatalogService(classes);

        parameters.clear();
        classes.clear();

        assertEquals(List.of("value"), new ArrayList<>(service.getEffectiveParameters("root").keySet()));
    }

    @Test
    void doesNotAllowReturnedCollectionsToCorruptTheHierarchy() {
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.getClass("order").parameters().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.getEffectiveParameters("office_order").clear());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.getAncestors("office_order").clear());
        assertEquals(9, catalog.getEffectiveParameters("office_order").size());
    }

    private static ObjectClassDefinition definition(String id, String parentId, ParameterDefinition... parameters) {
        return new ObjectClassDefinition(id, id, parentId, List.of(parameters));
    }

    private static ParameterDefinition parameter(String id, String type, String min, String max) {
        return new ParameterDefinition(id, id, type, decimal(min), decimal(max));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
