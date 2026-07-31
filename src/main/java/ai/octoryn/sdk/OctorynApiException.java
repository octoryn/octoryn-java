package ai.octoryn.sdk;

import java.time.Duration;

public final class OctorynApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int statusCode;
    private final String code;
    private final String errorType;
    private final String requestId;
    private final Duration retryAfter;

    public OctorynApiException(
        int statusCode,
        String message,
        String code,
        String errorType,
        String requestId,
        Duration retryAfter
    ) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
        this.errorType = errorType;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
    }

    public int statusCode() { return statusCode; }
    public String code() { return code; }
    public String errorType() { return errorType; }
    public String requestId() { return requestId; }
    public Duration retryAfter() { return retryAfter; }
}
