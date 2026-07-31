package ai.octoryn.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public record ToolCall(String id, String name, String arguments, String type) {
    public <T> T decodeInput(Class<T> target, ObjectMapper mapper)
        throws JsonProcessingException {
        return mapper.readValue(arguments, target);
    }
}
