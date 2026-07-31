package ai.octoryn.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

public final class TextStream implements AutoCloseable {
    private final InputStream body;
    private final ObjectMapper mapper;
    private final GovernanceMetadata governance;
    private final ReplayPublisher<StreamEvent> events = new ReplayPublisher<>();
    private final CompletableFuture<TextResult> result = new CompletableFuture<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "octoryn-java-stream");
        thread.setDaemon(true);
        return thread;
    });

    TextStream(InputStream body, ObjectMapper mapper, GovernanceMetadata governance) {
        this.body = body;
        this.mapper = mapper;
        this.governance = governance;
        events.publish(StreamEvent.start(governance));
        executor.submit(this::consume);
    }

    public Flow.Publisher<StreamEvent> events() { return events; }
    public CompletableFuture<TextResult> result() { return result; }

    private void consume() {
        var text = new StringBuilder();
        var tools = new TreeMap<Integer, ToolAccumulator>();
        Usage usage = null;
        String finishReason = null;
        try (var reader = new BufferedReader(
            new InputStreamReader(body, StandardCharsets.UTF_8))) {
            var data = new ArrayList<String>();
            String line;
            boolean done = false;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    if (line.startsWith("data:")) data.add(line.substring(5).trim());
                    continue;
                }
                if (data.isEmpty()) continue;
                var payload = String.join("\n", data);
                data.clear();
                if ("[DONE]".equals(payload)) {
                    done = true;
                    break;
                }
                var state = apply(payload, text, tools);
                if (state.usage() != null) usage = state.usage();
                if (state.finishReason() != null) finishReason = state.finishReason();
            }
            if (!done && data.size() == 1 && "[DONE]".equals(data.get(0))) done = true;
            if (!done) throw new IOException("Octoryn stream ended before [DONE].");
            var calls = new ArrayList<ToolCall>();
            for (var accumulator : tools.values()) {
                var call = accumulator.build();
                calls.add(call);
                events.publish(new StreamEvent(
                    "tool-call", null, call, null, null, null, null, null));
            }
            events.publish(new StreamEvent(
                "finish", null, null, null, finishReason, governance, null, null));
            result.complete(new TextResult(
                text.toString(),
                List.copyOf(calls),
                finishReason,
                usage,
                governance,
                null));
            events.complete();
        } catch (Throwable failure) {
            events.publish(new StreamEvent(
                "error", null, null, null, null, governance, null, failure));
            result.completeExceptionally(failure);
            events.fail(failure);
        } finally {
            executor.shutdown();
        }
    }

    private ChunkState apply(
        String payload,
        StringBuilder text,
        Map<Integer, ToolAccumulator> tools
    ) throws IOException {
        var root = mapper.readTree(payload);
        boolean recognized = false;
        Usage usage = null;
        String finishReason = null;
        if (root.hasNonNull("usage")) {
            usage = mapper.treeToValue(root.get("usage"), Usage.class);
            events.publish(new StreamEvent(
                "usage", null, null, usage, null, null, null, null));
            recognized = true;
        }
        for (var choice : root.path("choices")) {
            var delta = choice.path("delta");
            if (delta.hasNonNull("content")) {
                var value = delta.get("content").asText();
                if (!value.isEmpty()) {
                    text.append(value);
                    events.publish(new StreamEvent(
                        "text-delta", value, null, null, null, null, null, null));
                }
                recognized = true;
            }
            for (var call : delta.path("tool_calls")) {
                int index = call.path("index").asInt();
                boolean starting = !tools.containsKey(index);
                tools.computeIfAbsent(index, ignored -> new ToolAccumulator()).append(call);
                if (starting) {
                    events.publish(new StreamEvent(
                        "tool-call-start", null, null, null, null, null, call, null));
                }
                events.publish(new StreamEvent(
                    "tool-call-delta", null, null, null, null, null, call, null));
                recognized = true;
            }
            JsonNode reasoning = delta.hasNonNull("reasoning")
                ? delta.get("reasoning")
                : delta.get("reasoning_content");
            if (reasoning != null && reasoning.isTextual()) {
                events.publish(new StreamEvent(
                    "reasoning-delta",
                    reasoning.asText(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
                recognized = true;
            }
            if (choice.hasNonNull("finish_reason")) {
                finishReason = choice.get("finish_reason").asText();
            }
        }
        if (!recognized) {
            events.publish(new StreamEvent(
                "provider-event", null, null, null, null, null, root, null));
        }
        return new ChunkState(usage, finishReason);
    }

    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException ignored) {
        }
        if (!result.isDone()) {
            var cancellation = new java.util.concurrent.CancellationException(
                "Octoryn stream cancelled.");
            result.completeExceptionally(cancellation);
            events.fail(cancellation);
        }
        executor.shutdownNow();
    }

    private record ChunkState(Usage usage, String finishReason) {}

    private static final class ToolAccumulator {
        private String id = "";
        private String type = "function";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void append(JsonNode node) {
            if (node.hasNonNull("id")) id = node.get("id").asText();
            if (node.hasNonNull("type")) type = node.get("type").asText();
            var function = node.path("function");
            if (function.hasNonNull("name")) name.append(function.get("name").asText());
            if (function.hasNonNull("arguments")) {
                arguments.append(function.get("arguments").asText());
            }
        }

        ToolCall build() {
            return new ToolCall(id, name.toString(), arguments.toString(), type);
        }
    }
}
