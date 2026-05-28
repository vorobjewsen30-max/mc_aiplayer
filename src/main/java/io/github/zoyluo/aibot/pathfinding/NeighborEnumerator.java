package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public final class NeighborEnumerator {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };

    public List<NeighborCandidate> getNeighbors(BlockPos current, ServerWorld world) {
        List<NeighborCandidate> result = new ArrayList<>(HORIZONTAL.length);
        for (Direction direction : HORIZONTAL) {
            BlockPos target = current.offset(direction);
            if (Standability.isStandable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.WALK, 0));
                continue;
            }

            BlockPos jumpTarget = target.up();
            if (canJumpFrom(world, current) && Standability.isStandable(world, jumpTarget)) {
                result.add(new NeighborCandidate(jumpTarget, MoveType.JUMP_UP, 0));
                continue;
            }

            NeighborCandidate drop = findDrop(world, target);
            if (drop != null) {
                result.add(drop);
                continue;
            }

            if (isMineable(world, target) && hasHeadroom(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.DIG_THROUGH, 0));
            }
        }
        return result;
    }

    private static boolean canJumpFrom(ServerWorld world, BlockPos current) {
        return world.getBlockState(current.up()).getCollisionShape(world, current.up()).isEmpty()
                && world.getBlockState(current.up(2)).getCollisionShape(world, current.up(2)).isEmpty();
    }

    private static NeighborCandidate findDrop(ServerWorld world, BlockPos target) {
        if (!world.getBlockState(target).getCollisionShape(world, target).isEmpty()) {
            return null;
        }
        if (!world.getBlockState(target.up()).getCollisionShape(world, target.up()).isEmpty()) {
            return null;
        }
        for (int fall = 1; fall <= 3; fall++) {
            BlockPos landing = target.down(fall);
            if (Standability.isStandable(world, landing)) {
                return new NeighborCandidate(landing, MoveType.DROP_DOWN, fall);
            }
            if (!world.getBlockState(landing).getCollisionShape(world, landing).isEmpty()) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasHeadroom(ServerWorld world, BlockPos target) {
        return world.getBlockState(target.up()).getCollisionShape(world, target.up()).isEmpty();
    }

    private static boolean isMineable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getHardness(world, pos) < 0.0F || world.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            return false;
        }
        return state.isIn(BlockTags.STONE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DIRT)
                || state.isOf(Blocks.STONE)
                || state.isOf(Blocks.COBBLESTONE)
                || state.isOf(Blocks.GRANITE)
                || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.ANDESITE)
                || state.isOf(Blocks.SAND)
                || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.GRAVEL);
    }
}
