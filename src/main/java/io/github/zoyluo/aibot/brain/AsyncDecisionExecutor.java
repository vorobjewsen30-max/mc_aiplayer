package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.observe.BotProfiler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class AsyncDecisionExecutor {
    private final DeepSeekApiClient apiClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public AsyncDecisionExecutor(DeepSeekApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void submit(AIPlayerEntity bot,
                       List<ChatMessage> historySnapshot,
                       List<ToolDefinition> tools,
                       BiConsumer<AIPlayerEntity, ChatResponse> onResponse,
                       BiConsumer<AIPlayerEntity, Throwable> onError) {
        executor.submit(() -> {
            long started = System.nanoTime();
            try {
                ChatResponse response = apiClient.chat(historySnapshot, tools);
                long elapsed = System.nanoTime() - started;
                bot.getServer().execute(() -> onResponse.accept(bot, response));
                BotProfiler.INSTANCE.record(bot.getUuid(), bot.getGameProfile().getName(), "brain_latency", elapsed);
            } catch (Exception exception) {
                long elapsed = System.nanoTime() - started;
                BotProfiler.INSTANCE.record(bot.getUuid(), bot.getGameProfile().getName(), "brain_latency_error", elapsed);
                bot.getServer().execute(() -> onError.accept(bot, exception));
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
