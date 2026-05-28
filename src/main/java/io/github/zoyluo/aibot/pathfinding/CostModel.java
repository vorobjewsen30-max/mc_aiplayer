package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.util.math.BlockPos;

public final class CostModel {
    private CostModel() {
    }

    public static double stepCost(MoveType type, int fallHeight) {
        return switch (type) {
            case WALK -> 1.0D;
            case JUMP_UP -> 1.5D;
            case DROP_DOWN -> 0.5D + 0.3D * fallHeight;
            case DIG_THROUGH -> 8.0D;
        };
    }

    public static double heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dy = Math.abs(from.getY() - to.getY());
        int dz = Math.abs(from.getZ() - to.getZ());
        return dx + dz + 1.5D * dy;
    }
}
