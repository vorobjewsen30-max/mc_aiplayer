package io.github.zoyluo.aibot.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BlueprintLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BlueprintLoader() {
    }

    public static BlueprintSchema load(String name) throws IOException {
        if ("hut_5x5".equals(name)) {
            ensureDefaultHutWritten();
        }
        Path path = blueprintDir().resolve(name + ".json");
        if (!Files.exists(path)) {
            throw new IOException("blueprint_not_found: " + name);
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            BlueprintSchema schema = GSON.fromJson(reader, BlueprintSchema.class);
            if (schema == null || schema.placements() == null || schema.placements().isEmpty()) {
                throw new IOException("blueprint_empty: " + name);
            }
            return schema;
        }
    }

    private static void ensureDefaultHutWritten() throws IOException {
        Path path = blueprintDir().resolve("hut_5x5.json");
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(BlueprintSchema.hut5x5(), writer);
        }
    }

    private static Path blueprintDir() {
        return FabricLoader.getInstance().getGameDir().resolve("blueprints");
    }
}
