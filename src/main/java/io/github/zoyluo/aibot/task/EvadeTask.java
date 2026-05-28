package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class EvadeTask extends AbstractTask {
    private final Threat threat;
    private BlockPos escapeGoal;

    public EvadeTask(Threat threat) {
        this.threat = threat;
    }

    @Override
    public String name() {
        return "evade";
    }

    @Override
    public String describe() {
        return "Evading " + threat.type() + " toward " + (escapeGoal == null ? "(pending)" : compact(escapeGoal));
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, elapsed / 160.0D);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        escapeGoal = chooseGoal(bot);
        bot.getActionPack().startPathTo(escapeGoal);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (escapeGoal != null && bot.getBlockPos().getSquaredDistance(escapeGoal) <= 6.25D) {
            complete();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            escapeGoal = chooseGoal(bot);
            bot.getActionPack().startPathTo(escapeGoal);
        }
        if (elapsed > 400) {
            fail("evade_timeout");
        }
    }

    private BlockPos chooseGoal(AIPlayerEntity bot) {
        Vec3d away = new Vec3d(1.0D, 0.0D, 0.0D);
        if (threat.entity() != null) {
            away = bot.getPos().subtract(threat.entity().getPos());
        } else if (threat.pos() != null) {
            away = bot.getPos().subtract(Vec3d.ofCenter(threat.pos()));
        }
        if (away.lengthSquared() < 0.01D) {
            away = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        away = away.normalize().multiply(12.0D);
        BlockPos base = BlockPos.ofFloored(bot.getPos().add(away));
        for (int radius = 0; radius <= 4; radius++) {
            for (BlockPos candidate : BlockPos.iterate(base.add(-radius, -2, -radius), base.add(radius, 2, radius))) {
                if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), candidate)) {
                    return candidate.toImmutable();
                }
            }
        }
        return bot.getBlockPos();
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
