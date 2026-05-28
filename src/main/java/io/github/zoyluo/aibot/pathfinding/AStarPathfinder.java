package io.github.zoyluo.aibot.pathfinding;

import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class AStarPathfinder {
    private static final int DEFAULT_MAX_NODES = 10_000;
    private static final long DEFAULT_MAX_MILLIS = 50L;

    private final ServerWorld world;
    private final BlockPos start;
    private final BlockPos goal;
    private final NeighborEnumerator enumerator;
    private final int maxNodes;
    private final long maxMillis;

    public AStarPathfinder(ServerWorld world, BlockPos start, BlockPos goal) {
        this(world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS);
    }

    public AStarPathfinder(ServerWorld world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis) {
        this.world = world;
        this.start = start.toImmutable();
        this.goal = goal.toImmutable();
        this.enumerator = new NeighborEnumerator();
        this.maxNodes = maxNodes;
        this.maxMillis = maxMillis;
    }

    public PathfindingResult findPath() {
        long startTime = System.currentTimeMillis();
        BotLog.path(null, "findpath_start", "start", LogFields.pos(start), "goal", LogFields.pos(goal));
        Standability.clearCache();
        if (!Standability.isStandable(world, start)) {
            return done(PathfindingResult.failure(FailureReason.NO_START, 0, elapsed(startTime)));
        }
        if (!Standability.isStandable(world, goal)) {
            return done(PathfindingResult.failure(FailureReason.GOAL_NOT_STANDABLE, 0, elapsed(startTime)));
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator
                .comparingDouble(Node::fCost)
                .thenComparingDouble(Node::hCost));
        Map<BlockPos, Double> gScore = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node startNode = new Node(start, 0.0D, CostModel.heuristic(start, goal), MoveType.WALK, null);
        open.add(startNode);
        gScore.put(start, 0.0D);

        int explored = 0;
        while (!open.isEmpty()) {
            if (explored >= maxNodes) {
                return done(PathfindingResult.failure(FailureReason.SEARCH_LIMIT, explored, elapsed(startTime)));
            }
            if (elapsed(startTime) > maxMillis) {
                return done(PathfindingResult.failure(FailureReason.TIMEOUT, explored, elapsed(startTime)));
            }

            Node current = open.poll();
            if (!closed.add(current.pos())) {
                continue;
            }
            explored++;
            if (current.pos().equals(goal)) {
                return done(PathfindingResult.success(reconstruct(current), explored, elapsed(startTime)));
            }

            for (NeighborCandidate neighbor : enumerator.getNeighbors(current.pos(), world)) {
                if (closed.contains(neighbor.pos())) {
                    continue;
                }
                double tentativeG = current.gCost() + CostModel.stepCost(neighbor.moveType(), neighbor.fallHeight());
                double knownG = gScore.getOrDefault(neighbor.pos(), Double.POSITIVE_INFINITY);
                if (knownG <= tentativeG) {
                    continue;
                }
                gScore.put(neighbor.pos(), tentativeG);
                open.add(new Node(
                        neighbor.pos(),
                        tentativeG,
                        CostModel.heuristic(neighbor.pos(), goal),
                        neighbor.moveType(),
                        current));
            }
        }
        return done(PathfindingResult.failure(FailureReason.GOAL_UNREACHABLE, explored, elapsed(startTime)));
    }

    private static PathfindingResult done(PathfindingResult result) {
        BotLog.path(null, "findpath_done",
                "success", result.success(),
                "nodes", result.nodesExplored(),
                "ms", result.elapsedMs(),
                "fail_reason", result.reason());
        return result;
    }

    private static List<Node> reconstruct(Node end) {
        List<Node> path = new ArrayList<>();
        for (Node current = end; current != null; current = current.parent()) {
            path.add(current);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private static long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }
}
