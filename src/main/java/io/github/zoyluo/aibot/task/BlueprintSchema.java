package io.github.zoyluo.aibot.task;

import java.util.ArrayList;
import java.util.List;

public record BlueprintSchema(
        String name,
        int width,
        int height,
        int depth,
        List<BlockPlacement> placements,
        List<Op> ops
) {
    public record BlockPlacement(int dx, int dy, int dz, String blockId, String palette) {
        public BlockPlacement(int dx, int dy, int dz, String blockId) {
            this(dx, dy, dz, blockId, null);
        }
    }

    public record Op(String type, int[] from, int[] to, String block, String palette) {
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
        return new BlueprintSchema("hut_5x5", 5, 5, 5, List.copyOf(blocks), List.of());
    }

    public static BlueprintSchema smallHutOps() {
        return new BlueprintSchema("small_hut", 5, 5, 5, List.of(
                new BlockPlacement(2, 1, 0, "minecraft:air"),
                new BlockPlacement(2, 2, 0, "minecraft:air")
        ), List.of(
                new Op("layer", new int[]{0, 0, 0}, new int[]{4, 0, 4}, null, "planks"),
                new Op("hollow_box", new int[]{0, 1, 0}, new int[]{4, 3, 4}, null, "planks"),
                new Op("layer", new int[]{0, 4, 0}, new int[]{4, 4, 4}, null, "planks")
        ));
    }
}
