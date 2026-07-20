package com.wzz.momoi_game_console.client.screens.games.tictactoe;

import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import com.wzz.momoi_game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wzz.momoi_game_console.client.screens.games.LanMultiplayerScreen;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public class TicTacToeScreen extends Screen implements LanMultiplayerScreen {
    boolean showExitConfirm = false;
    private enum State { MENU, PLAYING }
    private State state = State.MENU;
    private TicTacToeGame game;
    private long tickCount = 0;
    private long lastAIMoveTime = 0;
    private int cellSize, gridStartX, gridStartY;

    // ── LAN 联机 ──────────────────────────────────────────────────
    private int lanMode = LAN_NONE;
    private java.util.UUID remotePeer = null;
    /** HOST=X先手，CLIENT=O后手；true=到我落子 */
    private boolean isMyTurn = true;

    public TicTacToeScreen(TicTacToeGame.GameMode mode) {
        super(Component.literal("井字棋"));
        this.game = new TicTacToeGame(mode);
    }

    /** LAN 联机构造：HOST=X先手，CLIENT=O后手 */
    public TicTacToeScreen(boolean isHost, java.util.UUID remote) {
        super(Component.literal("井字棋-联机"));
        this.game     = new TicTacToeGame(TicTacToeGame.GameMode.SINGLE_PLAYER);
        this.lanMode  = isHost ? LAN_HOST : LAN_CLIENT;
        this.remotePeer = remote;
        this.isMyTurn = isHost; // HOST(X)先手
        state = State.PLAYING; // 联机跳过菜单
    }

    // ── LanMultiplayerScreen 接口实现 ──────────────────────────────
    @Override public java.util.UUID getLanPeer() { return remotePeer; }
    @Override public String getLanGameId()        { return "tictactoe"; }

    /** 收到对方走法 "row,col" 或 "RESTART" */
    @Override
    public void onRemoteMove(String data) {
        if ("RESTART".equals(data)) {
            game.resetGame();
            state = State.PLAYING;
            isMyTurn = false; // CLIENT是O后手，HOST(X)重开后先走
            return;
        }
        try {
            String[] p = data.split(",");
            int row = Integer.parseInt(p[0]), col = Integer.parseInt(p[1]);
            game.makeMove(row, col); // 此时 currentPlayer 是对方，直接落子
            isMyTurn = true;
        } catch (Exception ignored) {}
    }

    @Override public void tick() {
        tickCount++;
        // LAN模式下不使用AI
        if (lanMode != LAN_NONE) return;
        if (state == State.PLAYING && game.getGameMode() == TicTacToeGame.GameMode.SINGLE_PLAYER
                && !game.isPlayerTurn() && !game.isGameOver()
                && lastAIMoveTime > 0 && System.currentTimeMillis() - lastAIMoveTime >= 600) {
            game.makeAIMove();
            lastAIMoveTime = 0;
        }
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state == State.MENU || (state == State.PLAYING && game.isGameOver())) {
                Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
            }
            if (lanMode != LAN_NONE) {
                Minecraft.getInstance().setScreen(new GameSelectorScreen());
            } else {
                showExitConfirm = true;
            }
            return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_R) {
            if (lanMode == LAN_CLIENT) return true; // CLIENT 不能单方面重开
            game.resetGame();
            state = State.PLAYING;
            isMyTurn = (lanMode != LAN_CLIENT); // HOST=true，单机=true
            if (lanMode == LAN_HOST) sendMove("RESTART");
            return true;
        }
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        cellSize = Math.max(20, Math.min((width - 80) / 3, (height - 100) / 3));
        cellSize = Math.min(cellSize, 60);
        gridStartX = (width - 3 * cellSize) / 2;
        gridStartY = (height - 3 * cellSize) / 2;

        GameRenderHelper.fillDarkBackground(g, width, height);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case PLAYING -> renderPlaying(g, mx, my);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x220022);
        GameRenderHelper.drawShadowedCenteredText(g, font, "§d井 §r§f字 §r§d棋", cx, cy - 60, 0xCC66FF, 2);
        g.drawCenteredString(font, "Tic-Tac-Toe", cx, cy - 42, 0x553366);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFFAA44FF, 0xFF552288);
        g.drawCenteredString(font, "点击格子落子", cx, cy - 10, 0xAAAAAA);
        g.drawCenteredString(font, "三子连线即可获胜！", cx, cy + 5, 0xCCCCCC);
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 30, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g, int mx, int my) {
        int gridW = 3 * cellSize;
        // 棋盘背景
        GameRenderHelper.drawGameBorder(g, gridStartX, gridStartY, gridW, gridW, 0xFF554466);
        g.fill(gridStartX, gridStartY, gridStartX + gridW, gridStartY + gridW, 0xFF1A1A28);

        // 网格线
        for (int i = 1; i < 3; i++) {
            g.fill(gridStartX + i * cellSize - 1, gridStartY, gridStartX + i * cellSize + 1, gridStartY + gridW, 0xFF665588);
            g.fill(gridStartX, gridStartY + i * cellSize - 1, gridStartX + gridW, gridStartY + i * cellSize + 1, 0xFF665588);
        }

        // X和O
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) {
                TicTacToeGame.Player cell = game.getCell(r, c);
                int cx = gridStartX + c * cellSize + cellSize / 2;
                int cy = gridStartY + r * cellSize + cellSize / 2;
                int s = cellSize / 3;
                if (cell == TicTacToeGame.Player.X) {
                    // 画X
                    for (int i = -s; i <= s; i++) {
                        g.fill(cx + i - 1, cy + i - 1, cx + i + 1, cy + i + 1, 0xFFFF4444);
                        g.fill(cx - i - 1, cy + i - 1, cx - i + 1, cy + i + 1, 0xFFFF4444);
                    }
                } else if (cell == TicTacToeGame.Player.O) {
                    GameRenderHelper.drawCircleOutline(g, cx, cy, s, 0xFF4488FF);
                    GameRenderHelper.drawCircleOutline(g, cx, cy, s - 1, 0xFF4488FF);
                }
            }

        // 悬停
        if (!game.isGameOver() && game.isPlayerTurn()) {
            int hc = (mx - gridStartX) / cellSize;
            int hr = (my - gridStartY) / cellSize;
            if (hc >= 0 && hc < 3 && hr >= 0 && hr < 3 && game.getCell(hr, hc) == TicTacToeGame.Player.NONE) {
                g.fill(gridStartX + hc * cellSize, gridStartY + hr * cellSize,
                    gridStartX + (hc+1) * cellSize, gridStartY + (hr+1) * cellSize, 0x22FFFFFF);
            }
        }

        // HUD
        GameRenderHelper.drawTopHUD(g, width, height);
        String status;
        if (lanMode == LAN_NONE) {
            status = game.getGameStatus();
        } else {
            if (game.isGameOver()) {
                status = game.getGameStatus();
            } else {
                status = isMyTurn ? "你的回合" : "等待对方...";
            }
        }
        g.drawCenteredString(font, "§d✖ §f" + status, width / 2, 7, 0xFFFFFF);

        // 游戏结束按钮
        if (game.isGameOver()) {
            int cx = width / 2;
            int by = gridStartY + 3 * cellSize + 16;
            GameRenderHelper.drawPrimaryButton(g, font, "重新开始", cx - 60, by, 120, 22, mx, my);
            GameRenderHelper.drawPrimaryButton(g, font, "返回菜单", cx - 60, by + 30, 120, 22, mx, my);
        } else {
            GameRenderHelper.drawBottomBar(g, font, width, height, "ESC 菜单  R 重开");
        }
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; state = State.MENU; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (state == State.MENU) {
            int cx = width/2, cy = height/2;
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+30 && my <= cy+52) { state = State.PLAYING; return true; }
        }
        if (state == State.PLAYING && game.isGameOver()) {
            int cx = width / 2;
            int by = gridStartY + 3 * cellSize + 16;
            if (mx >= cx-60 && mx <= cx+60 && my >= by && my <= by+22) {
                // 重新开始
                if (lanMode != LAN_CLIENT) {
                    game.resetGame(); isMyTurn = (lanMode != LAN_CLIENT);
                    if (lanMode == LAN_HOST) sendMove("RESTART");
                }
                return true;
            }
            if (mx >= cx-60 && mx <= cx+60 && my >= by+30 && my <= by+52) {
                Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
            }
            return true;
        }
        if (state == State.PLAYING && !game.isGameOver()) {
            // 联机时只有轮到自己才能落子
            if (lanMode != LAN_NONE && !isMyTurn) return true;
            int hc = ((int)mx - gridStartX) / cellSize;
            int hr = ((int)my - gridStartY) / cellSize;
            if (hc >= 0 && hc < 3 && hr >= 0 && hr < 3) {
                if (game.makeMove(hr, hc)) {
                    if (lanMode != LAN_NONE) {
                        // 发给对方，然后等待
                        isMyTurn = false;
                        sendMove(hr + "," + hc);
                    } else if (game.getGameMode() == TicTacToeGame.GameMode.SINGLE_PLAYER
                               && !game.isPlayerTurn() && !game.isGameOver()) {
                        lastAIMoveTime = System.currentTimeMillis();
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override public boolean isPauseScreen() { return false; }
}
