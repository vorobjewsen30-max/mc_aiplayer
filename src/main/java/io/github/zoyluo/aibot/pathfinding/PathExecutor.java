package io.github.zoyluo.aibot.pathfinding;

import io.github.zoyluo.aibot.action.ActionPack;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MiningController;
import io.github.zoyluo.aibot.action.WalkToController;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.log.LogFields;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class PathExecutor {
    private static final int STUCK_TICKS_LIMIT = 60;

    private List<Node> path;
    private int index = 1;
    private final BlockPos originalGoal;
    private WalkToController subWalker;
    private MiningController subMiner;
    private boolean digWalking;
    private boolean replanTried;
    private Vec3d lastPos;
    private int stuckTicks;
    private int totalTicks;

    public PathExecutor(List<Node> path, BlockPos originalGoal) {
        this.path = List.copyOf(path);
        this.originalGoal = originalGoal.toImmutable();
    }

    public ActionResult tick(ActionPack pack) {
        totalTicks++;
        if (path.isEmpty() || index >= path.size()) {
            cleanup(pack);
            return ActionResult.SUCCESS;
        }

        Node next = path.get(index);
        String danger = DangerCheck.scan(pack.player().getServerWorld(), next.pos());
        if (danger != null) {
            BotLog.warn(LogCategory.PATH, pack.player(), "path_danger", "at_node", LogFields.pos(next.pos()), "reason", danger);
            cleanup(pack);
            return ActionResult.failed("danger_at_node: " + danger);
        }

        ActionResult result = switch (next.moveType()) {
            case WALK, JUMP_UP, DROP_DOWN -> tickWalk(pack, next);
            case DIG_THROUGH -> tickDigThrough(pack, next);
        };
        if (!result.isInProgress()) {
            return result;
        }
        return checkProgress(pack, next);
    }

    public void abort(ActionPack pack) {
        cleanup(pack);
    }

    public int totalTicks() {
        return totalTicks;
    }

    private ActionResult tickWalk(ActionPack pack, Node next) {
        if (subWalker == null) {
            subWalker = new WalkToController(Vec3d.ofCenter(next.pos()));
        }
        ActionResult result = subWalker.tick(pack);
        if (result.isSuccess()) {
            advance();
        }
        if (result.isFailed()) {
            return handleStuck(pack, "walk_failed: " + result.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    private ActionResult tickDigThrough(ActionPack pack, Node next) {
        if (!digWalking) {
            if (subMiner == null) {
                LookAction.lookAtBlock(pack.player(), next.pos(), faceFromPlayer(pack, next.pos()));
                subMiner = new MiningController(next.pos(), faceFromPlayer(pack, next.pos()));
            }
            ActionResult mine = subMiner.tick(pack);
            if (mine.isFailed()) {
                return handleStuck(pack, "dig_failed: " + mine.reason());
            }
            if (mine.isInProgress()) {
                return ActionResult.IN_PROGRESS;
            }
            subMiner = null;
            digWalking = true;
            subWalker = new WalkToController(Vec3d.ofCenter(next.pos()));
        }

        ActionResult walk = subWalker.tick(pack);
        if (walk.isSuccess()) {
            advance();
        }
        if (walk.isFailed()) {
            return handleStuck(pack, "dig_walk_failed: " + walk.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    private ActionResult checkProgress(ActionPack pack, Node next) {
        Vec3d current = pack.player().getPos();
        if (lastPos != null && current.distanceTo(lastPos) < 0.03D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = current;
        if (stuckTicks > STUCK_TICKS_LIMIT) {
            return handleStuck(pack, "no_progress_at: " + compact(next.pos()));
        }
        return ActionResult.IN_PROGRESS;
    }

    private void advance() {
        Node next = path.get(index);
        BotLog.path(null, "path_advance", "index", index, "total", path.size(), "move_type", next.moveType(), "pos", LogFields.pos(next.pos()));
        index++;
        subWalker = null;
        subMiner = null;
        digWalking = false;
        stuckTicks = 0;
        lastPos = null;
    }

    private ActionResult handleStuck(ActionPack pack, String reason) {
        if (!replanTried) {
            replanTried = true;
            BotLog.path(pack.player(), "path_stuck", "at_node", reason, "stuck_ticks", stuckTicks);
            AStarPathfinder finder = new AStarPathfinder(pack.player().getServerWorld(), pack.player().getBlockPos(), originalGoal);
            PathfindingResult fresh = finder.findPath();
            if (fresh.success()) {
                BotLog.path(pack.player(), "path_replan", "at_node", reason, "new_path_size", fresh.path().size());
                path = fresh.path();
                index = 1;
                subWalker = null;
                subMiner = null;
                digWalking = false;
                stuckTicks = 0;
                lastPos = null;
                return ActionResult.IN_PROGRESS;
            }
            reason = reason + "; replan_failed: " + fresh.reason();
        }
        cleanup(pack);
        return ActionResult.failed(reason);
    }

    private void cleanup(ActionPack pack) {
        if (subMiner != null) {
            subMiner.abort(pack.player());
            subMiner = null;
        }
        subWalker = null;
        digWalking = false;
        pack.stopMovement();
    }

    private static Direction faceFromPlayer(ActionPack pack, BlockPos pos) {
        return Direction.getFacing(pack.player().getEyePos().subtract(pos.toCenterPos()));
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
