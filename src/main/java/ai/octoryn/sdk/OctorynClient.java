package ai.octoryn.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import com.fasterxml.jackson.core.JsonProcessingException;

public final class OctorynClient {
    private final String apiKey;
    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public OctorynClient(String apiKey) {
        this(apiKey, URI.create("https://api.octoryn.dev/v1/"), HttpClient.newHttpClient());
    }

    public OctorynClient(String apiKey, URI baseUri, HttpClient http) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Octoryn API key is required.");
        }
        this.apiKey = apiKey;
        this.baseUri = baseUri.toString().endsWith("/")
            ? baseUri
            : URI.create(baseUri + "/");
        this.http = http;
        this.mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public ObjectMapper objectMapper() { return mapper; }

    public TextResult generateText(GenerateTextRequest options)
        throws IOException, InterruptedException {
        var response = http.send(
            request(buildRequest(options, false), "application/json"),
            HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);
        return normalize(mapper.readTree(response.body()), governance(response));
    }

    public CompletableFuture<TextResult> generateTextAsync(GenerateTextRequest options) {
        return http.sendAsync(
            request(buildRequest(options, false), "application/json"),
            HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            ensureSuccess(response);
            try {
                return normalize(mapper.readTree(response.body()), governance(response));
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        });
    }

    public <T> ObjectResult<T> generateObject(GenerateObjectRequest<T> options)
        throws IOException, InterruptedException {
        var payload = buildRequest(options.text(), false);
        var format = mapper.createObjectNode();
        format.put("type", "json_schema");
        var schema = format.putObject("json_schema");
        schema.put("name", options.schemaName() == null ? "response" : options.schemaName());
        if (options.schemaDescription() != null) {
            schema.put("description", options.schemaDescription());
        }
        schema.put("strict", true);
        schema.set("schema", options.schema().deepCopy());
        payload.set("response_format", format);
        var response = http.send(
            request(payload, "application/json"),
            HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);
        var result = normalize(mapper.readTree(response.body()), governance(response));
        T object;
        try {
            object = mapper.readValue(result.text(), options.target());
        } catch (JsonProcessingException validation) {
            throw new StructuredOutputException(result.text(), validation);
        }
        return new ObjectResult<>(object, result);
    }

    public TextStream streamText(GenerateTextRequest options)
        throws IOException, InterruptedException {
        var response = http.send(
            request(buildRequest(options, true), "text/event-stream"),
            HttpResponse.BodyHandlers.ofInputStream());
        ensureStreamSuccess(response);
        return new TextStream(response.body(), mapper, governance(response));
    }

    private HttpRequest request(ObjectNode payload, String accept) {
        try {
            return HttpRequest.newBuilder(baseUri.resolve("chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("User-Agent", "octoryn-java/0.1.1")
                .header("X-Octoryn-Sdk", "java/0.1.1")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to encode Octoryn request.", error);
        }
    }

    private ObjectNode buildRequest(GenerateTextRequest options, boolean stream) {
        if (options == null || options.model() == null || options.model().isBlank()) {
            throw new IllegalArgumentException("Model is required.");
        }
        if ((options.prompt() == null) == (options.messages() == null)) {
            throw new IllegalArgumentException("Pass exactly one of prompt or messages.");
        }
        var payload = mapper.createObjectNode();
        payload.put("model", options.model());
        payload.put("stream", stream);
        var messages = payload.putArray("messages");
        if (options.system() != null) messages.add(mapper.valueToTree(
            new Message("system", options.system())));
        if (options.prompt() != null) {
            messages.add(mapper.valueToTree(new Message("user", options.prompt())));
        } else {
            options.messages().forEach(message -> messages.add(mapper.valueToTree(message)));
        }
        if (options.tools() != null) payload.set("tools", mapper.valueToTree(options.tools()));
        if (options.toolChoice() != null) {
            payload.set("tool_choice", mapper.valueToTree(options.toolChoice()));
        }
        if (options.temperature() != null) payload.put("temperature", options.temperature());
        if (options.topP() != null) payload.put("top_p", options.topP());
        if (options.maxOutputTokens() != null) {
            payload.put("max_tokens", options.maxOutputTokens());
        }
        if (options.metadata() != null) {
            payload.set("metadata", mapper.valueToTree(options.metadata()));
        }
        return payload;
    }

    private TextResult normalize(JsonNode root, GovernanceMetadata governance)
        throws IOException {
        var choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("Octoryn response contained no choices.");
        }
        var choice = choices.get(0);
        var message = choice.path("message");
        var content = message.path("content");
        String text = content.isTextual() ? content.asText() : textParts(content);
        var calls = new ArrayList<ToolCall>();
        for (var call : message.path("tool_calls")) {
            var function = call.path("function");
            calls.add(new ToolCall(
                call.path("id").asText(),
                function.path("name").asText(),
                function.path("arguments").asText("{}"),
                call.path("type").asText("function")));
        }
        Usage usage = root.hasNonNull("usage")
            ? mapper.treeToValue(root.get("usage"), Usage.class)
            : null;
        return new TextResult(
            text,
            List.copyOf(calls),
            choice.path("finish_reason").isTextual()
                ? choice.get("finish_reason").asText()
                : null,
            usage,
            governance,
            root);
    }

    private String textParts(JsonNode content) {
        if (!content.isArray()) return "";
        var text = new StringBuilder();
        for (var part : content) {
            if ("text".equals(part.path("type").asText()) && part.hasNonNull("text")) {
                text.append(part.get("text").asText());
            }
        }
        return text.toString();
    }

    private GovernanceMetadata governance(HttpResponse<?> response) {
        Function<String, String> header = name ->
            response.headers().firstValue(name).orElse(null);
        var costValue = header.apply("X-Octoryn-Estimated-Cost");
        BigDecimal cost = costValue == null ? null : new BigDecimal(costValue);
        return new GovernanceMetadata(
            header.apply("X-Octoryn-Run-Id"),
            header.apply("X-Octoryn-Upstream"),
            header.apply("X-Octoryn-Byok"),
            header.apply("X-Octoryn-Region"),
            header.apply("X-Octoryn-Route"),
            header.apply("X-Octoryn-Policy-Decision"),
            header.apply("X-Octoryn-Evidence-Hash"),
            cost);
    }

    private void ensureSuccess(HttpResponse<?> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) return;
        JsonNode error = mapper.createObjectNode();
        if (response.body() instanceof String body) {
            try {
                error = mapper.readTree(body).path("error");
            } catch (IOException ignored) {
            }
        }
        var retry = response.headers().firstValue("Retry-After")
            .map(value -> Duration.ofSeconds(Long.parseLong(value)))
            .orElse(null);
        throw new OctorynApiException(
            response.statusCode(),
            error.path("message").asText("Octoryn request failed."),
            error.path("code").isTextual() ? error.get("code").asText() : null,
            error.path("type").isTextual() ? error.get("type").asText() : null,
            response.headers().firstValue("X-Request-Id").orElse(null),
            retry);
    }

    private void ensureStreamSuccess(HttpResponse<InputStream> response)
        throws IOException {
        if (response.statusCode() >= 200 && response.statusCode() < 300) return;
        String body;
        try (var input = response.body()) {
            body = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        JsonNode error = mapper.createObjectNode();
        try {
            error = mapper.readTree(body).path("error");
        } catch (IOException ignored) {
        }
        var retry = response.headers().firstValue("Retry-After")
            .map(value -> Duration.ofSeconds(Long.parseLong(value)))
            .orElse(null);
        throw new OctorynApiException(
            response.statusCode(),
            error.path("message").asText("Octoryn request failed."),
            error.path("code").isTextual() ? error.get("code").asText() : null,
            error.path("type").isTextual() ? error.get("type").asText() : null,
            response.headers().firstValue("X-Request-Id").orElse(null),
            retry);
    }
}
