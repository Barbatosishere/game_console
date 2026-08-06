package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public class FlappyBirdScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int BIRD_SIZE = 16;
    private static final int PIPE_WIDTH = 40;
    private static final int PIPE_GAP = 100;

    private enum State { MENU, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private float birdY, birdVel;
    private final List<int[]> pipes = new ArrayList<>(); // {x, gapY}
    private int score, tickCounter;
    private long tickCount;
    private final List<GameRenderHelper.Particle> particles = new ArrayList<>();
    private final Random random = new Random();

    public FlappyBirdScreen() { super(Component.literal("像素鸟")); }

    private void startGame() {
        birdY = height / 2f; birdVel = 0; score = 0; tickCounter = 0;
        pipes.clear(); particles.clear();
        state = State.PLAYING;
    }

    private void flap() {
        birdVel = -5.5f;
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.CHICKEN_AMBIENT, 0.3F, 1.5F);
    }

    @Override public void tick() {
        tickCount++;
        if (state != State.PLAYING) return;
        birdVel += 0.35f;
        birdY += birdVel;
        tickCounter++;

        // 生成管道
        if (tickCounter % 50 == 0) {
            // 窗口高度过小时保护nextInt参数，避免IllegalArgumentException
            int gapY = 60 + random.nextInt(Math.max(1, height - 140));
            pipes.add(new int[]{width + 20, gapY});
        }

        // 移动管道
        pipes.forEach(p -> p[0] -= 3);
        pipes.removeIf(p -> p[0] + PIPE_WIDTH < 0);

        // 碰撞
        int bx = width / 4;
        for (int[] p : pipes) {
            if (bx + BIRD_SIZE > p[0] && bx < p[0] + PIPE_WIDTH) {
                if (birdY < p[1] - PIPE_GAP/2 || birdY + BIRD_SIZE > p[1] + PIPE_GAP/2) {
                    state = State.GAME_OVER;
                    GameRenderHelper.spawnParticles(particles, bx, birdY, 15, 0xFFFF44);
                    return;
                }
            }
            // 穿越判定：本帧右边缘已越过小鸟，且上一帧(移动前3px)还在其右侧，
            // 恰好在移动那一tick得分一次(替代整数精确相等，避免窗口宽度不满足模3时永远无法得分)
            if (p[0] + PIPE_WIDTH <= bx && p[0] + PIPE_WIDTH + 3 > bx) score++;
        }

        if (birdY > height || birdY < 0) state = State.GAME_OVER;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_R && state == State.GAME_OVER) { startGame(); return true; }
        if (state == State.PLAYING && (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_UP)) flap();
        return true;
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; state = State.MENU; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = width/2, cy = height/2;
        if (state == State.MENU) {
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+40 && my <= cy+62) { startGame(); return true; }
        }
        if (state == State.GAME_OVER) {
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+20 && my <= cy+38) { startGame(); return true; }
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+42 && my <= cy+60) { state = State.MENU; return true; }
            return true;
        }
        if (state == State.PLAYING) flap();
        return super.mouseClicked(mx, my, btn);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        GameRenderHelper.fillDarkBackground(g, width, height);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case PLAYING -> renderPlaying(g);
            case GAME_OVER -> renderGameOver(g, mx, my);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF0A1A2A, 0xFF1A3A6A);
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x003366);
        GameRenderHelper.drawShadowedCenteredText(g, font, "§e像 §r§f素 §r§e鸟", cx, cy - 60, 0xFFDD44, 2);
        g.drawCenteredString(font, "Flappy Bird", cx, cy - 42, 0x556633);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFFDDAA22, 0xFF886611);
        g.drawCenteredString(font, "空格/点击/W键 让小鸟飞起来！", cx, cy - 10, 0xCCCCCC);
        g.drawCenteredString(font, "穿过管道间隙得分", cx, cy + 5, 0xAAAAAA);
        // 小鸟预览
        int bob = (int)(Math.sin(tickCount * 0.15) * 4);
        drawBird(g, cx - 8, cy + 25 + bob);
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 40, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g) {
        // 天空渐变
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF1A3A6A, 0xFF2A5A3A);
        // 地面
        g.fill(0, height - 30, width, height, 0xFF3A6A2A);
        g.fill(0, height - 30, width, height - 28, 0xFF5A8A3A);

        // 管道
        for (int[] p : pipes) {
            int topH = p[1] - PIPE_GAP/2;
            int botY = p[1] + PIPE_GAP/2;
            // 上管道
            g.fill(p[0], 0, p[0] + PIPE_WIDTH, topH, 0xFF228833);
            g.fill(p[0], 0, p[0] + 3, topH, 0xFF33AA44);
            g.fill(p[0] - 4, topH - 16, p[0] + PIPE_WIDTH + 4, topH, 0xFF33AA44);
            // 下管道
            g.fill(p[0], botY, p[0] + PIPE_WIDTH, height - 30, 0xFF228833);
            g.fill(p[0], botY, p[0] + 3, height - 30, 0xFF33AA44);
            g.fill(p[0] - 4, botY, p[0] + PIPE_WIDTH + 4, botY + 16, 0xFF33AA44);
        }

        // 小鸟
        drawBird(g, width / 4, (int)birdY);

        GameRenderHelper.tickAndRenderParticles(g, particles);

        // HUD
        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "§e🐦 分数: §f" + score, 8, 7, 0xFFDD44);
        GameRenderHelper.drawBottomBar(g, font, width, height, "空格/点击 飞  ESC 菜单  R 重开");
    }

    private void drawBird(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + BIRD_SIZE, y + BIRD_SIZE, 0xFFFFDD22);
        g.fill(x, y, x + BIRD_SIZE, y + 2, 0xFFFFEE66);
        g.fill(x + BIRD_SIZE - 4, y + 4, x + BIRD_SIZE + 2, y + 8, 0xFFFF8800);
        g.fill(x + 3, y + 4, x + 6, y + 7, 0xFFFFFFFF);
        g.fill(x + 4, y + 5, x + 6, y + 7, 0xFF000000);
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        renderPlaying(g);
        GameRenderHelper.drawGameOverOverlay(g, width, height);
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.drawGameOverPanel(g, font, cx, cy, false, "游戏结束！", "分数: " + score);
        GameRenderHelper.drawPrimaryButton(g, font, "R - 重来", cx - 60, cy + 20, 120, 18, mx, my);
        GameRenderHelper.drawSecondaryButton(g, font, "ESC - 返回", cx - 60, cy + 42, 120, 18, mx, my);
    }

    @Override public boolean isPauseScreen() { return false; }
}
