package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;

public final class CombatTask extends AbstractTask {
    private enum Phase {
        ACQUIRE,
        APPROACH,
        STRIKE,
        REPOSITION,
        RETREAT
    }

    private static final float ATTACK_RANGE = 3.0F;
    private static final int SEARCH_RANGE = 20;

    private final EntityType<?> targetType;
    private final int targetKills;
    private final float retreatHpThreshold;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private int kills;
    private int repositionTicks;

    public CombatTask(EntityType<?> targetType, int targetKills, float retreatHpThreshold) {
        this.targetType = targetType;
        this.targetKills = Math.max(1, targetKills);
        this.retreatHpThreshold = retreatHpThreshold;
    }

    @Override
    public String name() {
        return "combat";
    }

    @Override
    public String describe() {
        return "Attacking " + Registries.ENTITY_TYPE.getId(targetType) + " " + kills + "/" + targetKills + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) kills / targetKills);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        EquipAction.equipBestArmor(bot);
        EquipAction.equipBestWeapon(bot);
        phase = Phase.ACQUIRE;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("combat_timeout");
            return;
        }
        if (bot.getHealth() <= retreatHpThreshold) {
            phase = Phase.RETREAT;
        }
        switch (phase) {
            case ACQUIRE -> acquire(bot);
            case APPROACH -> approach(bot);
            case STRIKE -> strike(bot);
            case REPOSITION -> reposition(bot);
            case RETREAT -> retreat(bot);
        }
    }

    private void acquire(AIPlayerEntity bot) {
        target = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(SEARCH_RANGE),
                        entity -> entity.isAlive() && entity.getType().equals(targetType) && entity != bot)
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo))
                .orElse(null);
        if (target == null) {
            if (kills > 0) {
                complete();
            } else {
                fail("no_target_in_range");
            }
            return;
        }
        phase = Phase.APPROACH;
        startApproach(bot);
    }

    private void approach(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire();
            return;
        }
        lookAtTarget(bot);
        if (bot.distanceTo(target) <= ATTACK_RANGE) {
            bot.getActionPack().stopAll();
            phase = Phase.STRIKE;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            startApproach(bot);
        }
    }

    private void strike(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire();
            return;
        }
        lookAtTarget(bot);
        if (bot.distanceTo(target) > ATTACK_RANGE + 0.75F) {
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        if (bot.getAttackCooldownProgress(0.5F) >= 0.95F) {
            InteractAction.attackEntity(bot, target);
            repositionTicks = 8;
            phase = Phase.REPOSITION;
        }
    }

    private void reposition(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            bot.getActionPack().stopMovement();
            kills++;
            finishOrAcquire();
            return;
        }
        lookAtTarget(bot);
        bot.getActionPack().setStrafing(elapsed % 40 < 20 ? 0.45F : -0.45F);
        repositionTicks--;
        if (repositionTicks <= 0) {
            bot.getActionPack().stopMovement();
            phase = Phase.STRIKE;
        }
    }

    private void retreat(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        fail("retreat");
    }

    private void startApproach(AIPlayerEntity bot) {
        BlockPos goal = target.getBlockPos();
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(target.getPos());
        }
    }

    private void lookAtTarget(AIPlayerEntity bot) {
        Vec3d targetCenter = target.getPos().add(0.0D, target.getHeight() * 0.5D, 0.0D);
        LookAction.lookAt(bot, targetCenter);
    }

    private void finishOrAcquire() {
        target = null;
        if (kills >= targetKills) {
            complete();
        } else {
            phase = Phase.ACQUIRE;
        }
    }
}
