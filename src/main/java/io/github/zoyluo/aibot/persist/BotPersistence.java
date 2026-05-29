package io.github.zoyluo.aibot.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zoyluo.aibot.coordination.Job;
import io.github.zoyluo.aibot.coordination.TaskBoard;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BotPersistence {
    public static final BotPersistence INSTANCE = new BotPersistence();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String INVENTORY_KEY = "Inventory";

    private BotPersistence() {
    }

    public int saveAll(MinecraftServer server) {
        List<BotRecord> records = captureAll();
        write(file(server), records);
        return records.size();
    }

    public void saveAllAsync(MinecraftServer server) {
        Path file = file(server);
        List<BotRecord> records = captureAll();
        CompletableFuture.runAsync(() -> write(file, records));
    }

    public List<BotRecord> load(MinecraftServer server) {
        Path file = file(server);
        if (!Files.exists(file)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            BotRecord[] records = GSON.fromJson(reader, BotRecord[].class);
            if (records == null || records.length == 0) {
                return List.of();
            }
            return List.of(records);
        } catch (IOException | RuntimeException exception) {
            BotLog.error("bot_persist_load_failed", exception, "path", file);
            return List.of();
        }
    }

    public int loadAndRespawn(MinecraftServer server) {
        int restored = 0;
        for (BotRecord record : load(server)) {
            if (AIPlayerManager.INSTANCE.respawnFromRecord(server, record).isPresent()) {
                restored++;
            }
        }
        loadJobs(server);
        return restored;
    }

    public static BotRecord capture(AIPlayerEntity bot) {
        return new BotRecord(
                bot.getGameProfile().getName(),
                bot.getWorld().getRegistryKey().getValue().toString(),
                bot.getX(),
                bot.getY(),
                bot.getZ(),
                bot.getYaw(),
                bot.getPitch(),
                bot.interactionManager.getGameMode().getName(),
                bot.getHealth(),
                bot.getHungerManager().getFoodLevel(),
                encodeInventory(bot),
                AIPlayerManager.INSTANCE.role(bot),
                BotMemoryStore.INSTANCE.saveString(bot.getUuid()));
    }

    public static String encodeInventory(ServerPlayerEntity player) {
        NbtCompound root = new NbtCompound();
        root.put(INVENTORY_KEY, player.getInventory().writeNbt(new NbtList()));
        return root.toString();
    }

    public static void applyInventory(ServerPlayerEntity player, String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return;
        }
        try {
            NbtCompound root = StringNbtReader.parse(snbt);
            NbtList inventory = root.getList(INVENTORY_KEY, NbtElement.COMPOUND_TYPE);
            PlayerInventory playerInventory = player.getInventory();
            playerInventory.readNbt(inventory);
            playerInventory.markDirty();
        } catch (Exception exception) {
            BotLog.error(player instanceof AIPlayerEntity bot ? bot : null, "bot_inventory_restore_failed", exception);
        }
    }

    private List<BotRecord> captureAll() {
        List<BotRecord> records = new ArrayList<>();
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            records.add(capture(bot));
        }
        return records;
    }

    private void write(Path file, List<BotRecord> records) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(records, writer);
            }
            BotLog.lifecycle("bot_persist_saved", "count", records.size(), "path", file);
            writeJobs(file.getParent().resolve("jobs.json"));
        } catch (IOException | RuntimeException exception) {
            BotLog.error("bot_persist_save_failed", exception, "path", file, "count", records.size());
        }
    }

    private void writeJobs(Path file) {
        List<Job> jobs = TaskBoard.INSTANCE.snapshot();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(jobs, writer);
            }
            BotLog.task(null, "jobs_persist_saved", "count", jobs.size(), "path", file);
        } catch (IOException | RuntimeException exception) {
            BotLog.error("jobs_persist_save_failed", exception, "path", file, "count", jobs.size());
        }
    }

    private void loadJobs(MinecraftServer server) {
        Path file = file(server).getParent().resolve("jobs.json");
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Job[] jobs = GSON.fromJson(reader, Job[].class);
            TaskBoard.INSTANCE.replaceAll(jobs == null ? List.of() : List.of(jobs));
            BotLog.task(null, "jobs_persist_loaded", "count", jobs == null ? 0 : jobs.length, "path", file);
        } catch (IOException | RuntimeException exception) {
            BotLog.error("jobs_persist_load_failed", exception, "path", file);
        }
    }

    private Path file(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("aibot").resolve("bots.json");
    }
}
