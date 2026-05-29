package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.persist.BotPersistence;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotPersistSubcommand {
    private AIBotPersistSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("persist")
                .then(literal("save")
                        .executes(context -> save(context.getSource())))
                .then(literal("reload")
                        .executes(context -> reload(context.getSource())));
    }

    private static int save(ServerCommandSource source) {
        int count = BotPersistence.INSTANCE.saveAll(source.getServer());
        source.sendFeedback(() -> Text.literal("[AIBot] persisted " + count + " bot(s)"), false);
        return count;
    }

    private static int reload(ServerCommandSource source) {
        int count = BotPersistence.INSTANCE.loadAndRespawn(source.getServer());
        source.sendFeedback(() -> Text.literal("[AIBot] restored " + count + " bot(s)"), false);
        return count;
    }
}
