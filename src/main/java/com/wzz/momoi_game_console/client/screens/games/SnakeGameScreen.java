package com.wzz.momoi_game_console.client.screens.games;

import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import com.wzz.momoi_game_console.util.GameRenderHelper;
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
public class SnakeGameScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int GRID_W = 30, GRID_H = 20;

    private enum State { MENU, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private final List<int[]> snake = new ArrayList<>();
    private int[] food;
    private int dx = 1, dy = 0;
    private int tickCounter = 0, score = 0;
    private long tickCount = 0;
    private final List<GameRenderHelper.Particle> particles = new ArrayList<>();
    private final List<GameRenderHelper.FloatingText> floats = new ArrayList<>();
    private final Random random = new Random();
    private int cellSize;
    private int offsetX, offsetY;

    public SnakeGameScreen() { super(Component.literal("贪吃蛇")); }

    private void startGame() {
        snake.clear();
        snake.add(new int[]{GRID_W / 2, GRID_H / 2});
        dx = 1; dy = 0; score = 0;
        spawnFood();
        state = State.PLAYING;
        particles.clear(); floats.clear();
    }

    private void spawnFood() {
        do { food = new int[]{random.nextInt(GRID_W), random.nextInt(GRID_H)}; }
        while (snake.stream().anyMatch(s -> s[0] == food[0] && s[1] == food[1]));
    }

    @Override public void tick() {
        tickCount++;
        floats.removeIf(f -> { f.update(); return !f.isAlive(); });
        if (state != State.PLAYING) return;
        tickCounter++;
        int speed = Math.max(1, 4 - score / 15);
        if (tickCounter < speed) return;
        tickCounter = 0;

        int[] head = snake.get(0);
        int nx = head[0] + dx, ny = head[1] + dy;
        if (nx < 0 || nx >= GRID_W || ny < 0 || ny >= GRID_H || snake.stream().anyMatch(s -> s[0] == nx && s[1] == ny)) {
            state = State.GAME_OVER;
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.GENERIC_EXPLODE.value(), 0.5F, 1.0F);
            return;
        }
        snake.add(0, new int[]{nx, ny});
        if (nx == food[0] && ny == food[1]) {
            score++;
            spawnFood();
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.5F);
            GameRenderHelper.spawnParticles(particles, offsetX + food[0] * cellSize + cellSize / 2f, offsetY + food[1] * cellSize + cellSize / 2f, 8, 0x44FF44);
            floats.add(new GameRenderHelper.FloatingText("+1", offsetX + nx * cellSize, offsetY + ny * cellSize - 10, 0x44FF44, 30));
        } else {
            snake.remove(snake.size() - 1);
        }
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            else { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
        }
        if (showExitConfirm) return true;
        if (state == State.GAME_OVER && key == GLFW.GLFW_KEY_R) { startGame(); return true; }
        if (state == State.PLAYING) {
            switch (key) {
                case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP    -> { if (dy != 1) { dx=0; dy=-1; } }
                case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN  -> { if (dy != -1) { dx=0; dy=1; } }
                case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT  -> { if (dx != 1) { dx=-1; dy=0; } }
                case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> { if (dx != -1) { dx=1; dy=0; } }
            }
        }
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        // 动态计算尺寸
        cellSize = GameRenderHelper.calcCellSize(width, height, GRID_W, GRID_H, 40);
        offsetX = (width - GRID_W * cellSize) / 2;
        offsetY = (height - GRID_H * cellSize) / 2;

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
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x003300);

        GameRenderHelper.drawShadowedCenteredText(g, font, "§a贪 §r§f吃 §r§a蛇", cx, cy - 60, 0x44FF44, 2);
        g.drawCenteredString(font, "Snake Game", cx, cy - 42, 0x336633);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFF22AA22, 0xFF115511);

        g.drawCenteredString(font, "WASD / 方向键 移动", cx, cy - 15, 0x88CC88);
        g.drawCenteredString(font, "吃食物让蛇变长，不要撞墙或自己！", cx, cy, 0xCCCCCC);

        // 蛇的动画预览
        int previewX = cx - 30;
        int previewY = cy + 20;
        for (int i = 0; i < 6; i++) {
            int bob = (int)(Math.sin(tickCount * 0.1 + i * 0.5) * 2);
            int brightness = 255 - i * 30;
            g.fill(previewX + i * 10, previewY + bob, previewX + i * 10 + 8, previewY + bob + 8,
                    0xFF000000 | (brightness / 4 << 16) | (brightness << 8) | (brightness / 4));
        }

        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 40, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g) {
        // 游戏区域背景
        g.fill(offsetX - 2, offsetY - 2, offsetX + GRID_W * cellSize + 2, offsetY + GRID_H * cellSize + 2, 0xFF334433);
        g.fill(offsetX, offsetY, offsetX + GRID_W * cellSize, offsetY + GRID_H * cellSize, 0xFF111811);

        // 网格线（微弱）
        for (int x = 0; x <= GRID_W; x++)
            g.fill(offsetX + x * cellSize, offsetY, offsetX + x * cellSize + 1, offsetY + GRID_H * cellSize, 0x11FFFFFF);
        for (int y = 0; y <= GRID_H; y++)
            g.fill(offsetX, offsetY + y * cellSize, offsetX + GRID_W * cellSize, offsetY + y * cellSize + 1, 0x11FFFFFF);

        // 蛇身
        for (int i = 0; i < snake.size(); i++) {
            int[] s = snake.get(i);
            float t = 1f - (float)i / Math.max(1, snake.size());
            int green = (int)(100 + 155 * t);
            int color = 0xFF000000 | (green / 3 << 16) | (green << 8) | (green / 6);
            int sx = offsetX + s[0] * cellSize;
            int sy = offsetY + s[1] * cellSize;
            g.fill(sx + 1, sy + 1, sx + cellSize - 1, sy + cellSize - 1, color);
            // 蛇头眼睛
            if (i == 0 && cellSize >= 8) {
                int eyeSize = Math.max(1, cellSize / 6);
                g.fill(sx + cellSize / 3, sy + cellSize / 4, sx + cellSize / 3 + eyeSize, sy + cellSize / 4 + eyeSize, 0xFFFFFFFF);
                g.fill(sx + cellSize * 2 / 3, sy + cellSize / 4, sx + cellSize * 2 / 3 + eyeSize, sy + cellSize / 4 + eyeSize, 0xFFFFFFFF);
            }
        }

        // 食物（闪烁动画）
        if (food != null) {
            int fx = offsetX + food[0] * cellSize;
            int fy = offsetY + food[1] * cellSize;
            int bob = (int)(Math.sin(tickCount * 0.15) * 2);
            float pulse = 0.8f + 0.2f * (float)Math.sin(tickCount * 0.2);
            int red = (int)(255 * pulse);
            g.fill(fx + 1, fy + bob + 1, fx + cellSize - 1, fy + bob + cellSize - 1, 0xFF000000 | (red << 16) | 0x2222);
            // 闪光
            int glow = (int)(40 + 30 * Math.sin(tickCount * 0.2));
            g.fill(fx - 1, fy + bob - 1, fx + cellSize + 1, fy + bob + cellSize + 1, (glow << 24) | 0xFF4444);
        }

        // 粒子
        GameRenderHelper.tickAndRenderParticles(g, particles);
        // 浮动文字
        for (GameRenderHelper.FloatingText ft : floats) ft.render(g, font);

        // HUD
        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "§a🐍 分数: §f" + score, 8, 7, 0x44FF44);
        g.drawString(font, "§7长度: §f" + snake.size(), width / 2 - 30, 7, 0xCCCCCC);
        int sw = font.width("ESC 菜单  R 重开");
        g.drawString(font, "§7ESC 菜单  R 重开", width - sw - 8, 7, 0x666666);
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        renderPlaying(g); // 先渲染游戏场景
        GameRenderHelper.drawGameOverOverlay(g, width, height);
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.drawGameOverPanel(g, font, cx, cy, false, "游戏结束！", "最终分数: " + score);
        g.drawCenteredString(font, "蛇长: " + snake.size(), cx, cy + 4, GameRenderHelper.TEXT_CYAN);
        GameRenderHelper.drawPrimaryButton(g, font, "R - 重新开始", cx - 70, cy + 20, 140, 18, mx, my);
        GameRenderHelper.drawSecondaryButton(g, font, "ESC - 返回菜单", cx - 70, cy + 42, 140, 18, mx, my);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; state = State.MENU; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = width / 2, cy = height / 2;
        if (state == State.MENU) {
            if (mx >= cx - 60 && mx <= cx + 60 && my >= cy + 40 && my <= cy + 62) { startGame(); return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override public boolean isPauseScreen() { return false; }
}