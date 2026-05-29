package io.github.zoyluo.aibot.mining;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OreScan {
    public static final Set<Block> COMMON_ORES = Set.of(
            Blocks.COAL_ORE,
            Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE,
            Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE,
            Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.GOLD_ORE,
            Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE,
            Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE);

    private OreScan() {
    }

    public static List<BlockPos> veinFrom(World world, BlockPos seed, Set<Block> ores, int cap) {
        BlockState seedState = world.getBlockState(seed);
        if (!isOre(seedState, ores)) {
            return List.of();
        }
        Block seedBlock = seedState.getBlock();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> result = new ArrayList<>();
        open.add(seed.toImmutable());
        seen.add(seed.toImmutable());
        while (!open.isEmpty() && result.size() < cap) {
            BlockPos current = open.removeFirst();
            if (!world.getBlockState(current).isOf(seedBlock)) {
                continue;
            }
            result.add(current);
            for (Direction direction : Direction.values()) {
                BlockPos next = current.offset(direction).toImmutable();
                if (!seen.add(next)) {
                    continue;
                }
                if (world.getBlockState(next).isOf(seedBlock)) {
                    open.addLast(next);
                }
            }
        }
        return result;
    }

    public static boolean isOre(BlockState state, Set<Block> ores) {
        return ores.contains(state.getBlock());
    }

    public static boolean adjacentHazard(World world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.offset(direction);
            FluidState fluid = world.getFluidState(adjacent);
            if (fluid.isIn(FluidTags.LAVA) || fluid.isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }
}
