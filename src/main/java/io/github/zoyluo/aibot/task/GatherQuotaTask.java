package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

public final class GatherQuotaTask extends AbstractTask {
    private static final int SEARCH_RADIUS = 16;

    private enum Phase {
        SURVEY,
        GOTO,
        HARVEST,
        PICKUP,
        DEPOSIT,
        DONE
    }

    private final Item targetItem;
    private final int targetCount;
    private final Block harvestBlock;
    private Phase phase = Phase.SURVEY;
    private BlockPos targetPos;
    private int countSoFar;
    private int countBeforeHarvest;
    private int pickupTicks;
    private boolean pickupSweepAttempted;
    private StockpileTask stockpileTask;

    public GatherQuotaTask(Item targetItem, int targetCount) {
        this.targetItem = targetItem;
        this.targetCount = Math.max(1, targetCount);
        this.harvestBlock = harvestBlockFor(targetItem);
    }

    @Override
    public String name() {
        return "gather";
    }

    @Override
    public String describe() {
        return "Gathering " + Registries.ITEM.getId(targetItem) + " " + countSoFar + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) countSoFar / targetCount);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        countSoFar = InventoryAction.countItem(bot, targetItem);
        phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
        stockpileTask = null;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        countSoFar = InventoryAction.countItem(bot, targetItem);
        if (countSoFar >= targetCount) {
            phase = Phase.DONE;
        }
        if (elapsed > 6000) {
            fail("gather_timeout");
            return;
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case HARVEST -> harvest(bot);
            case PICKUP -> pickup(bot);
            case DEPOSIT -> deposit(bot);
            case DONE -> complete();
        }
    }

    private void survey(AIPlayerEntity bot) {
        if (harvestBlock == null) {
            fail("unsupported_resource_type");
            return;
        }
        if (HarvestCore.isInventoryFull(bot) && countSoFar < targetCount) {
            phase = Phase.DEPOSIT;
            return;
        }
        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(bot, harvestBlock, SEARCH_RADIUS, 4, 8);
        if (choice == null) {
            fail("no_resource_nearby");
            return;
        }
        targetPos = choice.pos();
        if (choice.direct()) {
            startHarvest(bot);
            return;
        }
        phase = Phase.GOTO;
        bot.getActionPack().startPathTo(choice.stand());
    }

    private void goToTarget(AIPlayerEntity bot) {
        if (targetPos == null || !bot.getServerWorld().getBlockState(targetPos).isOf(harvestBlock)) {
            phase = Phase.SURVEY;
            return;
        }
        if (HarvestCore.canReach(bot, targetPos)) {
            bot.getActionPack().stopAll();
            startHarvest(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            phase = Phase.SURVEY;
        }
    }

    private void harvest(AIPlayerEntity bot) {
        if (targetPos == null || !bot.getServerWorld().getBlockState(targetPos).isOf(harvestBlock)) {
            pickupTicks = 120;
            phase = Phase.PICKUP;
            return;
        }
        if (bot.getActionPack().isMiningIdle() && elapsed % 200 == 0) {
            startHarvest(bot);
        }
    }

    private void pickup(AIPlayerEntity bot) {
        HarvestCore.forcePickupNearby(bot, targetItem);
        countSoFar = InventoryAction.countItem(bot, targetItem);
        if (countSoFar > countBeforeHarvest) {
            phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
            return;
        }
        pickupTicks--;
        HarvestCore.chaseDrop(bot, targetItem, 8.0D);
        if (pickupTicks <= 0) {
            if (!pickupSweepAttempted && HarvestCore.nearestDrop(bot, targetItem, 8.0D).isPresent()) {
                pickupSweepAttempted = true;
                HarvestCore.sweepPickup(bot, targetItem, 8);
                pickupTicks = 60;
                return;
            }
            countSoFar = InventoryAction.countItem(bot, targetItem);
            if (countSoFar > countBeforeHarvest) {
                phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
            } else {
                fail("pickup_timeout");
            }
        }
    }

    private void deposit(AIPlayerEntity bot) {
        if (stockpileTask == null) {
            bot.getActionPack().stopAll();
            stockpileTask = new StockpileTask(true);
            stockpileTask.start(bot);
        }
        stockpileTask.tick(bot);
        if (stockpileTask.state() == TaskState.COMPLETED) {
            stockpileTask = null;
            phase = Phase.SURVEY;
            return;
        }
        if (stockpileTask.state() == TaskState.FAILED) {
            String reason = stockpileTask.failureReason();
            stockpileTask = null;
            fail(reason == null || reason.isBlank() ? "inventory_full" : reason);
        }
    }

    private void startHarvest(AIPlayerEntity bot) {
        countBeforeHarvest = InventoryAction.countItem(bot, targetItem);
        pickupSweepAttempted = false;
        HarvestCore.startMining(bot, targetPos);
        phase = Phase.HARVEST;
    }

    private static Block harvestBlockFor(Item item) {
        if (item == Items.COBBLESTONE) {
            return Blocks.STONE;
        }
        if (item instanceof BlockItem blockItem) {
            return blockItem.getBlock();
        }
        return null;
    }
}
