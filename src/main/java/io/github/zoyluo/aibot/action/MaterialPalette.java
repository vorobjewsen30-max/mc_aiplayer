package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.block.BlockState;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

public final class MaterialPalette {
    public static final Map<String, List<Item>> GROUPS = Map.of(
            "planks", RecipeRegistry.PLANKS,
            "logs", RecipeRegistry.LOGS,
            "stone_like", List.of(Items.COBBLESTONE, Items.STONE, Items.STONE_BRICKS, Items.COBBLED_DEEPSLATE, Items.DEEPSLATE_BRICKS),
            "dirt_like", List.of(Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT),
            "glass", List.of(Items.GLASS, Items.WHITE_STAINED_GLASS, Items.LIGHT_GRAY_STAINED_GLASS));

    private MaterialPalette() {
    }

    public static OptionalInt pickSlot(AIPlayerEntity bot, String palette) {
        if (palette == null || palette.isBlank()) {
            return OptionalInt.empty();
        }
        List<Item> items = GROUPS.get(palette);
        if (items == null || items.isEmpty()) {
            return OptionalInt.empty();
        }
        for (Item item : items) {
            OptionalInt slot = InventoryAction.findItem(bot, item);
            if (slot.isPresent()) {
                return slot;
            }
        }
        return OptionalInt.empty();
    }

    public static OptionalInt pickAnyBlockSlot(AIPlayerEntity bot) {
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            if (bot.getInventory().main.get(slot).getItem() instanceof BlockItem) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public static boolean isKnown(String palette) {
        return palette != null && GROUPS.containsKey(palette);
    }

    public static boolean matchesBlock(BlockState state, String palette) {
        List<Item> items = GROUPS.get(palette);
        if (items == null) {
            return false;
        }
        for (Item item : items) {
            if (item instanceof BlockItem blockItem && state.isOf(blockItem.getBlock())) {
                return true;
            }
        }
        return false;
    }
}
