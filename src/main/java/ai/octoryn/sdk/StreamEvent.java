package ai.octoryn.sdk;

import com.fasterxml.jackson.databind.JsonNode;

public record StreamEvent(
    String type,
    String text,
    ToolCall toolCall,
    Usage usage,
    String finishReason,
    GovernanceMetadata octoryn,
    JsonNode providerEvent,
    Throwable error
) {
    public static StreamEvent start(GovernanceMetadata metadata) {
        return new StreamEvent("start", null, null, null, null, metadata, null, null);
    }
}
