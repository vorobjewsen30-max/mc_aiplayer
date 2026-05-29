package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.observe.BotProfiler;
import io.github.zoyluo.aibot.observe.TpsGuard;
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
    private final Map<UUID, FailureRecord> lastFailure = new ConcurrentHashMap<>();

    private TaskManager() {
    }

    public void assign(AIPlayerEntity bot, Task task) {
        abort(bot);
        bot.getActionPack().stopAll();
        active.put(bot.getUuid(), task);
        task.start(bot);
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(bot.getUuid(), status);
        BotReporter.INSTANCE.onAssigned(bot, status);
        BotLog.task(bot, "task_assigned", "name", task.name(), "params", task.describe());
    }

    public void abort(AIPlayerEntity bot) {
        Task current = active.remove(bot.getUuid());
        if (current != null) {
            current.abort(bot);
            lastStatus.put(bot.getUuid(), TaskStatus.from(current));
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.from(current));
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
        TaskStatus status = TaskStatus.from(current);
        lastStatus.put(bot.getUuid(), status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
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
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(bot.getUuid(), status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
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
            AIPlayerEntity player = bot.get();
            if (!isCritical(task) && !TpsGuard.INSTANCE.shouldTickNonCriticalTask(server)) {
                BotProfiler.INSTANCE.record(player, "task_tick_skipped", 0L);
                continue;
            }
            long started = System.nanoTime();
            try {
                task.tick(player);
            } finally {
                BotProfiler.INSTANCE.record(player, "task_tick", System.nanoTime() - started);
            }
            TaskStatus status = TaskStatus.from(task);
            lastStatus.put(uuid, status);
            BotReporter.INSTANCE.onStatus(server, player, status);
            if (task.state() == TaskState.COMPLETED) {
                active.remove(uuid);
                lastFailure.remove(uuid);
                BotLog.task(player, "task_completed", "name", task.name(), "elapsed_ticks", task.elapsedTicks());
            } else if (task.state() == TaskState.FAILED) {
                active.remove(uuid);
                recordFailure(player, task.name(), task.failureReason(), server.getTicks());
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, player, "task_failed",
                        "name", task.name(), "reason", task.failureReason(), "elapsed_ticks", task.elapsedTicks());
            }
        }
    }

    public void recordFailure(AIPlayerEntity bot, String name, String reason, int tick) {
        UUID uuid = bot.getUuid();
        FailureRecord previous = lastFailure.get(uuid);
        int count = previous != null && previous.name().equals(name) && previous.reason().equals(reason)
                ? previous.count() + 1
                : 1;
        lastFailure.put(uuid, new FailureRecord(name, reason, count, tick));
    }

    public Optional<FailureRecord> peekFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(lastFailure.get(bot.getUuid()));
    }

    public Optional<FailureRecord> consumeFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(lastFailure.remove(bot.getUuid()));
    }

    public void onServerStopping(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            abort(bot);
        }
        active.clear();
        paused.clear();
        lastFailure.clear();
        BotLog.task(null, "tasks_cleared");
    }

    public void onBotDespawn(AIPlayerEntity bot) {
        abort(bot);
        paused.remove(bot.getUuid());
        lastStatus.remove(bot.getUuid());
        lastFailure.remove(bot.getUuid());
        BotReporter.INSTANCE.onCleared(bot);
    }

    public int activeCount() {
        return active.size();
    }

    private static boolean isCritical(Task task) {
        return task instanceof EvadeTask || task instanceof CombatTask || task instanceof EatTask;
    }

    public record FailureRecord(String name, String reason, int count, int tick) {
    }
}
