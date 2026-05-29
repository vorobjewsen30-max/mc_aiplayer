package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FarmTask extends AbstractTask {
    private enum Phase {
        SURVEY,
        GOTO,
        TILL,
        PLANT,
        HARVEST,
        NEXT,
        DONE
    }

    private final BlockPos areaCenter;
    private final int radius;
    private final Item seed;
    private final Block crop;
    private final boolean keepTending;
    private final boolean harvestOnly;
    private final List<FarmTarget> targets = new ArrayList<>();
    private Phase phase = Phase.SURVEY;
    private FarmTarget current;
    private int completedActions;
    private int waitTicks;
    private String note = "";

    public FarmTask(BlockPos areaCenter, int radius, Item seed, Block crop, boolean keepTending, boolean harvestOnly) {
        this.areaCenter = areaCenter.toImmutable();
        this.radius = Math.max(1, radius);
        this.seed = seed;
        this.crop = crop;
        this.keepTending = keepTending;
        this.harvestOnly = harvestOnly;
    }

    @Override
    public String name() {
        return harvestOnly ? "harvest" : "farm";
    }

    @Override
    public String describe() {
        return name() + " crop=" + crop + " center=" + compact(areaCenter) + " radius=" + radius
                + " done=" + completedActions + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (keepTending) {
            return Math.min(0.95D, completedActions / 16.0D);
        }
        int total = completedActions + targets.size() + (current == null ? 0 : 1);
        return total == 0 ? 0.0D : Math.min(0.95D, (double) completedActions / total);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SURVEY;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (!keepTending && elapsed > 2400) {
            fail("farm_timeout");
            return;
        }
        if (keepTending && elapsed > 20 * 60 * 20) {
            complete();
            return;
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case TILL -> till(bot);
            case PLANT -> plant(bot);
            case HARVEST -> harvest(bot);
            case NEXT -> next(bot);
            case DONE -> done(bot);
        }
    }

    private void survey(AIPlayerEntity bot) {
        targets.clear();
        current = null;
        ServerWorld world = bot.getServerWorld();
        boolean hasSeeds = !harvestOnly && InventoryAction.countItem(bot, seed) > 0;
        BlockPos.stream(areaCenter.add(-radius, -1, -radius), areaCenter.add(radius, 1, radius))
                .map(BlockPos::toImmutable)
                .forEach(pos -> addTargetIfUseful(world, pos, hasSeeds));
        targets.sort(Comparator.comparingDouble(pos -> pos.ground().getSquaredDistance(bot.getBlockPos())));
        if (targets.isEmpty()) {
            if (!harvestOnly && InventoryAction.countItem(bot, seed) <= 0 && completedActions == 0) {
                fail("missing " + seed + " x1");
            } else {
                phase = Phase.DONE;
            }
            return;
        }
        phase = Phase.NEXT;
    }

    private void addTargetIfUseful(ServerWorld world, BlockPos ground, boolean hasSeeds) {
        BlockPos cropPos = ground.up();
        if (world.getBlockState(cropPos).isOf(crop) && FarmAction.isMature(world, cropPos)) {
            targets.add(new FarmTarget(ground, TargetAction.HARVEST));
            return;
        }
        if (harvestOnly || world.getBlockState(cropPos).isOf(crop) || !world.getBlockState(cropPos).isAir()) {
            return;
        }
        if (!hasSeeds) {
            return;
        }
        if (world.getBlockState(ground).isOf(Blocks.FARMLAND)) {
            targets.add(new FarmTarget(ground, TargetAction.PLANT));
            return;
        }
        if (FarmAction.isTillable(world.getBlockState(ground))) {
            targets.add(new FarmTarget(ground, TargetAction.TILL_PLANT));
        }
    }

    private void next(AIPlayerEntity bot) {
        current = targets.isEmpty() ? null : targets.remove(0);
        if (current == null) {
            phase = Phase.SURVEY;
            return;
        }
        phase = Phase.GOTO;
        goToTarget(bot);
    }

    private void goToTarget(AIPlayerEntity bot) {
        if (current == null) {
            phase = Phase.NEXT;
            return;
        }
        BlockPos focus = current.action() == TargetAction.HARVEST ? current.ground().up() : current.ground();
        if (bot.getEyePos().distanceTo(focus.toCenterPos()) <= 4.5D) {
            bot.getActionPack().stopAll();
            phase = switch (current.action()) {
                case HARVEST -> Phase.HARVEST;
                case PLANT -> Phase.PLANT;
                case TILL_PLANT -> Phase.TILL;
            };
            return;
        }
        BlockPos stand = adjacentStandPos(bot, current.ground());
        if (stand == null) {
            note = "unreachable " + compact(current.ground());
            phase = Phase.NEXT;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(stand);
        }
    }

    private void till(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "plant_skipped:missing " + seed + " x1";
            phase = Phase.NEXT;
            return;
        }
        ActionResult result = FarmAction.till(bot, current.ground());
        if (result.isFailed()) {
            note = result.reason();
            phase = Phase.NEXT;
            return;
        }
        phase = Phase.PLANT;
    }

    private void plant(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "plant_skipped:missing " + seed + " x1";
            phase = Phase.NEXT;
            return;
        }
        ActionResult result = FarmAction.plant(bot, current.ground(), seed, crop);
        if (result.isFailed()) {
            note = result.reason();
        } else {
            completedActions++;
        }
        phase = Phase.NEXT;
    }

    private void harvest(AIPlayerEntity bot) {
        ActionResult result = FarmAction.harvest(bot, current.ground().up());
        if (result.isFailed()) {
            note = result.reason();
            phase = Phase.NEXT;
            return;
        }
        completedActions++;
        if (!harvestOnly && InventoryAction.countItem(bot, seed) > 0) {
            ActionResult plantResult = FarmAction.plant(bot, current.ground(), seed, crop);
            if (plantResult.isFailed()) {
                note = "replant_failed:" + plantResult.reason();
            }
        } else if (!harvestOnly) {
            note = "replant_skipped:missing " + seed + " x1";
        }
        phase = Phase.NEXT;
    }

    private void done(AIPlayerEntity bot) {
        if (keepTending) {
            waitTicks++;
            if (waitTicks >= 100) {
                waitTicks = 0;
                phase = Phase.SURVEY;
            }
            return;
        }
        complete();
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction).up();
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private enum TargetAction {
        TILL_PLANT,
        PLANT,
        HARVEST
    }

    private record FarmTarget(BlockPos ground, TargetAction action) {
    }
}
