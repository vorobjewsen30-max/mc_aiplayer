package io.github.zoyluo.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public record AIBotConfig(
        DeepSeek deepseek,
        Perception perception,
        Brain brain,
        Logging logging
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AIBotConfig instance = defaults();

    public static AIBotConfig get() {
        return instance;
    }

    public static AIBotConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("aibot.json");
        AIBotConfig loaded = defaults();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                AIBotConfig parsed = GSON.fromJson(reader, AIBotConfig.class);
                if (parsed != null) {
                    loaded = parsed.withDefaults();
                }
            } catch (IOException exception) {
                BotLog.error("config_read_failed", exception, "path", path);
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(loaded, writer);
                }
                BotLog.config("config_template_written", "path", path);
            } catch (IOException exception) {
                BotLog.error("config_write_failed", exception, "path", path);
            }
        }

        String envKey = System.getenv("DEEPSEEK_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            loaded = loaded.withDeepSeek(loaded.deepseek().withApiKey(envKey));
        }
        if (loaded.deepseek().apiKey().isBlank()) {
            BotLog.warn(LogCategory.CONFIG, null, "deepseek_key_missing");
        }
        instance = loaded;
        return loaded;
    }

    public AIBotConfig withDeepSeek(DeepSeek deepseek) {
        return new AIBotConfig(deepseek, perception(), brain(), logging());
    }

    private AIBotConfig withDefaults() {
        AIBotConfig defaults = defaults();
        return new AIBotConfig(
                deepseek == null ? defaults.deepseek : deepseek.withDefaults(defaults.deepseek),
                perception == null ? defaults.perception : perception.withDefaults(defaults.perception),
                brain == null ? defaults.brain : brain.withDefaults(defaults.brain),
                logging == null ? defaults.logging : logging.withDefaults(defaults.logging));
    }

    public static AIBotConfig defaults() {
        return new AIBotConfig(
                new DeepSeek("", "https://api.deepseek.com", "deepseek-chat", 2048, 0.3D, 60, 3, 500),
                new Perception(16, 20, 10, 10),
                new Brain(20, 6, 5),
                new Logging(true, "logs/aibot", true, "daily", 50, 30, true, Map.of(
                        "LIFECYCLE", "INFO",
                        "COMM", "INFO",
                        "API", "INFO",
                        "ACTION", "INFO",
                        "PERCEPTION", "DEBUG",
                        "PATH", "DEBUG",
                        "TASK", "INFO",
                        "DANGER", "INFO",
                        "ERROR", "ERROR",
                        "CONFIG", "INFO")));
    }

    public record DeepSeek(
            String apiKey,
            String baseUrl,
            String model,
            int maxTokens,
            double temperature,
            int timeoutSeconds,
            int retryCount,
            int retryBackoffMs
    ) {
        DeepSeek withApiKey(String apiKey) {
            return new DeepSeek(apiKey, baseUrl, model, maxTokens, temperature, timeoutSeconds, retryCount, retryBackoffMs);
        }

        DeepSeek withDefaults(DeepSeek defaults) {
            return new DeepSeek(
                    apiKey == null ? defaults.apiKey : apiKey,
                    blankToDefault(baseUrl, defaults.baseUrl),
                    blankToDefault(model, defaults.model),
                    positiveOrDefault(maxTokens, defaults.maxTokens),
                    temperature,
                    positiveOrDefault(timeoutSeconds, defaults.timeoutSeconds),
                    Math.max(0, retryCount),
                    positiveOrDefault(retryBackoffMs, defaults.retryBackoffMs));
        }
    }

    public record Perception(int radius, int maxBlocks, int maxEntities, int maxItems) {
        Perception withDefaults(Perception defaults) {
            return new Perception(
                    positiveOrDefault(radius, defaults.radius),
                    positiveOrDefault(maxBlocks, defaults.maxBlocks),
                    positiveOrDefault(maxEntities, defaults.maxEntities),
                    positiveOrDefault(maxItems, defaults.maxItems));
        }
    }

    public record Brain(int maxHistoryMessages, int maxToolCallsPerTurn, int maxTurnsPerRequest) {
        Brain withDefaults(Brain defaults) {
            return new Brain(
                    positiveOrDefault(maxHistoryMessages, defaults.maxHistoryMessages),
                    positiveOrDefault(maxToolCallsPerTurn, defaults.maxToolCallsPerTurn),
                    positiveOrDefault(maxTurnsPerRequest, defaults.maxTurnsPerRequest));
        }
    }

    public record Logging(
            boolean enabled,
            String directory,
            boolean perBotFile,
            String rotation,
            int maxFileSizeMb,
            int maxBackups,
            boolean mirrorToSlf4j,
            Map<String, String> categories
    ) {
        Logging withDefaults(Logging defaults) {
            return new Logging(
                    enabled,
                    blankToDefault(directory, defaults.directory),
                    perBotFile,
                    blankToDefault(rotation, defaults.rotation),
                    positiveOrDefault(maxFileSizeMb, defaults.maxFileSizeMb),
                    positiveOrDefault(maxBackups, defaults.maxBackups),
                    mirrorToSlf4j,
                    categories == null || categories.isEmpty() ? defaults.categories : categories);
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }
}
