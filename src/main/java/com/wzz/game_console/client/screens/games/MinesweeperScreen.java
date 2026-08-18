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
public class MinesweeperScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int[] NUMBER_COLORS = {0, 0xFF0000FF, 0xFF008800, 0xFFFF0000, 0xFF000088, 0xFF880000, 0xFF008888, 0xFF000000, 0xFF888888};

    private enum State { MENU, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private int gridSize = 9, mineCount = 10;
    private Cell[][] grid;
    private boolean won;
    private long tickCount;
    private int cellSize, offsetX, offsetY;
    private int flagCount;
    private final List<GameRenderHelper.Particle> particles = new ArrayList<>();
    private final Random random = new Random();

    public MinesweeperScreen() { super(Component.literal("扫雷")); }

    private void startGame() {
        grid = new Cell[gridSize][gridSize];
        won = false; flagCount = 0;
        for (int y = 0; y < gridSize; y++)
            for (int x = 0; x < gridSize; x++) grid[y][x] = new Cell();
        int placed = 0;
        while (placed < mineCount) {
            int x = random.nextInt(gridSize), y = random.nextInt(gridSize);
            if (!grid[y][x].mine) { grid[y][x].mine = true; placed++; }
        }
        for (int y = 0; y < gridSize; y++)
            for (int x = 0; x < gridSize; x++) grid[y][x].adj = countAdj(x, y);
        state = State.PLAYING; particles.clear();
    }

    private int countAdj(int x, int y) {
        int c = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int nx = x + dx, ny = y + dy;
            if (nx >= 0 && nx < gridSize && ny >= 0 && ny < gridSize && grid[ny][nx].mine) c++;
        }
        return c;
    }

    private void reveal(int x, int y) {
        if (x < 0 || x >= gridSize || y < 0 || y >= gridSize) return;
        Cell c = grid[y][x];
        if (c.revealed || c.flagged) return;
        c.revealed = true;
        if (c.mine) { state = State.GAME_OVER; won = false; revealAll(); return; }
        if (c.adj == 0) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) if (dx != 0 || dy != 0) reveal(x + dx, y + dy);
        checkWin();
    }

    private void revealAll() {
        for (int y = 0; y < gridSize; y++)
            for (int x = 0; x < gridSize; x++) grid[y][x].revealed = true;
    }

    private void checkWin() {
        for (int y = 0; y < gridSize; y++)
            for (int x = 0; x < gridSize; x++)
                if (!grid[y][x].mine && !grid[y][x].revealed) return;
        state = State.GAME_OVER; won = true;
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1F, 1F);
    }

    @Override public void tick() { tickCount++; }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_R && state == State.GAME_OVER) { startGame(); return true; }
        return true;
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = width / 2, cy = height / 2;
        if (state == State.MENU) {
            // 难度按钮
            if (mx >= cx - 70 && mx <= cx + 70) {
                if (my >= cy + 10 && my <= cy + 30) { gridSize = 9; mineCount = 10; startGame(); return true; }
                if (my >= cy + 35 && my <= cy + 55) { gridSize = 12; mineCount = 25; startGame(); return true; }
                if (my >= cy + 60 && my <= cy + 80) { gridSize = 16; mineCount = 50; startGame(); return true; }
            }
            return true;
        }
        if (state == State.GAME_OVER) {
            if (mx >= cx - 60 && mx <= cx + 60 && my >= cy + 22 && my <= cy + 40) { startGame(); return true; }
            if (mx >= cx - 60 && mx <= cx + 60 && my >= cy + 44 && my <= cy + 62) { state = State.MENU; return true; }
            return true;
        }
        if (state != State.PLAYING) return super.mouseClicked(mx, my, btn);

        int gx = (int)((mx - offsetX) / cellSize);
        int gy = (int)((my - offsetY) / cellSize);
        if (gx < 0 || gx >= gridSize || gy < 0 || gy >= gridSize) return super.mouseClicked(mx, my, btn);

        if (btn == 0) {
            reveal(gx, gy);
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.STONE_BUTTON_CLICK_ON, 0.5F, 1F);
        } else if (btn == 1 && !grid[gy][gx].revealed) {
            grid[gy][gx].flagged = !grid[gy][gx].flagged;
            flagCount += grid[gy][gx].flagged ? 1 : -1;
        }
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        cellSize = GameRenderHelper.calcCellSize(width, height, gridSize, gridSize, 50);
        cellSize = Math.min(cellSize, 28);
        offsetX = (width - gridSize * cellSize) / 2;
        offsetY = (height - gridSize * cellSize) / 2;

        GameRenderHelper.fillDarkBackground(g, width, height);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case PLAYING -> renderPlaying(g, mx, my);
            case GAME_OVER -> renderGameOver(g, mx, my);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x222200);
        GameRenderHelper.drawShadowedCenteredText(g, font, "扫雷", cx, cy - 60, 0xFFAA00, 2);
        g.drawCenteredString(font, "Minesweeper", cx, cy - 42, 0x665533);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFFAA6600, 0xFF553300);
        g.drawCenteredString(font, "左键揭开  右键标旗  找出所有地雷", cx, cy - 10, 0xCCCCCC);
        GameRenderHelper.drawPrimaryButton(g, font, "简单 9×9", cx - 70, cy + 10, 140, 20, mx, my);
        GameRenderHelper.drawButton(g, font, "中等 12×12", cx - 70, cy + 35, 140, 20, mx, my, 0xFF3A3A14, 0xFF5A5A22, 0xFFAAAA44);
        GameRenderHelper.drawButton(g, font, "困难 16×16", cx - 70, cy + 60, 140, 20, mx, my, 0xFF3A1414, 0xFF5A2222, 0xFFAA4444);
    }

    private void renderPlaying(GuiGraphics g, int mx, int my) {
        GameRenderHelper.drawGameBorder(g, offsetX, offsetY, gridSize * cellSize, gridSize * cellSize, 0xFF445544);

        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                int sx = offsetX + x * cellSize, sy = offsetY + y * cellSize;
                Cell c = grid[y][x];
                boolean hover = mx >= sx && mx < sx + cellSize && my >= sy && my < sy + cellSize;

                if (c.revealed) {
                    if (c.mine) {
                        g.fill(sx, sy, sx + cellSize, sy + cellSize, 0xFFCC2222);
                        g.drawCenteredString(font, "💣", sx + cellSize / 2, sy + (cellSize - 8) / 2, 0xFFFFFF);
                    } else {
                        g.fill(sx, sy, sx + cellSize, sy + cellSize, 0xFF1A1A22);
                        if (c.adj > 0 && c.adj <= 8)
                            g.drawCenteredString(font, String.valueOf(c.adj), sx + cellSize / 2, sy + (cellSize - 8) / 2, NUMBER_COLORS[c.adj]);
                    }
                } else {
                    int bg = hover ? 0xFF444466 : 0xFF333344;
                    g.fill(sx, sy, sx + cellSize, sy + cellSize, bg);
                    g.fill(sx, sy, sx + cellSize, sy + 1, GameRenderHelper.brighten(bg, 1.3f));
                    g.fill(sx, sy, sx + 1, sy + cellSize, GameRenderHelper.brighten(bg, 1.15f));
                    g.fill(sx + cellSize - 1, sy, sx + cellSize, sy + cellSize, GameRenderHelper.darken(bg, 0.7f));
                    g.fill(sx, sy + cellSize - 1, sx + cellSize, sy + cellSize, GameRenderHelper.darken(bg, 0.6f));
                    if (c.flagged) g.drawCenteredString(font, "🚩", sx + cellSize / 2, sy + (cellSize - 8) / 2, 0xFF4444);
                }
                // 网格线
                g.fill(sx + cellSize, sy, sx + cellSize + 1, sy + cellSize, 0x22FFFFFF);
                g.fill(sx, sy + cellSize, sx + cellSize, sy + cellSize + 1, 0x22FFFFFF);
            }
        }

        GameRenderHelper.tickAndRenderParticles(g, particles);
        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "地雷: " + mineCount, 8, 7, 0xFF4444);
        g.drawCenteredString(font, "标旗: " + flagCount + " / " + mineCount, width / 2, 7, 0xFFFF44);
        GameRenderHelper.drawBottomBar(g, font, width, height, "ESC 菜单  左键揭开  右键标旗");
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        // 只画棋盘底色，不调用 renderPlaying（避免结算时渲染出地雷）
        int bw = gridSize * cellSize, bh = gridSize * cellSize;
        g.fill(offsetX - 2, offsetY - 2, offsetX + bw + 2, offsetY + bh + 2, 0xFF223322);
        GameRenderHelper.drawGameOverOverlay(g, width, height);
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.drawGameOverPanel(g, font, cx, cy, won,
                won ? "🎉 恭喜通关！" : "💥 踩到地雷了！",
                won ? "你成功找出了所有地雷" : "下次小心点~");
        GameRenderHelper.drawPrimaryButton(g, font, "R - 重来", cx - 60, cy + 22, 120, 18, mx, my);
        GameRenderHelper.drawSecondaryButton(g, font, "ESC - 返回", cx - 60, cy + 44, 120, 18, mx, my);
    }

    @Override public boolean isPauseScreen() { return false; }

    static class Cell { boolean mine, revealed, flagged; int adj; }
}