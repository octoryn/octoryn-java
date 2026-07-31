package ai.octoryn.sdk;

public final class StructuredOutputException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String rawOutput;

    public StructuredOutputException(String rawOutput, Throwable cause) {
        super("Octoryn structured output validation failed.", cause);
        this.rawOutput = rawOutput;
    }

    public String rawOutput() { return rawOutput; }
}
