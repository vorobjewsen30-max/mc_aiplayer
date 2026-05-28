package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.ForageTask;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.block.Block;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotTaskSubcommand {
    private AIBotTaskSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("task")
                .then(literal("assign")
                        .then(botName()
                                .then(literal("move")
                                        .then(blockPosArgs(AIBotTaskSubcommand::assignMove)))
                                .then(literal("forage")
                                        .then(argument("entity_type", IdentifierArgumentType.identifier())
                                                .executes(context -> assignForage(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignForage(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("mine")
                                        .then(argument("block", IdentifierArgumentType.identifier())
                                                .executes(context -> assignMine(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignMine(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("build")
                                        .then(argument("blueprint", StringArgumentType.word())
                                                .then(blockPosArgs(AIBotTaskSubcommand::assignBuild))))))
                .then(literal("status")
                        .then(botName()
                                .executes(AIBotTaskSubcommand::status)))
                .then(literal("abort")
                        .then(botName()
                                .executes(AIBotTaskSubcommand::abort)));
    }

    private static RequiredArgumentBuilder<ServerCommandSource, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<ServerCommandSource, Integer> blockPosArgs(Command<ServerCommandSource> command) {
        return argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                        .then(argument("z", IntegerArgumentType.integer())
                                .executes(command)));
    }

    private static int assignMove(CommandContext<ServerCommandSource> context) {
        return assign(context, bot -> new MoveTask(bot, getBlockPos(context)));
    }

    private static int assignForage(CommandContext<ServerCommandSource> context, int count) {
        return assign(context, bot -> new ForageTask(
                Registries.ENTITY_TYPE.get(IdentifierArgumentType.getIdentifier(context, "entity_type")),
                count));
    }

    private static int assignMine(CommandContext<ServerCommandSource> context, int count) {
        return assign(context, bot -> {
            Block block = Registries.BLOCK.get(IdentifierArgumentType.getIdentifier(context, "block"));
            return new MineTask(block, count);
        });
    }

    private static int assignBuild(CommandContext<ServerCommandSource> context) {
        return assign(context, bot -> {
            try {
                return new BuildTask(BlueprintLoader.load(StringArgumentType.getString(context, "blueprint")), getBlockPos(context));
            } catch (IOException exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
        });
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        TaskStatus status = TaskManager.INSTANCE.status(bot.get());
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] task "
                + status.name()
                + " state=" + status.state()
                + " progress=" + String.format(java.util.Locale.ROOT, "%.2f", status.progress())
                + " elapsed=" + status.elapsedTicks()
                + " desc=" + status.description()
                + (status.failureReason().isBlank() ? "" : " reason=" + status.failureReason())), false);
        return 1;
    }

    private static int abort(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        TaskManager.INSTANCE.abort(bot.get());
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] task aborted"), false);
        return 1;
    }

    private static int assign(CommandContext<ServerCommandSource> context, TaskFactory factory) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        try {
            Task task = factory.create(bot.get());
            TaskManager.INSTANCE.assign(bot.get(), task);
            context.getSource().sendFeedback(() -> Text.literal("[AIBot] task assigned: " + task.name()), false);
            return 1;
        } catch (RuntimeException exception) {
            context.getSource().sendError(Text.literal("[AIBot] task assign failed: " + exception.getMessage()));
            return 0;
        }
    }

    private static Optional<AIPlayerEntity> getBot(CommandContext<ServerCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByName(name);
        if (bot.isEmpty()) {
            context.getSource().sendError(Text.literal("[AIBot] No such bot: " + name));
        }
        return bot;
    }

    private static BlockPos getBlockPos(CommandContext<ServerCommandSource> context) {
        return new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    @FunctionalInterface
    private interface TaskFactory {
        Task create(AIPlayerEntity bot);
    }
}
