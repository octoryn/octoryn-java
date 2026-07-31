package ai.octoryn.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

public record ToolDefinition(String type, Function function) {
    public ToolDefinition(String name, String description, JsonNode schema) {
        this("function", new Function(name, description, schema));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Function(String name, String description, JsonNode parameters) {}
}
