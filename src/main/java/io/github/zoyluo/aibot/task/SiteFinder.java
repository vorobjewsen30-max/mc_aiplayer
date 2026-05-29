package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Optional;
import java.util.OptionalInt;

public final class SiteFinder {
    private static final double MAX_SCORE = 2.0D;

    private SiteFinder() {
    }

    public static Optional<BlockPos> findSite(AIPlayerEntity bot, int footprintX, int footprintZ, int searchRadius) {
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int originSurface = standableY(world, origin.getX(), origin.getZ(), origin.getY()).orElse(origin.getY());
        for (int x = origin.getX() - searchRadius; x <= origin.getX() + searchRadius - footprintX; x++) {
            for (int z = origin.getZ() - searchRadius; z <= origin.getZ() + searchRadius - footprintZ; z++) {
                OptionalInt maybeY = standableY(world, x, z, originSurface);
                if (maybeY.isEmpty()) {
                    continue;
                }
                int y = maybeY.getAsInt();
                BlockPos anchor = new BlockPos(x, y, z);
                if (Math.abs(y - originSurface) > 4) {
                    continue;
                }
                double score = flatnessScore(world, anchor, footprintX, footprintZ);
                if (score > MAX_SCORE) {
                    continue;
                }
                if (!hasUsableStand(world, anchor, footprintX, footprintZ)) {
                    continue;
                }
                double distancePenalty = anchor.getSquaredDistance(origin) / 256.0D;
                double total = score + distancePenalty;
                if (total < bestScore) {
                    bestScore = total;
                    best = anchor.toImmutable();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public static double flatnessScore(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        double sum = 0.0D;
        int count = 0;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                OptionalInt maybeY = standableY(world, x, z, anchor.getY());
                if (maybeY.isEmpty()) {
                    return Double.MAX_VALUE;
                }
                int surfaceY = maybeY.getAsInt();
                BlockPos feet = new BlockPos(x, surfaceY, z);
                if (!world.getBlockState(feet).isAir() || !world.getBlockState(feet.up()).isAir()) {
                    return Double.MAX_VALUE;
                }
                BlockPos ground = feet.down();
                FluidState groundFluid = world.getFluidState(ground);
                FluidState feetFluid = world.getFluidState(feet);
                if (!groundFluid.isEmpty() || !feetFluid.isEmpty() || world.getBlockState(ground).isAir()) {
                    return Double.MAX_VALUE;
                }
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
                sum += surfaceY;
                count++;
            }
        }
        if (count == 0 || maxY - minY > 2) {
            return Double.MAX_VALUE;
        }
        double mean = sum / count;
        double variance = 0.0D;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int surfaceY = standableY(world, anchor.getX() + dx, anchor.getZ() + dz, anchor.getY()).orElse(anchor.getY());
                double delta = surfaceY - mean;
                variance += delta * delta;
            }
        }
        return variance / count + (maxY - minY);
    }

    private static boolean hasUsableStand(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ) {
        Standability.clearCache();
        for (int dx = -1; dx <= footprintX; dx++) {
            if (standableSurface(world, anchor.getX() + dx, anchor.getZ() - 1)
                    || standableSurface(world, anchor.getX() + dx, anchor.getZ() + footprintZ)) {
                return true;
            }
        }
        for (int dz = 0; dz < footprintZ; dz++) {
            if (standableSurface(world, anchor.getX() - 1, anchor.getZ() + dz)
                    || standableSurface(world, anchor.getX() + footprintX, anchor.getZ() + dz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean standableSurface(ServerWorld world, int x, int z) {
        return standableY(world, x, z, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)).isPresent();
    }

    private static OptionalInt standableY(ServerWorld world, int x, int z, int preferredY) {
        int heightmapY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        OptionalInt direct = firstStandable(world, x, z, preferredY, heightmapY, heightmapY + 1, heightmapY - 1);
        if (direct.isPresent()) {
            return direct;
        }
        int minY = Math.max(world.getBottomY() + 1, Math.min(preferredY, heightmapY) - 4);
        int maxY = Math.min(world.getBottomY() + world.getHeight() - 2, Math.max(preferredY, heightmapY) + 4);
        for (int y = minY; y <= maxY; y++) {
            if (Standability.isStandable(world, new BlockPos(x, y, z))) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    private static OptionalInt firstStandable(ServerWorld world, int x, int z, int... ys) {
        for (int y : ys) {
            if (y > world.getBottomY() && y < world.getBottomY() + world.getHeight() - 1
                    && Standability.isStandable(world, new BlockPos(x, y, z))) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }
}
