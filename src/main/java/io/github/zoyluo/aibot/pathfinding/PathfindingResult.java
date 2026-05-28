package io.github.zoyluo.aibot.pathfinding;

import java.util.List;

public record PathfindingResult(
        List<Node> path,
        boolean success,
        FailureReason reason,
        int nodesExplored,
        long elapsedMs
) {
    public static PathfindingResult success(List<Node> path, int nodesExplored, long elapsedMs) {
        return new PathfindingResult(List.copyOf(path), true, FailureReason.NONE, nodesExplored, elapsedMs);
    }

    public static PathfindingResult failure(FailureReason reason, int nodesExplored, long elapsedMs) {
        return new PathfindingResult(List.of(), false, reason, nodesExplored, elapsedMs);
    }
}
