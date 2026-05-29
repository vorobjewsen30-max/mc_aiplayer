package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record BotSnapshotS2C(
        String botName,
        float health,
        float maxHealth,
        int food,
        String taskName,
        String taskState,
        float progress,
        boolean brainBusy,
        int promptTokens,
        int completionTokens,
        List<ItemEntry> inventory
) implements CustomPayload {
    public static final Id<BotSnapshotS2C> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "bot_snapshot"));
    public static final PacketCodec<RegistryByteBuf, BotSnapshotS2C> CODEC = PacketCodec.of(BotSnapshotS2C::write, BotSnapshotS2C::new);

    private BotSnapshotS2C(RegistryByteBuf buf) {
        this(
                buf.readString(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readInt(),
                buf.readString(),
                buf.readString(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                readInventory(buf));
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeFloat(health);
        buf.writeFloat(maxHealth);
        buf.writeInt(food);
        buf.writeString(taskName);
        buf.writeString(taskState);
        buf.writeFloat(progress);
        buf.writeBoolean(brainBusy);
        buf.writeInt(promptTokens);
        buf.writeInt(completionTokens);
        buf.writeInt(inventory.size());
        for (ItemEntry entry : inventory) {
            buf.writeString(entry.itemId());
            buf.writeInt(entry.count());
            buf.writeInt(entry.slot());
        }
    }

    private static List<ItemEntry> readInventory(RegistryByteBuf buf) {
        int size = buf.readInt();
        List<ItemEntry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new ItemEntry(buf.readString(), buf.readInt(), buf.readInt()));
        }
        return entries;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record ItemEntry(String itemId, int count, int slot) {
    }
}
