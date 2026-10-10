package com.wzz.game_console.client.screens.games;

import com.mojang.blaze3d.platform.InputConstants;
import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

import static com.wzz.game_console.client.screens.games.GameInput.Action.*;

/** Common input reset, focus pause and exit confirmation for keyboard arcade games. */
@OnlyIn(Dist.CLIENT)
public abstract class ControlledGameScreen extends Screen {
    protected boolean showExitConfirm;
    private boolean previouslyFocused = true;
    protected final GameInput input = new GameInput(Map.ofEntries(
            Map.entry(GLFW.GLFW_KEY_W, UP), Map.entry(GLFW.GLFW_KEY_UP, UP),
            Map.entry(GLFW.GLFW_KEY_S, DOWN), Map.entry(GLFW.GLFW_KEY_DOWN, DOWN),
            Map.entry(GLFW.GLFW_KEY_A, LEFT), Map.entry(GLFW.GLFW_KEY_LEFT, LEFT),
            Map.entry(GLFW.GLFW_KEY_D, RIGHT), Map.entry(GLFW.GLFW_KEY_RIGHT, RIGHT),
            Map.entry(GLFW.GLFW_KEY_SPACE, PRIMARY), Map.entry(GLFW.GLFW_KEY_ENTER, SECONDARY),
            Map.entry(GLFW.GLFW_KEY_R, RESTART), Map.entry(GLFW.GLFW_KEY_ESCAPE, EXIT)));

    protected ControlledGameScreen(Component title) { super(title); }

    protected boolean canAdvance(boolean playing) {
        Minecraft client = Minecraft.getInstance();
        boolean focused = client.isWindowActive();
        if (focused && !previouslyFocused) {
            input.clearReleasedBlocks(key -> InputConstants.isKeyDown(client.getWindow().getWindow(), key));
        }
        previouslyFocused = focused;
        if (!playing || showExitConfirm || !focused) {
            input.clear();
            return false;
        }
        input.tick();
        return true;
    }

    protected boolean handleExitKey(int key, boolean inGame) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (input.press(key) != EXIT) return true;
            input.clear();
            if (showExitConfirm) showExitConfirm = false;
            else if (inGame) showExitConfirm = true;
            else Minecraft.getInstance().setScreen(new GameSelectorScreen());
            return true;
        }
        if (showExitConfirm || !Minecraft.getInstance().isWindowActive()) {
            // Discard keys pressed during a pause and require release before a new action.
            // This prevents a held movement key or OS repeat from firing when play resumes.
            input.press(key);
            input.clear();
            return true;
        }
        return false;
    }

    protected boolean handleExitClick(double x, double y) {
        if (!showExitConfirm) return false;
        int click = GameRenderHelper.getExitConfirmClick(x, y, width, height);
        if (click == 1) Minecraft.getInstance().setScreen(new GameSelectorScreen());
        else if (click == 2) showExitConfirm = false;
        input.clear();
        return true;
    }

    protected void resetControls() { input.clear(); showExitConfirm = false; }
    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        return input.release(key) || super.keyReleased(key, scan, modifiers);
    }
    @Override public void removed() { input.clear(); super.removed(); }
    @Override public void onClose() { input.clear(); super.onClose(); }
}
