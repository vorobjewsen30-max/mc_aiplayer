package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;

public final class MineTask extends AbstractTask {
    private enum Phase {
        SEARCHING,
        MOVING,
        MINING,
        PICKING_UP
    }

    private final Block targetBlock;
    private final int countNeeded;
    private Phase phase = Phase.SEARCHING;
    private BlockPos targetPos;
    private int countSoFar;
    private int inventoryCountBeforeMining;
    private int pickupTicks;

    public MineTask(Block targetBlock, int countNeeded) {
        this.targetBlock = targetBlock;
        this.countNeeded = Math.max(1, countNeeded);
    }

    @Override
    public String name() {
        return "mine";
    }

    @Override
    public String describe() {
        return "Mining " + Registries.BLOCK.getId(targetBlock) + " " + countSoFar + "/" + countNeeded + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) countSoFar / countNeeded);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SEARCHING;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("timeout_120s");
            return;
        }
        switch (phase) {
            case SEARCHING -> search(bot);
            case MOVING -> move(bot);
            case MINING -> mine(bot);
            case PICKING_UP -> pickup(bot);
        }
    }

    private void search(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        targetPos = BlockPos.stream(origin.add(-8, -4, -8), origin.add(8, 6, 8))
                .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(targetBlock))
                .map(BlockPos::toImmutable)
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin)))
                .orElse(null);
        if (targetPos == null) {
            fail("no_target_block_in_range");
            return;
        }
        BlockPos stand = adjacentStandPos(bot, targetPos);
        if (stand == null) {
            fail("no_adjacent_standable");
            return;
        }
        phase = Phase.MOVING;
        bot.getActionPack().startPathTo(stand);
    }

    private void move(AIPlayerEntity bot) {
        if (targetPos == null || !bot.getServerWorld().getBlockState(targetPos).isOf(targetBlock)) {
            phase = Phase.SEARCHING;
            return;
        }
        if (bot.getEyePos().distanceTo(targetPos.toCenterPos()) <= 4.5D) {
            bot.getActionPack().stopAll();
            inventoryCountBeforeMining = totalInventoryCount(bot);
            MiningAction.startMining(bot, targetPos, Direction.getFacing(bot.getEyePos().subtract(targetPos.toCenterPos())));
            phase = Phase.MINING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            phase = Phase.SEARCHING;
        }
    }

    private void mine(AIPlayerEntity bot) {
        if (targetPos == null || !bot.getServerWorld().getBlockState(targetPos).isOf(targetBlock)) {
            pickupTicks = 120;
            phase = Phase.PICKING_UP;
            return;
        }
        if (bot.getActionPack().isMiningIdle() && elapsed % 200 == 0) {
            inventoryCountBeforeMining = totalInventoryCount(bot);
            MiningAction.startMining(bot, targetPos, Direction.getFacing(bot.getEyePos().subtract(targetPos.toCenterPos())));
        }
    }

    private void pickup(AIPlayerEntity bot) {
        if (totalInventoryCount(bot) > inventoryCountBeforeMining) {
            countSoFar++;
            if (countSoFar >= countNeeded) {
                complete();
            } else {
                phase = Phase.SEARCHING;
            }
            return;
        }
        pickupTicks--;
        bot.getServerWorld()
                .getEntitiesByClass(ItemEntity.class, bot.getBoundingBox().expand(8.0D), entity -> !entity.getStack().isEmpty())
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)))
                .ifPresent(item -> {
                    if (bot.distanceTo(item) > 1.5F && bot.getActionPack().isPathExecutorIdle()) {
                        bot.getActionPack().startPathTo(item.getBlockPos());
                    }
                });
        if (pickupTicks <= 0) {
            fail("pickup_timeout");
        }
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction);
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static int totalInventoryCount(AIPlayerEntity bot) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
