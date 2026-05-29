package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.brain.ChatMessage;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;

import java.util.ArrayList;
import java.util.List;

public final class MemoryStore {
    public static final MemoryStore INSTANCE = new MemoryStore();

    private static final int KEEP_MESSAGES = 12;
    private static final int MAX_MESSAGES = 20;

    private MemoryStore() {
    }

    public List<ChatMessage> prepareHistory(AIPlayerEntity bot, List<ChatMessage> rawHistory) {
        List<ChatMessage> withMemory = injectPersistentMemory(bot, rawHistory);
        if (withMemory.size() <= MAX_MESSAGES) {
            return withMemory;
        }
        List<ChatMessage> compact = new ArrayList<>();
        ChatMessage first = withMemory.getFirst();
        if ("system".equals(first.role())) {
            compact.add(first);
        }
        String memory = BotMemoryStore.INSTANCE.of(bot.getUuid()).inject();
        if (!memory.isBlank()) {
            compact.add(ChatMessage.system("Persistent memory:\n" + memory));
        }
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        if (!"idle".equals(status.name())) {
            compact.add(ChatMessage.system("Current task: " + status.description()
                    + ". State=" + status.state()
                    + ", progress=" + String.format(java.util.Locale.ROOT, "%.2f", status.progress())));
        }
        int start = Math.max("system".equals(first.role()) ? 1 : 0, withMemory.size() - KEEP_MESSAGES);
        compact.add(ChatMessage.system("Earlier conversation summary: older messages were compacted locally to keep the context short."));
        compact.addAll(withMemory.subList(start, withMemory.size()));
        return compact;
    }

    private List<ChatMessage> injectPersistentMemory(AIPlayerEntity bot, List<ChatMessage> rawHistory) {
        String memory = BotMemoryStore.INSTANCE.of(bot.getUuid()).inject();
        if (memory.isBlank()) {
            return rawHistory;
        }
        List<ChatMessage> result = new ArrayList<>(rawHistory.size() + 1);
        if (!rawHistory.isEmpty() && "system".equals(rawHistory.getFirst().role())) {
            result.add(rawHistory.getFirst());
            result.add(ChatMessage.system("Persistent memory:\n" + memory));
            result.addAll(rawHistory.subList(1, rawHistory.size()));
        } else {
            result.add(ChatMessage.system("Persistent memory:\n" + memory));
            result.addAll(rawHistory);
        }
        return result;
    }
}
