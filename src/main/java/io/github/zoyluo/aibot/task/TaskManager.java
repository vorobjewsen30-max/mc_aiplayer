package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskManager {
    public static final TaskManager INSTANCE = new TaskManager();

    private final Map<UUID, Task> active = new ConcurrentHashMap<>();
    private final Map<UUID, Task> paused = new ConcurrentHashMap<>();
    private final Map<UUID, TaskStatus> lastStatus = new ConcurrentHashMap<>();

    private TaskManager() {
    }

    public void assign(AIPlayerEntity bot, Task task) {
        abort(bot);
        active.put(bot.getUuid(), task);
        task.start(bot);
        lastStatus.put(bot.getUuid(), TaskStatus.from(task));
        BotLog.task(bot, "task_assigned", "name", task.name(), "params", task.describe());
    }

    public void abort(AIPlayerEntity bot) {
        Task current = active.remove(bot.getUuid());
        if (current != null) {
            current.abort(bot);
            lastStatus.put(bot.getUuid(), TaskStatus.from(current));
        }
    }

    public Optional<Task> getActive(AIPlayerEntity bot) {
        return Optional.ofNullable(active.get(bot.getUuid()));
    }

    public boolean hasPaused(AIPlayerEntity bot) {
        return paused.containsKey(bot.getUuid());
    }

    public TaskStatus status(AIPlayerEntity bot) {
        Task current = active.get(bot.getUuid());
        if (current != null) {
            return TaskStatus.from(current);
        }
        Task pausedTask = paused.get(bot.getUuid());
        if (pausedTask != null) {
            return TaskStatus.from(pausedTask);
        }
        return lastStatus.getOrDefault(bot.getUuid(), TaskStatus.idle());
    }

    public void pauseFor(AIPlayerEntity bot, String why) {
        Task current = active.remove(bot.getUuid());
        if (current == null) {
            return;
        }
        current.pause(bot);
        paused.put(bot.getUuid(), current);
        lastStatus.put(bot.getUuid(), TaskStatus.from(current));
        BotLog.task(bot, "task_paused", "name", current.name(), "why", why);
    }

    public void resumeFromPause(AIPlayerEntity bot) {
        if (active.containsKey(bot.getUuid())) {
            return;
        }
        Task task = paused.remove(bot.getUuid());
        if (task == null) {
            return;
        }
        active.put(bot.getUuid(), task);
        task.resume(bot);
        lastStatus.put(bot.getUuid(), TaskStatus.from(task));
        BotLog.task(bot, "task_resumed", "name", task.name());
    }

    public void tickAll(MinecraftServer server) {
        for (Map.Entry<UUID, Task> entry : new ArrayList<>(active.entrySet())) {
            UUID uuid = entry.getKey();
            Task task = entry.getValue();
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(uuid);
            if (bot.isEmpty()) {
                active.remove(uuid);
                paused.remove(uuid);
                continue;
            }
            task.tick(bot.get());
            lastStatus.put(uuid, TaskStatus.from(task));
            if (task.state() == TaskState.COMPLETED) {
                active.remove(uuid);
                BotLog.task(bot.get(), "task_completed", "name", task.name(), "elapsed_ticks", task.elapsedTicks());
            } else if (task.state() == TaskState.FAILED) {
                active.remove(uuid);
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot.get(), "task_failed",
                        "name", task.name(), "reason", task.failureReason(), "elapsed_ticks", task.elapsedTicks());
            }
        }
    }

    public void onServerStopping(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            abort(bot);
        }
        active.clear();
        paused.clear();
        BotLog.task(null, "tasks_cleared");
    }

    public void onBotDespawn(AIPlayerEntity bot) {
        abort(bot);
        paused.remove(bot.getUuid());
        lastStatus.remove(bot.getUuid());
    }

    public int activeCount() {
        return active.size();
    }
}
