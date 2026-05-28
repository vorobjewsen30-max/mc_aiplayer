package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.pathfinding.PathExecutor;
import io.github.zoyluo.aibot.pathfinding.PathfindingResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class ActionPack {
    private final AIPlayerEntity player;

    private float forward;
    private float strafing;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    private int jumpTicks;

    private WalkToController walkTo;
    private MiningController mining;
    private PathExecutor pathExecutor;
    private int itemUseCooldown;
    private int blockHitDelay;

    public ActionPack(AIPlayerEntity player) {
        this.player = player;
    }

    public AIPlayerEntity player() {
        return player;
    }

    public void setForward(float value) {
        this.forward = clampInput(value);
    }

    public void setStrafing(float value) {
        this.strafing = clampInput(value);
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        player.setSneaking(sneaking);
        if (sneaking && sprinting) {
            setSprinting(false);
        }
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        player.setSprinting(sprinting);
        if (sprinting && sneaking) {
            setSneaking(false);
        }
    }

    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    public void jumpOnce() {
        this.jumpTicks = 2;
    }

    public ActionResult startWalkTo(Vec3d target) {
        this.walkTo = new WalkToController(target);
        this.mining = null;
        this.pathExecutor = null;
        return ActionResult.IN_PROGRESS;
    }

    public ActionResult startPathTo(BlockPos goal) {
        AStarPathfinder finder = new AStarPathfinder(player.getServerWorld(), player.getBlockPos(), goal);
        PathfindingResult result = finder.findPath();
        if (!result.success()) {
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        this.pathExecutor = new PathExecutor(result.path(), goal);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public ActionResult startMining(BlockPos pos, Direction face) {
        this.mining = new MiningController(pos, face);
        this.pathExecutor = null;
        this.forward = 0.0F;
        this.strafing = 0.0F;
        return ActionResult.IN_PROGRESS;
    }

    public void stopMining() {
        if (this.mining != null) {
            this.mining.abort(player);
            this.mining = null;
        }
    }

    public void stopMovement() {
        setSneaking(false);
        setSprinting(false);
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.jumping = false;
        this.jumpTicks = 0;
        player.setJumping(false);
    }

    public void stopAll() {
        if (pathExecutor != null) {
            pathExecutor.abort(this);
            pathExecutor = null;
        }
        stopMining();
        this.walkTo = null;
        stopMovement();
    }

    public boolean isPathExecutorIdle() {
        return pathExecutor == null;
    }

    public boolean isMiningIdle() {
        return mining == null;
    }

    public void onUpdate() {
        tickPathExecutor();
        tickWalkTo();
        tickMining();

        if (itemUseCooldown > 0) {
            itemUseCooldown--;
        }
        if (blockHitDelay > 0) {
            blockHitDelay--;
        }

        float velocity = sneaking ? 0.3F : 1.0F;
        player.forwardSpeed = forward * velocity;
        player.sidewaysSpeed = strafing * velocity;
        boolean jumpNow = jumping || jumpTicks > 0;
        player.setJumping(jumpNow);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    public int itemUseCooldown() {
        return itemUseCooldown;
    }

    public void setItemUseCooldown(int itemUseCooldown) {
        this.itemUseCooldown = Math.max(0, itemUseCooldown);
    }

    public int blockHitDelay() {
        return blockHitDelay;
    }

    public void setBlockHitDelay(int blockHitDelay) {
        this.blockHitDelay = Math.max(0, blockHitDelay);
    }

    private void tickWalkTo() {
        if (walkTo == null) {
            return;
        }

        ActionResult result = walkTo.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "walk_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "walk_failed", "reason", result.reason());
        }
        walkTo = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickPathExecutor() {
        if (pathExecutor == null) {
            return;
        }

        ActionResult result = pathExecutor.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.path(player, "path_complete", "ticks", pathExecutor.totalTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "path_failed", "reason", result.reason());
        }
        pathExecutor = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickMining() {
        if (mining == null) {
            return;
        }

        ActionResult result = mining.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "mine_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "mine_failed", "reason", result.reason());
        }
        mining = null;
    }

    private static float clampInput(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }
}
