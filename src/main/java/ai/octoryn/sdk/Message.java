package ai.octoryn.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
    String role,
    Object content,
    String name,
    @JsonProperty("tool_call_id") String toolCallId
) {
    public Message(String role, Object content) {
        this(role, content, null, null);
    }
}
