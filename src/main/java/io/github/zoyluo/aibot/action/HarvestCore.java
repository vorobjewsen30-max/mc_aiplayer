package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class HarvestCore {
    private HarvestCore() {
    }

    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Block targetBlock, int horizontalRadius, int down, int up) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(origin.add(-horizontalRadius, -down, -horizontalRadius), origin.add(horizontalRadius, up, horizontalRadius))
                .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(targetBlock))
                .map(BlockPos::toImmutable)
                .map(pos -> targetChoice(bot, pos))
                .filter(choice -> choice != null)
                .min(Comparator.comparingDouble(choice -> choice.pos().getSquaredDistance(origin)))
                .orElse(null);
    }

    public static void startMining(AIPlayerEntity bot, BlockPos targetPos) {
        ToolSelector.equipBestTool(bot, bot.getServerWorld().getBlockState(targetPos));
        MiningAction.startMining(bot, targetPos, Direction.getFacing(bot.getEyePos().subtract(targetPos.toCenterPos())));
    }

    public static Optional<ItemEntity> nearestDrop(AIPlayerEntity bot, Item item, double radius) {
        return bot.getServerWorld()
                .getEntitiesByClass(ItemEntity.class, bot.getBoundingBox().expand(radius),
                        entity -> !entity.getStack().isEmpty() && (item == null || entity.getStack().isOf(item)))
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
    }

    public static boolean forcePickupNearby(AIPlayerEntity bot, Item item, double maxH, double maxV) {
        Box box = bot.getBoundingBox().expand(maxH, maxV, maxH);
        List<ItemEntity> drops = bot.getServerWorld().getEntitiesByClass(ItemEntity.class, box,
                entity -> !entity.getStack().isEmpty()
                        && (item == null || entity.getStack().isOf(item))
                        && canForcePickup(bot, entity, maxH, maxV));
        boolean picked = false;
        for (ItemEntity drop : drops) {
            ItemStack remaining = drop.getStack().copy();
            int before = remaining.getCount();
            ActionResult result = InventoryAction.giveItem(bot, remaining);
            int inserted = before - remaining.getCount();
            if (inserted <= 0) {
                continue;
            }
            picked = true;
            BotLog.action(bot, "pickup_forced",
                    "item", drop.getStack().getItem(),
                    "count", inserted,
                    "result", result.isSuccess() ? "all" : result.reason());
            if (remaining.isEmpty()) {
                drop.discard();
            } else {
                drop.setStack(remaining);
            }
        }
        return picked;
    }

    public static void chaseDrop(AIPlayerEntity bot, Item item, double radius) {
        if (forcePickupNearby(bot, item, 1.5D, 1.0D)) {
            bot.getActionPack().stopMovement();
            return;
        }
        nearestDrop(bot, item, radius).ifPresent(drop -> {
            if (bot.distanceTo(drop) > 1.3F) {
                if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                    ActionResult result = bot.getActionPack().startPathTo(pickupStandPos(bot, drop.getBlockPos()));
                    if (result.isFailed()) {
                        bot.getActionPack().startWalkTo(drop.getPos());
                    }
                }
            } else {
                bot.getActionPack().stopMovement();
            }
        });
    }

    public static int sweepPickup(AIPlayerEntity bot, Item item, double radius, int maxTargets) {
        int picked = 0;
        for (int i = 0; i < maxTargets; i++) {
            if (!forcePickupNearby(bot, item, 1.5D, 1.0D)) {
                break;
            }
            picked++;
        }
        if (picked > 0) {
            return picked;
        }
        nearestDrop(bot, item, radius).ifPresent(drop -> {
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                ActionResult result = bot.getActionPack().startPathTo(pickupStandPos(bot, drop.getBlockPos()));
                if (result.isFailed()) {
                    bot.getActionPack().startWalkTo(drop.getPos());
                }
            }
        });
        return 0;
    }

    public static int totalInventoryCount(AIPlayerEntity bot) {
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

    public static boolean isInventoryFull(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().main) {
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static BlockPos pickupStandPos(AIPlayerEntity bot, BlockPos itemPos) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos[] candidates = {
                itemPos,
                itemPos.north(),
                itemPos.south(),
                itemPos.east(),
                itemPos.west()
        };
        for (BlockPos candidate : candidates) {
            if (!Standability.isStandable(bot.getServerWorld(), candidate)) {
                continue;
            }
            double distance = candidate.getSquaredDistance(bot.getBlockPos());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? itemPos : best;
    }

    public static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return bot.getEyePos().distanceTo(target.toCenterPos()) <= 4.5D;
    }

    public static boolean canDirectMine(AIPlayerEntity bot, BlockPos target) {
        // 允许挖脚下/低处方块(够得着即可)——地表往下挖石头的核心。
        return canReach(bot, target);
    }

    public static TargetChoice targetChoice(AIPlayerEntity bot, BlockPos target) {
        // 不再因方块低于自己而拒绝:够得着就直接挖(挖脚下→下落→继续往下挖,掉落物随之落到脚边便于拾取)。
        if (canDirectMine(bot, target)) {
            return new TargetChoice(target, null, true);
        }
        BlockPos stand = adjacentStandPos(bot, target);
        if (stand == null) {
            return null;
        }
        return new TargetChoice(target, stand, false);
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canForcePickup(AIPlayerEntity bot, ItemEntity drop, double maxH, double maxV) {
        if (drop.cannotPickup()) {
            return false;
        }
        double dx = drop.getX() - bot.getX();
        double dz = drop.getZ() - bot.getZ();
        return dx * dx + dz * dz <= maxH * maxH && Math.abs(drop.getY() - bot.getY()) <= maxV;
    }

    public record TargetChoice(BlockPos pos, BlockPos stand, boolean direct) {
    }
}
