package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.log.BotLog;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import java.util.regex.Pattern;

public final class ChatCaptureListener {
    private static final Pattern MENTION = Pattern.compile("@(\\w+)\\s+(.+)");

    private ChatCaptureListener() {
    }

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString();
            var matcher = MENTION.matcher(text);
            if (!matcher.find()) {
                return;
            }
            String targetName = matcher.group(1);
            String body = matcher.group(2);
            AIPlayerManager.INSTANCE.getByName(targetName).ifPresent(bot -> {
                BotLog.comm(bot, "chat_in", "sender", sender.getGameProfile().getName(), "text", body);
                BrainCoordinator.INSTANCE.handleMessage(bot, sender.getGameProfile().getName(), body);
            });
        });
    }
}
