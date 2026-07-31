package ai.octoryn.sdk;

import java.math.BigDecimal;

public record GovernanceMetadata(
    String runId,
    String upstream,
    String byok,
    String region,
    String route,
    String policyDecision,
    String evidenceHash,
    BigDecimal estimatedCost
) {}
