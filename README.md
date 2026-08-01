# Octoryn Java SDK

Governed model access for Java 17 and later.

Install the signed release from Maven Central:

```xml
<dependency>
  <groupId>ai.octoryn</groupId>
  <artifactId>octoryn-java</artifactId>
  <version>0.1.1</version>
</dependency>
```

```java
var client = new OctorynClient(System.getenv("OCTORYN_API_KEY"));
var result = client.generateText(
    GenerateTextRequest.builder("openai/gpt-4.1-mini")
        .prompt("Explain this routing decision.")
        .build());

System.out.println(result.octoryn().evidenceHash());
```

The SDK provides synchronous and asynchronous text generation, replayable
`Flow.Publisher` streaming events, typed tool inputs, strict JSON Schema
structured output, normalized errors, cancellation and governance metadata.
