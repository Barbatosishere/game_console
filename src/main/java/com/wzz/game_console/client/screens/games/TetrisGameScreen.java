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
public class TetrisGameScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int GW = 10, GH = 20;
    private static final int[][][] SHAPES = {
            {{1,1,1,1}}, {{1,1},{1,1}}, {{0,1,0},{1,1,1}},
            {{0,1,1},{1,1,0}}, {{1,1,0},{0,1,1}}, {{1,0,0},{1,1,1}}, {{0,0,1},{1,1,1}}
    };
    private static final int[] COLORS = {
            0xFF00FFFF, 0xFFFFFF00, 0xFFAA00FF, 0xFF00FF00, 0xFFFF0000, 0xFF0000FF, 0xFFFF8800
    };

    private enum State { MENU, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private int[][] board; private int[][] colorBoard;
    private int[][] current; private int currentColor;
    private int cx, cy, tickCounter, score, lines, level;
    private long tickCount;
    private final List<GameRenderHelper.Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private int cellSize, offsetX, offsetY;

    public TetrisGameScreen() { super(Component.literal("俄罗斯方块")); }

    private void startGame() {
        board = new int[GH][GW]; colorBoard = new int[GH][GW];
        score = 0; lines = 0; level = 1;
        spawnPiece(); state = State.PLAYING; particles.clear();
    }

    private void spawnPiece() {
        int idx = random.nextInt(SHAPES.length);
        current = copyShape(SHAPES[idx]); currentColor = COLORS[idx];
        cx = GW / 2 - current[0].length / 2; cy = 0;
        if (!canPlace(current, cx, cy)) { state = State.GAME_OVER; }
    }

    private int[][] copyShape(int[][] s) {
        int[][] c = new int[s.length][];
        for (int i = 0; i < s.length; i++) c[i] = s[i].clone();
        return c;
    }

    private boolean canPlace(int[][] p, int px, int py) {
        for (int i = 0; i < p.length; i++)
            for (int j = 0; j < p[i].length; j++)
                if (p[i][j] == 1) {
                    int nx = px + j, ny = py + i;
                    if (nx < 0 || nx >= GW || ny < 0 || ny >= GH || board[ny][nx] == 1) return false;
                }
        return true;
    }

    private void placePiece() {
        for (int i = 0; i < current.length; i++)
            for (int j = 0; j < current[i].length; j++)
                if (current[i][j] == 1) { board[cy + i][cx + j] = 1; colorBoard[cy + i][cx + j] = currentColor; }
        clearLines(); spawnPiece();
    }

    private void clearLines() {
        for (int y = 0; y < GH; y++) {
            boolean full = true;
            for (int x = 0; x < GW; x++) if (board[y][x] == 0) { full = false; break; }
            if (full) {
                // 清除动画粒子
                for (int x = 0; x < GW; x++)
                    GameRenderHelper.spawnParticles(particles, offsetX + x * cellSize + cellSize / 2f, offsetY + y * cellSize, 3, colorBoard[y][x]);
                for (int i = y; i > 0; i--) { board[i] = board[i-1].clone(); colorBoard[i] = colorBoard[i-1].clone(); }
                board[0] = new int[GW]; colorBoard[0] = new int[GW];
                lines++; score += 100 * level;
                if (lines % 10 == 0) level++;
                if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.EXPERIENCE_BOTTLE_THROW, 1.0F, 1.0F);
            }
        }
    }

    private int[][] rotate(int[][] s) {
        int[][] r = new int[s[0].length][s.length];
        for (int y = 0; y < s.length; y++)
            for (int x = 0; x < s[0].length; x++)
                r[x][s.length - y - 1] = s[y][x];
        return r;
    }

    @Override public void tick() {
        tickCount++;
        if (state != State.PLAYING) return;
        tickCounter++;
        int speed = Math.max(1, 10 - level);
        if (tickCounter >= speed) {
            tickCounter = 0;
            if (canPlace(current, cx, cy + 1)) cy++;
            else placePiece();
        }
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (state == State.GAME_OVER && key == GLFW.GLFW_KEY_R) { startGame(); return true; }
        if (state != State.PLAYING) return true;
        switch (key) {
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT  -> { if (canPlace(current, cx-1, cy)) cx--; }
            case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> { if (canPlace(current, cx+1, cy)) cx++; }
            case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN  -> { if (canPlace(current, cx, cy+1)) cy++; }
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP    -> { int[][] r = rotate(current); if (canPlace(r, cx, cy)) current = r; }
            case GLFW.GLFW_KEY_SPACE -> { while (canPlace(current, cx, cy+1)) cy++; placePiece(); }
        }
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        cellSize = Math.max(8, Math.min((width - 120) / GW, (height - 60) / GH));
        offsetX = (width - GW * cellSize) / 2;
        offsetY = (height - GH * cellSize) / 2;

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
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x001144);
        GameRenderHelper.drawShadowedCenteredText(g, font, "§b俄罗斯方块", cx, cy - 60, 0x00FFFF, 2);
        g.drawCenteredString(font, "Tetris", cx, cy - 42, 0x335566);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFF0088CC, 0xFF004466);
        g.drawCenteredString(font, "A/D 左右移动  W 旋转  S 加速  空格 硬降", cx, cy - 10, 0x88AACC);
        g.drawCenteredString(font, "消除完整行获得分数！", cx, cy + 5, 0xCCCCCC);
        // 方块预览
        int[] previewColors = {0xFF00FFFF, 0xFFFF8800, 0xFFAA00FF, 0xFF00FF00};
        for (int i = 0; i < 4; i++) {
            int px = cx - 30 + i * 16;
            int bob = (int)(Math.sin(tickCount * 0.1 + i) * 3);
            g.fill(px, cy + 25 + bob, px + 12, cy + 37 + bob, previewColors[i]);
            g.fill(px, cy + 25 + bob, px + 12, cy + 26 + bob, GameRenderHelper.brighten(previewColors[i], 1.3f));
        }
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 48, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g) {
        // 游戏区边框
        GameRenderHelper.drawGameBorder(g, offsetX, offsetY, GW * cellSize, GH * cellSize, 0xFF334466);
        g.fill(offsetX, offsetY, offsetX + GW * cellSize, offsetY + GH * cellSize, 0xFF0A0A15);

        // 网格
        for (int x = 0; x <= GW; x++)
            g.fill(offsetX + x * cellSize, offsetY, offsetX + x * cellSize + 1, offsetY + GH * cellSize, 0x0CFFFFFF);
        for (int y = 0; y <= GH; y++)
            g.fill(offsetX, offsetY + y * cellSize, offsetX + GW * cellSize, offsetY + y * cellSize + 1, 0x0CFFFFFF);

        // 固定方块
        for (int y = 0; y < GH; y++)
            for (int x = 0; x < GW; x++)
                if (board[y][x] == 1) drawBlock(g, offsetX + x * cellSize, offsetY + y * cellSize, cellSize, colorBoard[y][x]);

        // 当前方块
        if (current != null)
            for (int i = 0; i < current.length; i++)
                for (int j = 0; j < current[i].length; j++)
                    if (current[i][j] == 1)
                        drawBlock(g, offsetX + (cx + j) * cellSize, offsetY + (cy + i) * cellSize, cellSize, currentColor);

        // 幽灵方块（落点预览）
        int ghostY = cy;
        while (canPlace(current, cx, ghostY + 1)) ghostY++;
        if (ghostY != cy && current != null)
            for (int i = 0; i < current.length; i++)
                for (int j = 0; j < current[i].length; j++)
                    if (current[i][j] == 1)
                        g.fill(offsetX + (cx + j) * cellSize + 1, offsetY + (ghostY + i) * cellSize + 1,
                                offsetX + (cx + j + 1) * cellSize - 1, offsetY + (ghostY + i + 1) * cellSize - 1,
                                GameRenderHelper.withAlpha(currentColor, 40));

        GameRenderHelper.tickAndRenderParticles(g, particles);

        // HUD
        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "§b🟦 分数: §f" + score, 8, 7, 0x00FFFF);
        g.drawCenteredString(font, "§e等级 " + level + "  §7消除 " + lines + " 行", width / 2, 7, 0xFFFF44);
        GameRenderHelper.drawBottomBar(g, font, width, height, "ESC 菜单  R 重开  WASD 操作  空格 硬降");
    }

    private void drawBlock(GuiGraphics g, int x, int y, int s, int color) {
        g.fill(x, y, x + s, y + s, color);
        g.fill(x, y, x + s, y + 1, GameRenderHelper.brighten(color, 1.3f));
        g.fill(x, y, x + 1, y + s, GameRenderHelper.brighten(color, 1.15f));
        g.fill(x, y + s - 1, x + s, y + s, GameRenderHelper.darken(color, 0.6f));
        g.fill(x + s - 1, y, x + s, y + s, GameRenderHelper.darken(color, 0.7f));
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        renderPlaying(g);
        GameRenderHelper.drawGameOverOverlay(g, width, height);
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.drawGameOverPanel(g, font, cx, cy, false, "游戏结束！", "分数: " + score + "  等级: " + level);
        g.drawCenteredString(font, "消除 " + lines + " 行", cx, cy + 4, GameRenderHelper.TEXT_CYAN);
        GameRenderHelper.drawPrimaryButton(g, font, "R - 重来", cx - 60, cy + 22, 120, 18, mx, my);
        GameRenderHelper.drawSecondaryButton(g, font, "ESC - 返回", cx - 60, cy + 44, 120, 18, mx, my);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; state = State.MENU; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = width / 2, cy = height / 2;
        if (state == State.MENU && mx >= cx - 60 && mx <= cx + 60 && my >= cy + 48 && my <= cy + 70) { startGame(); return true; }
        return super.mouseClicked(mx, my, btn);
    }

    @Override public boolean isPauseScreen() { return false; }
}