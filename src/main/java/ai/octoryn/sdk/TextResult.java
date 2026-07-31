package ai.octoryn.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record TextResult(
    String text,
    List<ToolCall> toolCalls,
    String finishReason,
    Usage usage,
    GovernanceMetadata octoryn,
    JsonNode response
) {}
