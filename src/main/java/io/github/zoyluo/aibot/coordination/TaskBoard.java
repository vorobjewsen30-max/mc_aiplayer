package io.github.zoyluo.aibot.coordination;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskBoard {
    public static final TaskBoard INSTANCE = new TaskBoard();

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    private TaskBoard() {
    }

    public UUID post(String kind, Map<String, String> params, String role) {
        UUID id = UUID.randomUUID();
        Job job = new Job(id, clean(kind), Map.copyOf(params), clean(role), Job.Status.OPEN, null, "");
        jobs.put(id, job);
        BotLog.task(null, "job_posted", "id", id, "kind", job.kind(), "role", job.role());
        return id;
    }

    public Optional<Job> claimNext(AIPlayerEntity bot, Set<String> roles) {
        for (Job job : snapshot()) {
            if (job.status() != Job.Status.OPEN || !roleMatches(job.role(), roles)) {
                continue;
            }
            UUID id = job.id();
            Job claimed = jobs.compute(id, (ignored, current) -> {
                if (current == null || current.status() != Job.Status.OPEN || !roleMatches(current.role(), roles)) {
                    return current;
                }
                return current.withStatus(Job.Status.CLAIMED, bot.getUuid(), "");
            });
            if (claimed != null && claimed.status() == Job.Status.CLAIMED && bot.getUuid().equals(claimed.claimant())) {
                BotLog.task(bot, "job_claimed", "id", claimed.id(), "kind", claimed.kind(), "role", claimed.role());
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    public void markDone(UUID jobId) {
        jobs.computeIfPresent(jobId, (ignored, job) -> job.withStatus(Job.Status.DONE, job.claimant(), ""));
        BotLog.task(null, "job_done", "id", jobId);
    }

    public void markFailed(UUID jobId, String why) {
        jobs.computeIfPresent(jobId, (ignored, job) -> job.withStatus(Job.Status.FAILED, job.claimant(), why));
        BotLog.task(null, "job_failed", "id", jobId, "reason", why);
    }

    public List<Job> snapshot() {
        List<Job> list = new ArrayList<>(jobs.values());
        list.sort(Comparator.comparing(job -> job.id().toString()));
        return list;
    }

    public void replaceAll(List<Job> loaded) {
        jobs.clear();
        for (Job job : loaded) {
            jobs.put(job.id(), new Job(
                    job.id(),
                    clean(job.kind()),
                    new LinkedHashMap<>(job.params()),
                    clean(job.role()),
                    job.status() == null ? Job.Status.OPEN : job.status(),
                    job.claimant(),
                    job.failureReason() == null ? "" : job.failureReason()));
        }
    }

    public void clear() {
        jobs.clear();
        BotLog.task(null, "jobs_cleared");
    }

    private static boolean roleMatches(String jobRole, Set<String> roles) {
        return jobRole == null || jobRole.isBlank() || roles.contains(jobRole.toLowerCase(java.util.Locale.ROOT));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
