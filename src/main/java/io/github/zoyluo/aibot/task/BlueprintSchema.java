package io.github.zoyluo.aibot.task;

import java.util.ArrayList;
import java.util.List;

public record BlueprintSchema(
        String name,
        int width,
        int height,
        int depth,
        List<BlockPlacement> placements
) {
    public record BlockPlacement(int dx, int dy, int dz, String blockId) {
    }

    public static BlueprintSchema hut5x5() {
        List<BlockPlacement> blocks = new ArrayList<>();
        String plank = "minecraft:oak_planks";
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                blocks.add(new BlockPlacement(x, 0, z, plank));
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < 5; x++) {
                blocks.add(new BlockPlacement(x, y, 0, plank));
                blocks.add(new BlockPlacement(x, y, 4, plank));
            }
            for (int z = 1; z < 4; z++) {
                blocks.add(new BlockPlacement(0, y, z, plank));
                blocks.add(new BlockPlacement(4, y, z, plank));
            }
        }
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                blocks.add(new BlockPlacement(x, 4, z, plank));
            }
        }
        blocks.removeIf(block -> block.dx() == 2 && block.dz() == 0 && (block.dy() == 1 || block.dy() == 2));
        return new BlueprintSchema("hut_5x5", 5, 5, 5, List.copyOf(blocks));
    }
}
