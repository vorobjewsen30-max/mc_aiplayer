package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Standability {
    private static final int CACHE_LIMIT = 4096;
    private static final Map<Long, Boolean> CACHE = new LinkedHashMap<>(CACHE_LIMIT, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private Standability() {
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static boolean isStandable(ServerWorld world, BlockPos pos) {
        long key = pos.asLong();
        Boolean cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = compute(world, pos);
        CACHE.put(key, result);
        return result;
    }

    private static boolean compute(ServerWorld world, BlockPos pos) {
        int topY = world.getBottomY() + world.getHeight();
        if (pos.getY() < world.getBottomY() + 1 || pos.getY() >= topY - 1) {
            return false;
        }

        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());
        if (!feet.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        if (!head.getCollisionShape(world, pos.up()).isEmpty()) {
            return false;
        }
        if (isDangerous(feet) || isDangerous(head) || isDangerous(below)) {
            return false;
        }
        if (below.isAir()) {
            return false;
        }
        return below.getCollisionShape(world, pos.down()).getMax(Direction.Axis.Y) > 0.0D;
    }

    public static boolean isDangerous(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.isIn(FluidTags.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE);
    }
}
