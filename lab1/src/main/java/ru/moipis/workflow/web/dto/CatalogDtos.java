package ru.moipis.workflow.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class CatalogDtos {
    private CatalogDtos() { }

    public record UserDto(String id, String name, List<String> roles, Map<String, String> roleTitles) { }
    public record ClassDto(String id, String title, String parentId) { }
    public record ParameterDto(String id, String title, String type, BigDecimal min, BigDecimal max) { }
    public record ActionDto(String id, String title) { }
}
