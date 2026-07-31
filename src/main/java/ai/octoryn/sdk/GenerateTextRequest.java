package ai.octoryn.sdk;

import java.util.List;
import java.util.Map;

public final class GenerateTextRequest {
    private final String model;
    private final String prompt;
    private final List<Message> messages;
    private final String system;
    private final List<ToolDefinition> tools;
    private final Object toolChoice;
    private final Double temperature;
    private final Double topP;
    private final Integer maxOutputTokens;
    private final Map<String, String> metadata;

    private GenerateTextRequest(Builder builder) {
        model = builder.model;
        prompt = builder.prompt;
        messages = builder.messages;
        system = builder.system;
        tools = builder.tools;
        toolChoice = builder.toolChoice;
        temperature = builder.temperature;
        topP = builder.topP;
        maxOutputTokens = builder.maxOutputTokens;
        metadata = builder.metadata;
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    public String model() { return model; }
    public String prompt() { return prompt; }
    public List<Message> messages() { return messages; }
    public String system() { return system; }
    public List<ToolDefinition> tools() { return tools; }
    public Object toolChoice() { return toolChoice; }
    public Double temperature() { return temperature; }
    public Double topP() { return topP; }
    public Integer maxOutputTokens() { return maxOutputTokens; }
    public Map<String, String> metadata() { return metadata; }

    public static final class Builder {
        private final String model;
        private String prompt;
        private List<Message> messages;
        private String system;
        private List<ToolDefinition> tools;
        private Object toolChoice;
        private Double temperature;
        private Double topP;
        private Integer maxOutputTokens;
        private Map<String, String> metadata;

        private Builder(String model) { this.model = model; }
        public Builder prompt(String value) { prompt = value; return this; }
        public Builder messages(List<Message> value) { messages = value; return this; }
        public Builder system(String value) { system = value; return this; }
        public Builder tools(List<ToolDefinition> value) { tools = value; return this; }
        public Builder toolChoice(Object value) { toolChoice = value; return this; }
        public Builder temperature(double value) { temperature = value; return this; }
        public Builder topP(double value) { topP = value; return this; }
        public Builder maxOutputTokens(int value) { maxOutputTokens = value; return this; }
        public Builder metadata(Map<String, String> value) { metadata = value; return this; }
        public GenerateTextRequest build() { return new GenerateTextRequest(this); }
    }
}
