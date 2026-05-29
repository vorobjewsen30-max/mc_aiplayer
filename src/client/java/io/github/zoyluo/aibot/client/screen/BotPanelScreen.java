package io.github.zoyluo.aibot.client.screen;

import io.github.zoyluo.aibot.client.BotClientState;
import io.github.zoyluo.aibot.client.BotCommandBridge;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

public final class BotPanelScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int INPUT_HEIGHT = 20;

    private TextFieldWidget botField;
    private TextFieldWidget chatField;
    private TextFieldWidget moveField;
    private TextFieldWidget mineField;
    private TextFieldWidget mineCountField;
    private TextFieldWidget craftField;
    private TextFieldWidget craftCountField;
    private TextFieldWidget smeltInputField;
    private TextFieldWidget smeltOutputField;
    private TextFieldWidget smeltCountField;

    public BotPanelScreen() {
        super(Text.translatable("screen.aibot.panel"));
    }

    @Override
    protected void init() {
        int left = Math.max(12, (width - PANEL_WIDTH) / 2);
        int y = 28;
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;

        botField = addTextField(renderer, left, y, 164, BotClientState.INSTANCE.targetBot(), "Bob");
        addDrawableChild(ButtonWidget.builder(Text.literal("Lock"), button -> lockTarget())
                .dimensions(left + 170, y, 54, INPUT_HEIGHT)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Come"), button -> {
            if (client != null && client.player != null) {
                BotCommandBridge.command(targetBot(), "move", client.player.getBlockPos().toShortString().replace(",", ""), "", 1);
            }
        }).dimensions(left + 230, y, 58, INPUT_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), button -> BotCommandBridge.command(targetBot(), "abort", "", "", 1))
                .dimensions(left + 294, y, 54, INPUT_HEIGHT)
                .build());

        y += 28;
        chatField = addTextField(renderer, left, y, 278, "", "Message");
        addDrawableChild(ButtonWidget.builder(Text.literal("Send"), button -> sendChat())
                .dimensions(left + 286, y, 62, INPUT_HEIGHT)
                .build());

        y += 30;
        moveField = addTextField(renderer, left, y, 166, "", "x y z");
        addDrawableChild(ButtonWidget.builder(Text.literal("Move"), button -> BotCommandBridge.command(targetBot(), "move", moveField.getText(), "", 1))
                .dimensions(left + 172, y, 52, INPUT_HEIGHT)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Eat"), button -> BotCommandBridge.command(targetBot(), "eat", "", "", 1))
                .dimensions(left + 230, y, 48, INPUT_HEIGHT)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), button -> BotCommandBridge.command(targetBot(), "reset", "", "", 1))
                .dimensions(left + 284, y, 64, INPUT_HEIGHT)
                .build());

        y += 30;
        mineField = addTextField(renderer, left, y, 200, "minecraft:stone", "Block id");
        mineCountField = addTextField(renderer, left + 206, y, 48, "1", "N");
        addDrawableChild(ButtonWidget.builder(Text.literal("Mine"), button -> BotCommandBridge.command(targetBot(), "mine", mineField.getText(), "", parseCount(mineCountField)))
                .dimensions(left + 262, y, 86, INPUT_HEIGHT)
                .build());

        y += 30;
        craftField = addTextField(renderer, left, y, 200, "minecraft:crafting_table", "Item id");
        craftCountField = addTextField(renderer, left + 206, y, 48, "1", "N");
        addDrawableChild(ButtonWidget.builder(Text.literal("Craft"), button -> BotCommandBridge.command(targetBot(), "craft", craftField.getText(), "", parseCount(craftCountField)))
                .dimensions(left + 262, y, 86, INPUT_HEIGHT)
                .build());

        y += 30;
        smeltInputField = addTextField(renderer, left, y, 126, "minecraft:raw_iron", "Input");
        smeltOutputField = addTextField(renderer, left + 132, y, 126, "minecraft:iron_ingot", "Output");
        smeltCountField = addTextField(renderer, left + 264, y, 32, "1", "N");
        addDrawableChild(ButtonWidget.builder(Text.literal("Smelt"), button -> BotCommandBridge.command(targetBot(), "smelt", smeltInputField.getText(), smeltOutputField.getText(), parseCount(smeltCountField)))
                .dimensions(left + 302, y, 58, INPUT_HEIGHT)
                .build());

        BotCommandBridge.subscribe(targetBot(), true);
        setInitialFocus(chatField);
    }

    @Override
    public void close() {
        BotCommandBridge.subscribe(targetBot(), false);
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (chatField != null && chatField.isFocused()) {
                sendChat();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xD0101014);
        int left = Math.max(12, (width - PANEL_WIDTH) / 2);
        int top = 10;
        int textColor = 0xFFE8ECEF;
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;

        context.drawTextWithShadow(renderer, title, left, top, textColor);
        drawStatus(context, renderer, left, 212);
        drawTranscript(context, renderer, left, Math.max(318, height - 112));
        super.render(context, mouseX, mouseY, delta);
    }

    private TextFieldWidget addTextField(TextRenderer renderer, int x, int y, int width, String text, String suggestion) {
        TextFieldWidget field = new TextFieldWidget(renderer, x, y, width, INPUT_HEIGHT, Text.literal(suggestion));
        field.setMaxLength(256);
        field.setText(text);
        field.setSuggestion(suggestion);
        addDrawableChild(field);
        return field;
    }

    private void lockTarget() {
        String oldTarget = BotClientState.INSTANCE.targetBot();
        String newTarget = botField.getText().isBlank() ? "Bob" : botField.getText().trim();
        if (!oldTarget.equals(newTarget)) {
            BotCommandBridge.subscribe(oldTarget, false);
            BotClientState.INSTANCE.setTargetBot(newTarget);
            BotCommandBridge.subscribe(newTarget, true);
            BotClientState.INSTANCE.addTranscript("system", "Locked target: " + newTarget);
        }
    }

    private void sendChat() {
        String text = chatField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        BotCommandBridge.chat(targetBot(), text);
        chatField.setText("");
    }

    private void drawStatus(DrawContext context, TextRenderer renderer, int left, int top) {
        BotSnapshotS2C snapshot = BotClientState.INSTANCE.snapshot();
        if (!BotCommandBridge.hasPermission()) {
            drawLine(context, renderer, "Permission: Need OP permission", left, top, 0xFFFFB4A8);
            top += 12;
        }
        if (snapshot == null) {
            drawLine(context, renderer, "Target: " + targetBot() + " | waiting for snapshot", left, top, 0xFFE8ECEF);
            drawLine(context, renderer, "Fallback: commands and chat capture stay available", left, top + 12, 0xFFADB5BD);
            return;
        }
        drawLine(context, renderer, "Target: " + snapshot.botName() + " | HP " + oneDecimal(snapshot.health()) + "/" + oneDecimal(snapshot.maxHealth()) + " | Food " + snapshot.food(), left, top, 0xFFE8ECEF);
        drawLine(context, renderer, "Task: " + snapshot.taskName() + " | " + snapshot.taskState() + " | " + (int) (snapshot.progress() * 100) + "%", left, top + 12, 0xFFD7DEE5);
        drawLine(context, renderer, "Brain: " + (snapshot.brainBusy() ? "busy" : "idle") + " | tokens " + snapshot.promptTokens() + "/" + snapshot.completionTokens(), left, top + 24, 0xFFD7DEE5);
        drawLine(context, renderer, "Inventory: " + inventoryText(snapshot.inventory()), left, top + 38, 0xFFBEC7D0);
    }

    private void drawTranscript(DrawContext context, TextRenderer renderer, int left, int top) {
        drawLine(context, renderer, "Chat", left, top, 0xFFE8ECEF);
        List<BotClientState.ChatLine> lines = BotClientState.INSTANCE.transcript();
        int start = Math.max(0, lines.size() - 7);
        int y = top + 14;
        for (int index = start; index < lines.size(); index++) {
            BotClientState.ChatLine line = lines.get(index);
            int color = switch (line.role()) {
                case "user" -> 0xFFB8E0FF;
                case "bot" -> 0xFFC8F7C5;
                case "system" -> 0xFFFFD28C;
                default -> 0xFFE8ECEF;
            };
            drawLine(context, renderer, trim(line.role() + ": " + line.text(), 76), left, y, color);
            y += 12;
        }
    }

    private void drawLine(DrawContext context, TextRenderer renderer, String text, int x, int y, int color) {
        context.drawTextWithShadow(renderer, text, x, y, color);
    }

    private String inventoryText(List<BotSnapshotS2C.ItemEntry> inventory) {
        if (inventory.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(inventory.size(), 8);
        for (int index = 0; index < limit; index++) {
            BotSnapshotS2C.ItemEntry entry = inventory.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(entry.itemId())).append(" x").append(entry.count());
        }
        if (inventory.size() > limit) {
            builder.append(", +").append(inventory.size() - limit);
        }
        return builder.toString();
    }

    private String targetBot() {
        return BotClientState.INSTANCE.targetBot();
    }

    private int parseCount(TextFieldWidget field) {
        try {
            return Math.max(1, Integer.parseInt(field.getText().trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private String shortId(String id) {
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }

    private String trim(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String oneDecimal(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
