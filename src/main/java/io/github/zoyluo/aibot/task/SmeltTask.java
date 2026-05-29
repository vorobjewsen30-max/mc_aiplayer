package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class SmeltTask extends AbstractTask {
    private enum Phase {
        FINDING_FURNACE,
        WALKING_TO_FURNACE,
        PLACING_FURNACE,
        LOADING,
        SMELTING,
        COLLECTING
    }

    private static final Map<Item, Integer> FUEL_TICKS = new LinkedHashMap<>();

    static {
        FUEL_TICKS.put(Items.COAL, 1600);
        FUEL_TICKS.put(Items.CHARCOAL, 1600);
        FUEL_TICKS.put(Items.OAK_LOG, 300);
        FUEL_TICKS.put(Items.SPRUCE_LOG, 300);
        FUEL_TICKS.put(Items.BIRCH_LOG, 300);
        FUEL_TICKS.put(Items.JUNGLE_LOG, 300);
        FUEL_TICKS.put(Items.ACACIA_LOG, 300);
        FUEL_TICKS.put(Items.DARK_OAK_LOG, 300);
        FUEL_TICKS.put(Items.MANGROVE_LOG, 300);
        FUEL_TICKS.put(Items.CHERRY_LOG, 300);
        FUEL_TICKS.put(Items.OAK_PLANKS, 300);
        FUEL_TICKS.put(Items.SPRUCE_PLANKS, 300);
        FUEL_TICKS.put(Items.BIRCH_PLANKS, 300);
        FUEL_TICKS.put(Items.JUNGLE_PLANKS, 300);
        FUEL_TICKS.put(Items.ACACIA_PLANKS, 300);
        FUEL_TICKS.put(Items.DARK_OAK_PLANKS, 300);
        FUEL_TICKS.put(Items.MANGROVE_PLANKS, 300);
        FUEL_TICKS.put(Items.CHERRY_PLANKS, 300);
        FUEL_TICKS.put(Items.STICK, 100);
    }

    private final Item input;
    private final Item output;
    private final int targetCount;
    private Phase phase = Phase.FINDING_FURNACE;
    private BlockPos furnacePos;
    private int collected;

    public SmeltTask(Item input, Item output, int targetCount) {
        this.input = input;
        this.output = output;
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "smelt";
    }

    @Override
    public String describe() {
        return "Smelting " + Registries.ITEM.getId(input) + " -> " + Registries.ITEM.getId(output)
                + " " + collected + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) collected / targetCount);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FINDING_FURNACE;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 400 + targetCount * 260) {
            fail("smelt_timeout");
            return;
        }
        switch (phase) {
            case FINDING_FURNACE -> findFurnace(bot);
            case WALKING_TO_FURNACE -> walkToFurnace(bot);
            case PLACING_FURNACE -> placeFurnace(bot);
            case LOADING -> loadFurnace(bot);
            case SMELTING -> waitForOutput(bot);
            case COLLECTING -> collectOutput(bot);
        }
    }

    private void findFurnace(AIPlayerEntity bot) {
        if (!InventoryAction.hasItems(bot, input, targetCount)) {
            fail("missing " + Registries.ITEM.getId(input) + " x" + targetCount);
            return;
        }
        furnacePos = nearestFurnace(bot).orElse(null);
        if (furnacePos == null) {
            if (InventoryAction.findItem(bot, Items.FURNACE).isEmpty()) {
                fail("missing minecraft:furnace");
                return;
            }
            phase = Phase.PLACING_FURNACE;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(furnacePos.toCenterPos()) <= 20.25D) {
            phase = Phase.LOADING;
            return;
        }
        BlockPos stand = adjacentStand(bot, furnacePos);
        if (stand == null) {
            fail("no_stand_position_for_furnace");
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        phase = Phase.WALKING_TO_FURNACE;
    }

    private void walkToFurnace(AIPlayerEntity bot) {
        if (furnacePos == null || !bot.getServerWorld().getBlockState(furnacePos).isOf(Blocks.FURNACE)) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(furnacePos.toCenterPos()) <= 20.25D) {
            bot.getActionPack().stopAll();
            phase = Phase.LOADING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            phase = Phase.FINDING_FURNACE;
        }
    }

    private void placeFurnace(AIPlayerEntity bot) {
        OptionalInt furnaceSlot = InventoryAction.findItem(bot, Items.FURNACE);
        if (furnaceSlot.isEmpty()) {
            fail("missing minecraft:furnace");
            return;
        }
        BlockPos pos = adjacentAir(bot);
        if (pos == null) {
            fail("no_place_for_furnace");
            return;
        }
        InventoryAction.equipFromSlot(bot, furnaceSlot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isFailed()) {
            fail("place_furnace_failed: " + result.reason());
            return;
        }
        furnacePos = pos;
        phase = Phase.LOADING;
    }

    private void loadFurnace(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        ItemStack inputSlot = furnace.getStack(0);
        if (!inputSlot.isEmpty() && !inputSlot.isOf(input)) {
            fail("furnace_input_occupied: " + Registries.ITEM.getId(inputSlot.getItem()));
            return;
        }
        int remaining = targetCount - collected;
        int inputToLoad = Math.min(remaining, inputSlot.isEmpty() ? 64 : 64 - inputSlot.getCount());
        if (inputToLoad <= 0) {
            fail("furnace_input_full");
            return;
        }
        ItemStack fuelSlot = furnace.getStack(1);
        FuelChoice fuel = null;
        if (fuelSlot.isEmpty()) {
            fuel = chooseFuel(bot, inputToLoad);
            if (fuel == null) {
                fail("missing_fuel");
                return;
            }
        }
        if (!InventoryAction.removeItems(bot, input, inputToLoad)) {
            fail("missing " + Registries.ITEM.getId(input) + " x" + inputToLoad);
            return;
        }
        furnace.setStack(0, new ItemStack(input, inputSlot.getCount() + inputToLoad));

        if (fuel != null) {
            if (!InventoryAction.removeItems(bot, fuel.item(), fuel.count())) {
                fail("missing_fuel: " + Registries.ITEM.getId(fuel.item()));
                return;
            }
            furnace.setStack(1, new ItemStack(fuel.item(), fuel.count()));
        }
        furnace.markDirty();
        phase = Phase.SMELTING;
    }

    private void waitForOutput(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            fail("furnace_missing");
            return;
        }
        ItemStack outputSlot = furnace.getStack(2);
        if (!outputSlot.isEmpty() && !outputSlot.isOf(output)) {
            fail("unexpected_output: " + Registries.ITEM.getId(outputSlot.getItem()));
            return;
        }
        if (!outputSlot.isEmpty()) {
            phase = Phase.COLLECTING;
        }
    }

    private void collectOutput(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            fail("furnace_missing");
            return;
        }
        ItemStack outputSlot = furnace.getStack(2);
        if (outputSlot.isEmpty()) {
            phase = Phase.SMELTING;
            return;
        }
        if (!outputSlot.isOf(output)) {
            fail("unexpected_output: " + Registries.ITEM.getId(outputSlot.getItem()));
            return;
        }
        int take = Math.min(targetCount - collected, outputSlot.getCount());
        ActionResult result = InventoryAction.giveItem(bot, new ItemStack(output, take));
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        outputSlot.decrement(take);
        furnace.markDirty();
        collected += take;
        if (collected >= targetCount) {
            complete();
        } else {
            phase = Phase.SMELTING;
        }
    }

    private AbstractFurnaceBlockEntity furnace(AIPlayerEntity bot) {
        if (furnacePos == null) {
            return null;
        }
        return bot.getServerWorld().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }

    private static Optional<BlockPos> nearestFurnace(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(origin.add(-10, -3, -10), origin.add(10, 4, 10))
                .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(Blocks.FURNACE))
                .map(BlockPos::toImmutable)
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin)));
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = pos.offset(direction);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos adjacentAir(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = origin.offset(direction);
            if (bot.getServerWorld().getBlockState(candidate).isAir()) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private static FuelChoice chooseFuel(AIPlayerEntity bot, int smeltCount) {
        int ticksNeeded = smeltCount * 200;
        for (Map.Entry<Item, Integer> entry : FUEL_TICKS.entrySet()) {
            int available = InventoryAction.countItem(bot, entry.getKey());
            int needed = divideRoundUp(ticksNeeded, entry.getValue());
            if (available < needed) {
                continue;
            }
            return new FuelChoice(entry.getKey(), needed);
        }
        return null;
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private record FuelChoice(Item item, int count) {
    }
}
