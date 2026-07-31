package ai.octoryn.sdk;

import com.fasterxml.jackson.databind.JsonNode;

public record GenerateObjectRequest<T>(
    GenerateTextRequest text,
    JsonNode schema,
    String schemaName,
    String schemaDescription,
    Class<T> target
) {
    public GenerateObjectRequest(
        GenerateTextRequest text,
        JsonNode schema,
        Class<T> target
    ) {
        this(text, schema, "response", null, target);
    }
}
