package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.OptionalInt;

public final class FarmAction {
    private FarmAction() {
    }

    public static ActionResult till(AIPlayerEntity bot, BlockPos ground) {
        ServerWorld world = bot.getServerWorld();
        if (!isTillable(world.getBlockState(ground)) || !world.getBlockState(ground.up()).isAir()) {
            return ActionResult.failed("not_tillable");
        }
        OptionalInt hoeSlot = findHoeSlot(bot);
        if (hoeSlot.isEmpty()) {
            return ActionResult.failed("missing_hoe");
        }
        InventoryAction.equipFromSlot(bot, hoeSlot.getAsInt());
        world.setBlockState(ground, Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
        BotLog.action(bot, "till", "pos", ground);
        return ActionResult.SUCCESS;
    }

    public static ActionResult plant(AIPlayerEntity bot, BlockPos farmland, Item seed, Block crop) {
        ServerWorld world = bot.getServerWorld();
        if (!world.getBlockState(farmland).isOf(Blocks.FARMLAND) || !world.getBlockState(farmland.up()).isAir()) {
            return ActionResult.failed("not_empty_farmland");
        }
        if (!InventoryAction.removeItems(bot, seed, 1)) {
            return ActionResult.failed("missing " + seed + " x1");
        }
        world.setBlockState(farmland.up(), crop.getDefaultState(), Block.NOTIFY_ALL);
        BotLog.action(bot, "plant", "pos", farmland.up(), "seed", seed, "crop", crop);
        return ActionResult.SUCCESS;
    }

    public static boolean isMature(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMature(state);
    }

    public static ActionResult harvest(AIPlayerEntity bot, BlockPos cropPos) {
        ServerWorld world = bot.getServerWorld();
        if (!isMature(world, cropPos)) {
            return ActionResult.failed("not_mature");
        }
        world.breakBlock(cropPos, true, bot);
        BotLog.action(bot, "harvest", "pos", cropPos);
        return ActionResult.SUCCESS;
    }

    public static CropSpec cropSpec(String cropName) {
        return switch (cropName) {
            case "wheat", "minecraft:wheat" -> new CropSpec(Items.WHEAT_SEEDS, Blocks.WHEAT, "wheat");
            case "carrot", "carrots", "minecraft:carrot", "minecraft:carrots" -> new CropSpec(Items.CARROT, Blocks.CARROTS, "carrot");
            case "potato", "potatoes", "minecraft:potato", "minecraft:potatoes" -> new CropSpec(Items.POTATO, Blocks.POTATOES, "potato");
            default -> throw new IllegalArgumentException("unknown_crop: " + cropName);
        };
    }

    public static boolean isSupportedCrop(Block block) {
        return block == Blocks.WHEAT || block == Blocks.CARROTS || block == Blocks.POTATOES;
    }

    public static Item seedFor(Block crop) {
        if (crop == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (crop == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (crop == Blocks.POTATOES) {
            return Items.POTATO;
        }
        throw new IllegalArgumentException("unknown_crop_block: " + crop);
    }

    public static boolean isTillable(BlockState state) {
        return state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT);
    }

    private static OptionalInt findHoeSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.getItem() instanceof HoeItem) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public record CropSpec(Item seed, Block crop, String name) {
    }
}
