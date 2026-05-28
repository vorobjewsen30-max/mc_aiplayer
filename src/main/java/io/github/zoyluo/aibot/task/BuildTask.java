package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.OptionalInt;

public final class BuildTask extends AbstractTask {
    private final BlueprintSchema blueprint;
    private final BlockPos anchor;
    private int nextIndex;
    private int retryTicks;
    private int placeDelayTicks;

    public BuildTask(BlueprintSchema blueprint, BlockPos anchor) {
        this.blueprint = blueprint;
        this.anchor = anchor.toImmutable();
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String describe() {
        return "Building " + blueprint.name() + " at " + compact(anchor) + " " + nextIndex + "/" + blueprint.placements().size();
    }

    @Override
    public double progress() {
        return blueprint.placements().isEmpty() ? 1.0D : Math.min(1.0D, (double) nextIndex / blueprint.placements().size());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        nextIndex = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 7200) {
            fail("timeout_6min");
            return;
        }
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return;
        }
        if (nextIndex >= blueprint.placements().size()) {
            complete();
            return;
        }
        BlueprintSchema.BlockPlacement placement = blueprint.placements().get(nextIndex);
        BlockPos pos = anchor.add(placement.dx(), placement.dy(), placement.dz());
        Block block = Registries.BLOCK.get(Identifier.of(placement.blockId()));
        if (block == Blocks.AIR) {
            nextIndex++;
            return;
        }
        if (bot.getServerWorld().getBlockState(pos).isOf(block)) {
            nextIndex++;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) > 64.0D) {
            if (bot.getActionPack().isPathExecutorIdle()) {
                BlockPos stand = nearbyStand(bot, pos);
                if (stand == null) {
                    fail("no_stand_position_for: " + compact(pos));
                    return;
                }
                bot.getActionPack().startPathTo(stand);
            }
            return;
        }
        if (!bot.getActionPack().isPathExecutorIdle()) {
            if (bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) <= 20.25D) {
                bot.getActionPack().stopAll();
            } else {
                return;
            }
        }
        Item item = block.asItem();
        if (!(item instanceof BlockItem)) {
            fail("not_placeable_item: " + placement.blockId());
            return;
        }
        OptionalInt slot = InventoryAction.findItem(bot, item);
        if (slot.isEmpty()) {
            fail("missing_material: " + placement.blockId());
            return;
        }
        if (slot.getAsInt() >= 9) {
            fail("material_not_in_hotbar: " + placement.blockId());
            return;
        }
        InventoryAction.selectHotbar(bot, slot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isSuccess()) {
            nextIndex++;
            retryTicks = 0;
            placeDelayTicks = 2;
            return;
        }
        retryTicks++;
        BlockPos stand = nearbyStand(bot, pos);
        if (stand != null && retryTicks % 4 == 0) {
            bot.getActionPack().startPathTo(stand);
        }
        placeDelayTicks = 5;
        if (retryTicks > 12) {
            fail("place_failed: " + result.reason() + " at " + compact(pos));
        }
    }

    private static BlockPos nearbyStand(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = pos.offset(direction);
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        BlockPos below = pos.down();
        if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), below)) {
            return below;
        }
        return bot.getBlockPos();
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
