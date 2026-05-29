package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.coordination.Job;
import io.github.zoyluo.aibot.coordination.TaskBoard;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotJobSubcommand {
    private AIBotJobSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("job")
                .then(literal("post")
                        .then(argument("kind", StringArgumentType.word())
                                .then(argument("role", StringArgumentType.word())
                                        .executes(context -> post(context.getSource(),
                                                StringArgumentType.getString(context, "kind"),
                                                StringArgumentType.getString(context, "role"),
                                                ""))
                                        .then(argument("params", StringArgumentType.greedyString())
                                                .executes(context -> post(context.getSource(),
                                                        StringArgumentType.getString(context, "kind"),
                                                        StringArgumentType.getString(context, "role"),
                                                        StringArgumentType.getString(context, "params")))))))
                .then(literal("list")
                        .executes(context -> list(context.getSource())))
                .then(literal("tell")
                        .then(argument("from_bot", StringArgumentType.word())
                                .then(argument("target_bot", StringArgumentType.word())
                                        .then(argument("message", StringArgumentType.greedyString())
                                                .executes(context -> tell(context.getSource(),
                                                        StringArgumentType.getString(context, "from_bot"),
                                                        StringArgumentType.getString(context, "target_bot"),
                                                        StringArgumentType.getString(context, "message")))))))
                .then(literal("clear")
                        .executes(context -> clear(context.getSource())));
    }

    private static int post(ServerCommandSource source, String kind, String role, String paramsText) {
        UUID id = TaskBoard.INSTANCE.post(kind, parseParams(paramsText), role);
        source.sendFeedback(() -> Text.literal("[AIBot] job posted " + id + " kind=" + kind + " role=" + role), false);
        return 1;
    }

    private static int list(ServerCommandSource source) {
        var jobs = TaskBoard.INSTANCE.snapshot();
        if (jobs.isEmpty()) {
            source.sendFeedback(() -> Text.literal("[AIBot] jobs: empty"), false);
            return 0;
        }
        String text = jobs.stream()
                .map(AIBotJobSubcommand::format)
                .collect(Collectors.joining(" | "));
        source.sendFeedback(() -> Text.literal("[AIBot] jobs: " + text), false);
        return jobs.size();
    }

    private static int clear(ServerCommandSource source) {
        TaskBoard.INSTANCE.clear();
        source.sendFeedback(() -> Text.literal("[AIBot] jobs cleared"), false);
        return 1;
    }

    private static int tell(ServerCommandSource source, String fromBot, String targetBot, String message) {
        var from = AIPlayerManager.INSTANCE.getByName(fromBot);
        var target = AIPlayerManager.INSTANCE.getByName(targetBot);
        if (from.isEmpty()) {
            source.sendError(Text.literal("[AIBot] No such sender bot: " + fromBot));
            return 0;
        }
        if (target.isEmpty()) {
            source.sendError(Text.literal("[AIBot] No such target bot: " + targetBot));
            return 0;
        }
        boolean queued = BrainCoordinator.INSTANCE.handleMessage(target.get(), from.get().getGameProfile().getName(), message);
        source.sendFeedback(() -> Text.literal("[AIBot] tell_bot " + (queued ? "queued" : "busy")), false);
        return queued ? 1 : 0;
    }

    public static Map<String, String> parseParams(String text) {
        Map<String, String> params = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return params;
        }
        for (String part : text.split("[, ]+")) {
            if (part.isBlank()) {
                continue;
            }
            int equals = part.indexOf('=');
            if (equals <= 0 || equals == part.length() - 1) {
                continue;
            }
            params.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
        }
        return params;
    }

    private static String format(Job job) {
        String shortId = job.id().toString().substring(0, 8);
        String claimant = job.claimant() == null ? "-" : job.claimant().toString().substring(0, 8);
        String reason = job.failureReason() == null || job.failureReason().isBlank() ? "" : " reason=" + job.failureReason();
        return shortId + " " + job.kind() + " role=" + job.role() + " status=" + job.status() + " bot=" + claimant + reason;
    }
}
