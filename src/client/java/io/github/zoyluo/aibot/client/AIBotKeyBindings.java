package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.client.screen.BotPanelScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class AIBotKeyBindings {
    private static KeyBinding openPanel;
    private static boolean altZeroDown;

    private AIBotKeyBindings() {
    }

    public static void register() {
        openPanel = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aibot.open_panel",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.aibot"));
    }

    public static boolean shouldTogglePanel(MinecraftClient client) {
        boolean openPressed = false;
        while (openPanel.wasPressed()) {
            openPressed = true;
        }
        long handle = client.getWindow().getHandle();
        boolean altPressed = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean zeroPressed = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_0);
        boolean comboPressed = altPressed && zeroPressed;
        boolean comboOpened = comboPressed && !altZeroDown;
        altZeroDown = comboPressed;
        boolean triggered = openPressed || comboOpened;
        return triggered && (client.currentScreen == null || client.currentScreen instanceof BotPanelScreen);
    }
}
