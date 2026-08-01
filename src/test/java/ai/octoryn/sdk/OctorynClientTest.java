package ai.octoryn.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OctorynClientTest {
    @Test
    void generatesTextToolsAndGovernance() throws Exception {
        try (var server = server(exchange -> {
            assertEquals("/v1/chat/completions", exchange.getRequestURI().getPath());
            assertEquals("Bearer test-key", exchange.getRequestHeaders()
                .getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Octoryn-Run-Id", "run_java");
            exchange.getResponseHeaders().add("X-Octoryn-Region", "au-sydney");
            exchange.getResponseHeaders().add("X-Octoryn-Estimated-Cost", "0.001");
            respond(exchange, 200, fixture("chat-completion.json"));
        })) {
            var client = client(server);
            var result = client.generateText(GenerateTextRequest
                .builder("policy/frontier")
                .prompt("Weather?")
                .tools(List.of(new ToolDefinition(
                    "getWeather",
                    "Get weather",
                    client.objectMapper().readTree("""
                        {"type":"object","properties":{"city":{"type":"string"}}}
                        """))))
                .build());
            assertEquals("Governed answer", result.text());
            assertEquals("Sydney", result.toolCalls().get(0)
                .decodeInput(WeatherInput.class, client.objectMapper()).city());
            assertEquals("run_java", result.octoryn().runId());
            assertEquals("au-sydney", result.octoryn().region());
            assertEquals("0.001", result.octoryn().estimatedCost().toPlainString());
        }
    }

    @Test
    void generatesTypedStructuredOutput() throws Exception {
        try (var server = server(exchange -> {
            var request = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
            assertTrue(request.contains("\"response_format\""));
            respond(exchange, 200, """
                {"choices":[{"message":{"role":"assistant","content":"{\\"risk\\":\\"low\\",\\"score\\":7}"},"finish_reason":"stop"}]}
                """);
        })) {
            var client = client(server);
            var request = new GenerateObjectRequest<>(
                GenerateTextRequest.builder("policy/risk").prompt("Assess").build(),
                client.objectMapper().readTree("""
                    {"type":"object","properties":{"risk":{"type":"string"},"score":{"type":"number"}}}
                    """),
                Risk.class);
            var result = client.generateObject(request);
            assertEquals(new Risk("low", 7), result.object());
        }
    }

    @Test
    void streamsAndReplaysSplitToolCalls() throws Exception {
        try (var server = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("X-Octoryn-Upstream", "anthropic");
            respond(exchange, 200, fixture("chat-stream.sse"));
        })) {
            var client = client(server);
            try (var stream = client.streamText(GenerateTextRequest
                .builder("policy/frontier")
                .prompt("Weather?")
                .build())) {
                var result = stream.result().get(2, TimeUnit.SECONDS);
                assertEquals("Octoryn", result.text());
                assertEquals("Sydney", result.toolCalls().get(0)
                    .decodeInput(WeatherInput.class, client.objectMapper()).city());
                assertEquals("anthropic", result.octoryn().upstream());

                var first = collect(stream.events());
                var second = collect(stream.events());
                assertEquals(first, second);
                assertTrue(first.stream().anyMatch(event ->
                    "tool-call-delta".equals(event.type())));
                assertTrue(first.stream().anyMatch(event ->
                    "tool-call".equals(event.type())));
            }
        }
    }

    @Test
    void mapsApiErrors() throws Exception {
        try (var server = server(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "2");
            respond(exchange, 429, """
                {"error":{"code":"quota_exceeded","message":"budget exhausted"}}
                """);
        })) {
            var error = assertThrows(OctorynApiException.class, () ->
                client(server).generateText(GenerateTextRequest
                    .builder("policy/frontier")
                    .prompt("hello")
                    .build()));
            assertEquals("quota_exceeded", error.code());
            assertEquals(Duration.ofSeconds(2), error.retryAfter());
        }
    }

    @Test
    void mapsStreamingApiErrors() throws Exception {
        try (var server = server(exchange -> respond(exchange, 403, """
            {"error":{"code":"policy_denied","message":"route denied"}}
            """))) {
            var error = assertThrows(OctorynApiException.class, () ->
                client(server).streamText(GenerateTextRequest
                    .builder("policy/frontier")
                    .prompt("hello")
                    .build()));
            assertEquals("policy_denied", error.code());
        }
    }

    @Test
    void structuredValidationErrorPreservesRawOutput() throws Exception {
        try (var server = server(exchange -> respond(exchange, 200, """
            {"choices":[{"message":{"role":"assistant","content":"not-json"},"finish_reason":"stop"}]}
            """))) {
            var client = client(server);
            var request = new GenerateObjectRequest<>(
                GenerateTextRequest.builder("policy/risk").prompt("Assess").build(),
                client.objectMapper().readTree("{\"type\":\"object\"}"),
                Risk.class);
            var error = assertThrows(
                StructuredOutputException.class,
                () -> client.generateObject(request));
            assertEquals("not-json", error.rawOutput());
        }
    }

    @Test
    void cancellingStreamCompletesResultExceptionally() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(
                "data: {\"choices\":[{\"delta\":{\"content\":\"started\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        })) {
            var stream = client(server).streamText(GenerateTextRequest
                .builder("policy/frontier")
                .prompt("hello")
                .build());
            stream.close();
            assertTrue(stream.result().isCompletedExceptionally());
            release.countDown();
        }
    }

    private static List<StreamEvent> collect(Flow.Publisher<StreamEvent> publisher)
        throws InterruptedException {
        var values = new ArrayList<StreamEvent>();
        var error = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(StreamEvent item) { values.add(item); }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }

            @Override
            public void onComplete() { done.countDown(); }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
        return List.copyOf(values);
    }

    private static OctorynClient client(TestServer server) {
        return new OctorynClient(
            "test-key",
            URI.create("http://127.0.0.1:" + server.port() + "/v1/"),
            HttpClient.newHttpClient());
    }

    private static String fixture(String name) throws IOException {
        var monorepoPath = Path.of("..", "sdk-conformance", "v1", name);
        var mirrorPath = Path.of("sdk-conformance", "v1", name);
        return Files.readString(Files.exists(monorepoPath) ? monorepoPath : mirrorPath);
    }

    private static TestServer server(Handler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable failure) {
                failure.printStackTrace();
                if (exchange.getResponseCode() == -1) {
                    respond(exchange, 500, "{\"error\":{\"message\":\"test failure\"}}");
                }
            }
        });
        server.start();
        return new TestServer(server);
    }

    private static void respond(HttpExchange exchange, int status, String body)
        throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private record WeatherInput(String city) {}
    private record Risk(String risk, int score) {}

    private record TestServer(HttpServer server) implements AutoCloseable {
        int port() { return server.getAddress().getPort(); }
        @Override
        public void close() { server.stop(0); }
    }
}
