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
public class BreakoutScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int BRICK_ROWS = 5, BRICK_COLS = 10;

    private enum State { MENU, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private boolean[][] bricks;
    private float ballX, ballY, ballDX, ballDY, paddleX;
    private int score, lives;
    private long tickCount;
    private int paddleW, paddleH, ballS, brickW, brickH, gameLeft, gameTop, gameW, gameH;
    private final List<GameRenderHelper.Particle> particles = new ArrayList<>();
    private static final int[] BRICK_COLORS = {0xFFFF4444, 0xFFFF8800, 0xFFFFFF00, 0xFF44FF44, 0xFF4488FF};

    public BreakoutScreen() { super(Component.literal("打砖块")); }

    private void startGame() {
        calcLayout();
        bricks = new boolean[BRICK_ROWS][BRICK_COLS];
        for (boolean[] row : bricks) Arrays.fill(row, true);
        paddleX = gameLeft + gameW / 2f - paddleW / 2f;
        ballX = gameLeft + gameW / 2f; ballY = gameTop + gameH * 0.7f;
        ballDX = 3; ballDY = -3; score = 0; lives = 3;
        state = State.PLAYING; particles.clear();
    }

    private void calcLayout() {
        gameW = Math.min(width - 40, 500);
        gameH = Math.min(height - 60, 400);
        gameLeft = (width - gameW) / 2;
        gameTop = (height - gameH) / 2;
        brickW = gameW / BRICK_COLS;
        brickH = Math.min(16, gameH / 20);
        paddleW = Math.max(40, gameW / 8);
        paddleH = 8; ballS = 6;
    }

    @Override public void tick() {
        tickCount++;
        if (state != State.PLAYING || showExitConfirm) return; // 弹窗期间暂停游戏
        ballX += ballDX; ballY += ballDY;
        // 墙壁反弹
        if (ballX <= gameLeft || ballX + ballS >= gameLeft + gameW) ballDX = -ballDX;
        if (ballY <= gameTop) ballDY = -ballDY;
        // 挡板反弹
        if (ballY + ballS >= gameTop + gameH - paddleH - 5 && ballY + ballS <= gameTop + gameH - 3
                && ballX + ballS >= paddleX && ballX <= paddleX + paddleW) {
            ballDY = -Math.abs(ballDY);
            float hitPos = (ballX + ballS/2f - paddleX) / paddleW;
            ballDX = (hitPos - 0.5f) * 6;
        }
        // 掉落
        if (ballY > gameTop + gameH) {
            lives--;
            if (lives <= 0) state = State.GAME_OVER;
            else { ballX = paddleX + paddleW/2f; ballY = gameTop + gameH * 0.7f; ballDX = 3; ballDY = -3; }
        }
        // 砖块碰撞：根据撞击面反弹（左右侧面反转ballDX，上下反转ballDY），同一tick只处理一块砖，防止多次反转导致穿墙
        boolean brickHit = false;
        for (int r = 0; r < BRICK_ROWS && !brickHit; r++)
            for (int c = 0; c < BRICK_COLS && !brickHit; c++)
                if (bricks[r][c]) {
                    int bx = gameLeft + c * brickW, by = gameTop + 20 + r * (brickH + 2);
                    if (ballX + ballS > bx && ballX < bx + brickW && ballY + ballS > by && ballY < by + brickH) {
                        bricks[r][c] = false; score += 10;
                        brickHit = true;
                        // 穿透量较小的一侧即为撞击面
                        float overlapX = Math.min(ballX + ballS - bx, bx + brickW - ballX);
                        float overlapY = Math.min(ballY + ballS - by, by + brickH - ballY);
                        if (overlapX < overlapY) ballDX = -ballDX; else ballDY = -ballDY;
                        GameRenderHelper.spawnParticles(particles, bx + brickW/2f, by + brickH/2f, 6, BRICK_COLORS[r]);
                        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.3F, 1.5F);
                    }
                }
        // 全部清除（用标签跳出全部循环，原break只跳出内层循环）
        boolean allClear = true;
        outer:
        for (boolean[] row : bricks) for (boolean b : row) if (b) { allClear = false; break outer; }
        if (allClear) state = State.GAME_OVER;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_R) { startGame(); return true; }
        return true;
    }

    @Override public void mouseMoved(double mx, double my) {
        if (state == State.PLAYING && !showExitConfirm) paddleX = (float)Math.max(gameLeft, Math.min(mx - paddleW/2.0, gameLeft + gameW - paddleW));
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        calcLayout();
        GameRenderHelper.fillDarkBackground(g, width, height);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case PLAYING -> renderPlaying(g);
            case GAME_OVER -> renderGameOver(g, mx, my);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width/2, cy = height/2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x220000);
        GameRenderHelper.drawShadowedCenteredText(g, font, "打砖块", cx, cy - 60, 0xFF6644, 2);
        g.drawCenteredString(font, "Breakout", cx, cy - 42, 0x553322);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFFFF4444, 0xFF882222);
        g.drawCenteredString(font, "鼠标移动控制挡板", cx, cy - 10, 0xAAAAAA);
        g.drawCenteredString(font, "反弹球击碎所有砖块！", cx, cy + 5, 0xCCCCCC);
        // 砖块预览
        for (int i = 0; i < 5; i++) {
            int bob = (int)(Math.sin(tickCount * 0.1 + i * 0.5) * 2);
            GameRenderHelper.drawBlock3D(g, cx - 40 + i * 18, cy + 22 + bob, 14, BRICK_COLORS[i]);
        }
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 45, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g) {
        // 游戏区域
        GameRenderHelper.drawGameBorder(g, gameLeft, gameTop, gameW, gameH, 0xFF334455);
        g.fill(gameLeft, gameTop, gameLeft + gameW, gameTop + gameH, 0xFF0A0A15);
        // 砖块
        for (int r = 0; r < BRICK_ROWS; r++)
            for (int c = 0; c < BRICK_COLS; c++)
                if (bricks[r][c])
                    GameRenderHelper.drawBlock3D(g, gameLeft + c * brickW + 1, gameTop + 20 + r * (brickH + 2), brickW - 2, BRICK_COLORS[r]);
        // 挡板
        GameRenderHelper.drawBlock3D(g, (int)paddleX, gameTop + gameH - paddleH - 5, paddleW, 0xFF44AAFF);
        // 球
        g.fill((int)ballX, (int)ballY, (int)ballX + ballS, (int)ballY + ballS, 0xFFFFFFFF);
        g.fill((int)ballX, (int)ballY, (int)ballX + ballS, (int)ballY + 1, 0xFFFFFFCC);
        GameRenderHelper.tickAndRenderParticles(g, particles);
        // HUD
        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "🧱 分数: " + score, 8, 7, 0xFF6644);
        g.drawCenteredString(font, "❤ x " + lives, width / 2, 7, 0xFF4444);
        GameRenderHelper.drawBottomBar(g, font, width, height, "鼠标移动  ESC 菜单  R 重开");
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        renderPlaying(g);
        GameRenderHelper.drawGameOverOverlay(g, width, height);
        int cx = width/2, cy = height/2;
        boolean win = lives > 0;
        GameRenderHelper.drawGameOverPanel(g, font, cx, cy, win, win ? "🎉 全部清除！" : "游戏结束！", "分数: " + score);
        GameRenderHelper.drawPrimaryButton(g, font, "R - 重来", cx - 60, cy + 20, 120, 18, mx, my);
        GameRenderHelper.drawSecondaryButton(g, font, "ESC - 返回", cx - 60, cy + 42, 120, 18, mx, my);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = width/2, cy = height/2;
        if (state == State.MENU && mx >= cx-60 && mx <= cx+60 && my >= cy+45 && my <= cy+67) { startGame(); return true; }
        if (state == State.GAME_OVER) {
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+20 && my <= cy+38) { startGame(); return true; }
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+42 && my <= cy+60) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override public boolean isPauseScreen() { return false; }
}
