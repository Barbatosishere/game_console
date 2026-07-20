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
public class PlatformerScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int PS = 16, GRAVITY = 1, JUMP_FORCE = -12, MOVE_SPEED = 4;

    private enum State { MENU, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private float playerX, playerY, velX, velY;
    private boolean onGround;
    private final List<int[]> platforms = new ArrayList<>(); // {x, y, w}
    private final List<int[]> coins = new ArrayList<>(); // {x, y, collected}
    private int score, cameraX;
    private long tickCount;
    private final boolean[] keys = new boolean[512];
    private final Random random = new Random();
    private final List<GameRenderHelper.Particle> particles = new ArrayList<>();

    public PlatformerScreen() { super(Component.literal("平台跳跃")); }

    private void startGame() {
        playerX = 50; playerY = 100; velX = 0; velY = 0; onGround = false;
        score = 0; cameraX = 0;
        platforms.clear(); coins.clear(); particles.clear();
        // 生成平台
        platforms.add(new int[]{20, height - 60, 120});
        int lastX = 120, lastY = height - 60;
        for (int i = 0; i < 100; i++) {
            int gap = 60 + random.nextInt(80);
            int dy = -30 + random.nextInt(60);
            lastX += gap;
            lastY = Math.max(60, Math.min(height - 80, lastY + dy));
            int pw = 60 + random.nextInt(80);
            platforms.add(new int[]{lastX, lastY, pw});
            if (random.nextFloat() > 0.4f) coins.add(new int[]{lastX + pw/2, lastY - 20, 0});
        }
        state = State.PLAYING;
        Arrays.fill(keys, false);
    }

    @Override public void tick() {
        tickCount++;
        if (state != State.PLAYING) return;
        // 输入
        if (keys[GLFW.GLFW_KEY_A] || keys[GLFW.GLFW_KEY_LEFT]) velX = -MOVE_SPEED;
        else if (keys[GLFW.GLFW_KEY_D] || keys[GLFW.GLFW_KEY_RIGHT]) velX = MOVE_SPEED;
        else velX = 0;
        // 物理
        velY += GRAVITY;
        if (velY > 20) velY = 20; // 终端速度，防止穿透
        playerX += velX;
        // Y轴分步碰撞：每步最多移动4px，防止高速穿透平台
        float remainY = velY;
        onGround = false;
        while (Math.abs(remainY) > 0) {
            float step = Math.max(-4, Math.min(4, remainY));
            playerY += step;
            remainY -= step;
            for (int[] p : platforms) {
                int px = p[0] - cameraX;
                if (playerX + PS > px && playerX < px + p[2]
                        && playerY + PS >= p[1] && playerY + PS <= p[1] + PS && velY >= 0) {
                    playerY = p[1] - PS; velY = 0; onGround = true; remainY = 0;
                } else if (playerX + PS > px && playerX < px + p[2]
                        && playerY <= p[1] + p[2] && playerY >= p[1] && velY < 0) {
                    velY = 0; remainY = 0; // 撞到底部
                }
            }
        }
        // 金币
        for (int[] c : coins) {
            if (c[2] == 0) {
                int cx = c[0] - cameraX, cy = c[1];
                if (Math.abs(playerX + PS/2 - cx) < 16 && Math.abs(playerY + PS/2 - cy) < 16) {
                    c[2] = 1; score += 10;
                    GameRenderHelper.spawnParticles(particles, cx, cy, 6, 0xFFDD44);
                    if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.5F);
                }
            }
        }
        // 摄像机
        if (playerX > width / 3) { cameraX += (int)(playerX - width / 3); playerX = width / 3f; }
        // 掉落
        if (playerY > height + 50) state = State.GAME_OVER;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        keys[Math.min(key, 511)] = true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_R) { startGame(); return true; }
        if (state == State.PLAYING && (key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_SPACE) && onGround)
            velY = JUMP_FORCE;
        return true;
    }

    @Override public boolean keyReleased(int key, int scan, int mods) {
        keys[Math.min(key, 511)] = false;
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF0A1A2A, 0xFF1A2A1A);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case PLAYING -> renderPlaying(g);
            case GAME_OVER -> renderGameOver(g, mx, my);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width/2, cy = height/2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x002200);
        GameRenderHelper.drawShadowedCenteredText(g, font, "§a平台跳跃", cx, cy - 60, 0x44FF44, 2);
        g.drawCenteredString(font, "Platformer", cx, cy - 42, 0x336633);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFF44AA44, 0xFF225522);
        g.drawCenteredString(font, "A/D 移动  W/空格 跳跃", cx, cy - 10, 0xAAAAAA);
        g.drawCenteredString(font, "收集金币！不要掉下去！", cx, cy + 5, 0xCCCCCC);
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 30, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g) {
        // 平台
        for (int[] p : platforms) {
            int px = p[0] - cameraX;
            if (px > -p[2] && px < width + 10) {
                g.fill(px, p[1], px + p[2], p[1] + 8, 0xFF44AA44);
                g.fill(px, p[1], px + p[2], p[1] + 2, 0xFF66CC66);
                g.fill(px, p[1] + 8, px + p[2], p[1] + 12, 0xFF886644);
            }
        }
        // 金币
        for (int[] c : coins) {
            if (c[2] == 0) {
                int cx = c[0] - cameraX, cy = c[1];
                int bob = (int)(Math.sin(tickCount * 0.15 + cx * 0.1) * 3);
                g.fill(cx - 5, cy - 5 + bob, cx + 5, cy + 5 + bob, 0xFFFFDD22);
                g.fill(cx - 5, cy - 5 + bob, cx + 5, cy - 4 + bob, 0xFFFFEE66);
            }
        }
        // 玩家
        g.fill((int)playerX, (int)playerY, (int)playerX + PS, (int)playerY + PS, 0xFF4488FF);
        g.fill((int)playerX, (int)playerY, (int)playerX + PS, (int)playerY + 3, 0xFF66AAFF);
        // 眼睛
        g.fill((int)playerX + 3, (int)playerY + 4, (int)playerX + 6, (int)playerY + 7, 0xFFFFFFFF);
        g.fill((int)playerX + 4, (int)playerY + 5, (int)playerX + 6, (int)playerY + 7, 0xFF000000);

        GameRenderHelper.tickAndRenderParticles(g, particles);
        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "§e🪙 " + score, 8, 7, 0xFFDD44);
        GameRenderHelper.drawBottomBar(g, font, width, height, "A/D 移动  W/空格 跳  ESC 菜单  R 重开");
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        renderPlaying(g);
        GameRenderHelper.drawGameOverOverlay(g, width, height);
        int cx = width/2, cy = height/2;
        GameRenderHelper.drawGameOverPanel(g, font, cx, cy, false, "游戏结束！", "金币: " + score);
        GameRenderHelper.drawPrimaryButton(g, font, "R - 重来", cx - 60, cy + 20, 120, 18, mx, my);
        GameRenderHelper.drawSecondaryButton(g, font, "ESC - 返回", cx - 60, cy + 42, 120, 18, mx, my);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; state = State.MENU; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = width/2, cy = height/2;
        if (state == State.MENU && mx >= cx-60 && mx <= cx+60 && my >= cy+30 && my <= cy+52) { startGame(); return true; }
        if (state == State.GAME_OVER) {
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+20 && my <= cy+38) { startGame(); return true; }
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+42 && my <= cy+60) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override public boolean isPauseScreen() { return false; }
}
