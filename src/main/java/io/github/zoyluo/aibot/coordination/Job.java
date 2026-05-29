package io.github.zoyluo.aibot.coordination;

import java.util.Map;
import java.util.UUID;

public record Job(
        UUID id,
        String kind,
        Map<String, String> params,
        String role,
        Status status,
        UUID claimant,
        String failureReason
) {
    public enum Status {
        OPEN,
        CLAIMED,
        DONE,
        FAILED
    }

    public Job withStatus(Status newStatus, UUID newClaimant, String reason) {
        return new Job(id, kind, Map.copyOf(params), role, newStatus, newClaimant, reason == null ? "" : reason);
    }
}
