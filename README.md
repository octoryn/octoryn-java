# Octoryn Java SDK

Governed model access for Java 17 and later.

Until the `ai.octoryn` namespace is available from Maven Central, install the
public release artifact into the caller's local Maven repository:

```bash
curl -fLO \
  https://github.com/octopusos/octoryn-java/releases/download/v0.1.1/octoryn-java-0.1.1.jar
mvn install:install-file \
  -Dfile=octoryn-java-0.1.1.jar \
  -DgroupId=ai.octoryn \
  -DartifactId=octoryn-java \
  -Dversion=0.1.1 \
  -Dpackaging=jar
```

```java
var client = new OctorynClient(System.getenv("OCTORYN_API_KEY"));
var result = client.generateText(
    GenerateTextRequest.builder("policy/au-enterprise")
        .prompt("Explain this routing decision.")
        .build());

System.out.println(result.octoryn().evidenceHash());
```

The SDK provides synchronous and asynchronous text generation, replayable
`Flow.Publisher` streaming events, typed tool inputs, strict JSON Schema
structured output, normalized errors, cancellation and governance metadata.
