package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.Optional;

public final class DangerWatcher {
    public static final DangerWatcher INSTANCE = new DangerWatcher();

    private DangerWatcher() {
    }

    public void scanAll(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            Optional<Threat> threat = collectTopThreat(bot);
            Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
            if (threat.isPresent()) {
                Threat top = threat.get();
                if (active.isPresent() && !(active.get() instanceof EvadeTask)
                        && top.severity().ordinal() >= Threat.Severity.MEDIUM.ordinal()) {
                    TaskManager.INSTANCE.pauseFor(bot, "threat: " + top.type());
                    TaskManager.INSTANCE.assign(bot, new EvadeTask(top));
                    BotLog.danger(bot, "threat_detected", "type", top.type(), "severity", top.severity(), "source", top.pos());
                }
            } else if (active.isEmpty() && TaskManager.INSTANCE.hasPaused(bot)) {
                TaskManager.INSTANCE.resumeFromPause(bot);
            }
        }
    }

    private static Optional<Threat> collectTopThreat(AIPlayerEntity bot) {
        if (bot.getHealth() < 6.0F) {
            return Optional.of(new Threat(Threat.Type.LOW_HP, Threat.Severity.HIGH, null, bot.getBlockPos()));
        }
        Optional<LivingEntity> hostile = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(8.0D),
                        entity -> entity instanceof HostileEntity && entity.isAlive())
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
        if (hostile.isPresent()) {
            return Optional.of(new Threat(Threat.Type.HOSTILE, Threat.Severity.MEDIUM, hostile.get(), hostile.get().getBlockPos()));
        }
        if (bot.isSubmergedInWater() && bot.getAir() < 50) {
            return Optional.of(new Threat(Threat.Type.DROWNING, Threat.Severity.MEDIUM, null, bot.getBlockPos()));
        }
        Optional<BlockPos> lava = BlockPos.stream(bot.getBlockPos().add(-2, -1, -2), bot.getBlockPos().add(2, 1, 2))
                .filter(pos -> {
                    BlockState state = bot.getServerWorld().getBlockState(pos);
                    return state.getFluidState().isIn(FluidTags.LAVA);
                })
                .map(BlockPos::toImmutable)
                .findFirst();
        if (lava.isPresent()) {
            return Optional.of(new Threat(Threat.Type.LAVA, Threat.Severity.HIGH, null, lava.get()));
        }
        if (bot.fallDistance > 5.0F && !bot.isOnGround()) {
            return Optional.of(new Threat(Threat.Type.FALLING, Threat.Severity.LOW, null, bot.getBlockPos()));
        }
        return Optional.empty();
    }
}
