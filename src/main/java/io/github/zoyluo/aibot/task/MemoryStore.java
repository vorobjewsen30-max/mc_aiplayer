package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.brain.ChatMessage;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;

import java.util.ArrayList;
import java.util.List;

public final class MemoryStore {
    public static final MemoryStore INSTANCE = new MemoryStore();

    private static final int KEEP_MESSAGES = 12;
    private static final int MAX_MESSAGES = 20;

    private MemoryStore() {
    }

    public List<ChatMessage> prepareHistory(AIPlayerEntity bot, List<ChatMessage> rawHistory) {
        if (rawHistory.size() <= MAX_MESSAGES) {
            return rawHistory;
        }
        List<ChatMessage> compact = new ArrayList<>();
        ChatMessage first = rawHistory.getFirst();
        if ("system".equals(first.role())) {
            compact.add(first);
        }
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        if (!"idle".equals(status.name())) {
            compact.add(ChatMessage.system("Current task: " + status.description()
                    + ". State=" + status.state()
                    + ", progress=" + String.format(java.util.Locale.ROOT, "%.2f", status.progress())));
        }
        int start = Math.max("system".equals(first.role()) ? 1 : 0, rawHistory.size() - KEEP_MESSAGES);
        compact.add(ChatMessage.system("Earlier conversation summary: older messages were compacted locally to keep the context short."));
        compact.addAll(rawHistory.subList(start, rawHistory.size()));
        return compact;
    }
}
