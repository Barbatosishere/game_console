package com.wzz.game_console.client.screens.games.gogame;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.client.screens.games.LanMultiplayerScreen;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public class GoGameScreen extends Screen implements LanMultiplayerScreen {
    boolean showExitConfirm = false;
    private static final int BOARD_SIZE = 19;
    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};

    private enum State { MENU, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private final GoGame game;
    private long tickCount = 0;
    private int cellSize, boardStartX, boardStartY;

    // ── 胜负结果 ──
    private String resultMsg  = "";
    private boolean myWin     = false;

    // ── LAN 联机 ──
    private static final int LAN_NONE   = LanMultiplayerScreen.LAN_NONE;
    private static final int LAN_HOST   = LanMultiplayerScreen.LAN_HOST;
    private static final int LAN_CLIENT = LanMultiplayerScreen.LAN_CLIENT;
    private int     lanMode    = LAN_NONE;
    private java.util.UUID remotePeer = null;
    /** LAN HOST=黑棋先手，CLIENT=白棋后手 */
    private boolean myTurn = true; // 单机或 HOST 默认先手

    /** 单机 / AI 构造 */
    public GoGameScreen(GoGame game) {
        super(Component.literal("围棋"));
        this.game = game;
        this.game.setAiMode(true);
    }

    /** LAN 联机构造 */
    public GoGameScreen(boolean isHost, java.util.UUID remote) {
        super(Component.literal("围棋"));
        this.game       = new GoGame();
        this.game.setAiMode(false);
        this.lanMode    = isHost ? LAN_HOST : LAN_CLIENT;
        this.remotePeer = remote;
        this.myTurn     = isHost; // HOST（黑）先手
    }

    // ── LanMultiplayerScreen 接口 ──────────────────────────────
    @Override public java.util.UUID getLanPeer() { return remotePeer; }
    @Override public String getLanGameId() { return "go"; }

    @Override
    public void onRemoteMove(String data) {
        if ("RESTART".equals(data)) { resetGame(); return; }
        if ("PASS".equals(data)) {
            game.pass();
            myTurn = true;
            if (game.isGameOver()) finishGame();
            return;
        }
        if (data.startsWith("RESIGN:")) {
            // 对方认输，我赢
            myWin    = true;
            resultMsg = "对方认输，你赢了！";
            state    = State.GAME_OVER;
            return;
        }
        try {
            String[] p = data.split(",");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
            game.placeStone(x, y);
            myTurn = true;
            if (game.isGameOver()) finishGame();
        } catch (Exception ignored) {}
    }

    @Override public void onRemoteState(String data) { /* 围棋走法驱动 */ }
    @Override public void onRemoteGameOver(String data) { /* 由 finishGame 本地处理 */ }

    private void sendLanMove(String moveData) {
        if (lanMode == LAN_NONE) return;
        sendMove(moveData);
    }

    // ── 游戏逻辑 ──────────────────────────────────────────────
    private void resetGame() {
        game.reset();
        myTurn    = (lanMode != LAN_CLIENT);
        resultMsg = "";
        myWin     = false;
        state     = State.PLAYING;
    }

    /**
     * 游戏结束时计算胜负（中国规则：数子法，黑子贴目3.75目）。
     * 修复 Bug：原版 endGame() 不计算胜者，导致局域网双方都显示"你赢了"。
     */
    private void finishGame() {
        // 计算双方领地 + 提子数
        int[] territory = calcTerritory();
        int blackTerritory = territory[0];
        int whiteTerritory = territory[1];

        // 黑棋活子 - 贴目3.75（× 4 放大避免小数）
        double blackScore = blackTerritory + game.getBlackCaptured();
        double whiteScore = whiteTerritory + game.getWhiteCaptured() + 3.75;

        boolean blackWins = blackScore > whiteScore;

        if (lanMode == LAN_NONE) {
            // 单机/AI 模式
            if (game.isAiMode()) {
                // AI 执白，玩家执黑
                myWin = blackWins;
                resultMsg = String.format("%s 胜！黑%.1f 白%.1f",
                        blackWins ? "玩家（黑）" : "AI（白）", blackScore, whiteScore);
            } else {
                myWin = false;
                resultMsg = String.format("%s 胜！黑%.1f 白%.1f",
                        blackWins ? "黑方" : "白方", blackScore, whiteScore);
            }
        } else {
            // LAN 模式：HOST=黑，CLIENT=白
            boolean iAmBlack = (lanMode == LAN_HOST);
            myWin     = iAmBlack == blackWins;
            resultMsg = String.format("%s 胜！黑%.1f 白%.1f",
                    myWin ? "你" : "对方", blackScore, whiteScore);
        }
        state = State.GAME_OVER;
    }

    /**
     * 数子法计算领地（flood-fill 无子区域，判断属于哪方）。
     * @return [黑领地, 白领地]（包含己方活子数）
     */
    private int[] calcTerritory() {
        int size = game.getBoardSize();
        boolean[][] visited = new boolean[size][size];
        int blackT = 0, whiteT = 0;

        // 先统计棋盘上的活子数
        for (int x = 0; x < size; x++)
            for (int y = 0; y < size; y++) {
                GoPlayer s = game.getStone(x, y);
                if (s == GoPlayer.BLACK) blackT++;
                else if (s == GoPlayer.WHITE) whiteT++;
            }

        // 再统计空点领地
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                if (visited[x][y] || game.getStone(x, y) != GoPlayer.NONE) continue;
                // BFS 找连通空区
                java.util.List<int[]> region = new java.util.ArrayList<>();
                java.util.Queue<int[]> queue = new java.util.LinkedList<>();
                queue.add(new int[]{x, y});
                boolean touchBlack = false, touchWhite = false;
                while (!queue.isEmpty()) {
                    int[] pos = queue.poll();
                    int px = pos[0], py = pos[1];
                    if (px < 0 || px >= size || py < 0 || py >= size) continue;
                    if (visited[px][py]) continue;
                    visited[px][py] = true;
                    GoPlayer st = game.getStone(px, py);
                    if (st == GoPlayer.BLACK) { touchBlack = true; continue; }
                    if (st == GoPlayer.WHITE) { touchWhite = true; continue; }
                    region.add(new int[]{px, py});
                    for (int[] d : DIRS)
                        queue.add(new int[]{px+d[0], py+d[1]});
                }
                int pts = region.size();
                if (touchBlack && !touchWhite) blackT += pts;
                else if (touchWhite && !touchBlack) whiteT += pts;
                // 争议地带不计
            }
        }
        return new int[]{blackT, whiteT};
    }

    @Override public void tick() {
        tickCount++;
        // AI 模式：AI 执白，黑棋下完后触发
        if (state == State.PLAYING && lanMode == LAN_NONE && game.isAiMode()
                && !game.isGameOver() && game.getCurrentPlayer() == GoPlayer.WHITE) {
            game.makeAiMove();
            if (game.isGameOver()) finishGame();
        }
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_N) { resetGame(); state = State.PLAYING; return true; }
        if (key == GLFW.GLFW_KEY_P && state == State.PLAYING && !game.isGameOver()) {
            if (lanMode == LAN_NONE) {
                game.pass();
                if (game.isGameOver()) finishGame();
            } else if (myTurn) {
                game.pass();
                myTurn = false;
                sendLanMove("PASS");
                if (game.isGameOver()) finishGame();
            }
            return true;
        }
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        cellSize = Math.max(8, Math.min((width - 120) / BOARD_SIZE, (height - 80) / BOARD_SIZE));
        int boardPixels = BOARD_SIZE * cellSize;
        boardStartX = (width - boardPixels) / 2 - 40;
        boardStartY = (height - boardPixels) / 2;

        GameRenderHelper.fillDarkBackground(g, width, height);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case PLAYING -> renderPlaying(g, mx, my);
            case GAME_OVER -> { renderPlaying(g, mx, my); renderGameOver(g, mx, my); }
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x112200);
        GameRenderHelper.drawShadowedCenteredText(g, font, "§f围 §r§8棋", cx, cy - 60, 0xFFFFFF, 2);
        g.drawCenteredString(font, "Go Game", cx, cy - 42, 0x555555);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFFD2B48C, 0xFF8B7355);
        g.drawCenteredString(font, "鼠标点击落子  N新游戏  P弃权", cx, cy - 10, 0xAAAAAA);
        g.drawCenteredString(font, "围地为王，黑白博弈的艺术", cx, cy + 5, 0xCCCCCC);
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 30, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g, int mx, int my) {
        int bw = BOARD_SIZE * cellSize;
        g.fill(boardStartX - 4, boardStartY - 4, boardStartX + bw + 4, boardStartY + bw + 4, 0xFF3A2A10);
        g.fill(boardStartX, boardStartY, boardStartX + bw, boardStartY + bw, 0xFFD2B48C);

        for (int i = 0; i < BOARD_SIZE; i++) {
            int x = boardStartX + i * cellSize + cellSize/2;
            int y = boardStartY + i * cellSize + cellSize/2;
            g.fill(x, boardStartY + cellSize/2, x+1, boardStartY + bw - cellSize/2, 0xFF000000);
            g.fill(boardStartX + cellSize/2, y, boardStartX + bw - cellSize/2, y+1, 0xFF000000);
        }

        int[] stars = {3, 9, 15};
        for (int sx : stars)
            for (int sy : stars)
                GameRenderHelper.drawCircle(g, boardStartX + sx * cellSize + cellSize/2,
                    boardStartY + sy * cellSize + cellSize/2, 2, 0xFF000000);

        int stoneR = cellSize / 2 - 1;
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++) {
                GoPlayer stone = game.getStone(x, y);
                if (stone != GoPlayer.NONE) {
                    int scx = boardStartX + x * cellSize + cellSize/2;
                    int scy = boardStartY + y * cellSize + cellSize/2;
                    int color = stone == GoPlayer.BLACK ? 0xFF111111 : 0xFFEEEEEE;
                    GameRenderHelper.drawCircle(g, scx, scy, stoneR, color);
                    if (stone == GoPlayer.WHITE) GameRenderHelper.drawCircle(g, scx-stoneR/3, scy-stoneR/3, stoneR/4, 0x44FFFFFF);
                }
            }

        // 悬停预览
        if (state == State.PLAYING && !game.isGameOver()) {
            boolean canPlay = (lanMode == LAN_NONE) || myTurn;
            if (canPlay) {
                int[] pos = getBoardPos(mx, my);
                if (pos != null && game.canPlaceStone(pos[0], pos[1])) {
                    int scx = boardStartX + pos[0] * cellSize + cellSize/2;
                    int scy = boardStartY + pos[1] * cellSize + cellSize/2;
                    int color = game.getCurrentPlayer() == GoPlayer.BLACK ? 0x66111111 : 0x66EEEEEE;
                    GameRenderHelper.drawCircle(g, scx, scy, stoneR, color);
                }
            }
        }

        // 信息面板
        int infoX = boardStartX + bw + 15;
        int infoY = boardStartY;
        GameRenderHelper.drawPanel(g, infoX, infoY, 100, 140, GameRenderHelper.BG_PANEL, 0xFF334455);

        String curColor = game.getCurrentPlayer() == GoPlayer.BLACK ? "§0黑棋" : "§f白棋";
        g.drawString(font, "§f当前: " + curColor, infoX + 5, infoY + 8, 0xFFFFFF);

        String turnHint;
        if (lanMode == LAN_NONE) {
            turnHint = game.isAiMode() ? (game.getCurrentPlayer()==GoPlayer.BLACK ? "§a你的回合" : "§7AI思考中") : "§a进行中";
        } else {
            turnHint = myTurn ? "§a你的回合" : "§7等待对方";
        }
        g.drawString(font, turnHint, infoX + 5, infoY + 24, 0xFFFFFF);
        g.drawString(font, "§7黑捕: " + game.getBlackCaptured(), infoX + 5, infoY + 44, 0xCCCCCC);
        g.drawString(font, "§7白捕: " + game.getWhiteCaptured(), infoX + 5, infoY + 58, 0xCCCCCC);

        String modeStr = lanMode != LAN_NONE ? (lanMode==LAN_HOST ? "§b局域网(黑)" : "§b局域网(白)")
                : (game.isAiMode() ? "§aAI模式" : "§e双人模式");
        g.drawString(font, modeStr, infoX + 5, infoY + 78, 0xCCCCCC);

        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "§f⚫⚪ 围棋", 8, 7, 0xFFFFFF);
        GameRenderHelper.drawBottomBar(g, font, width, height, "ESC 菜单  N 新游戏  P 弃权");
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        g.flush(); // 防止先绘制的棋盘/HUD文字盖住遮罩背景（批量渲染text批次后置）
        g.fill(0, 0, width, height, 0xAA000000);

        int pw = 300, ph = 120;
        int px = cx - pw/2, py = cy - ph/2;
        g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, myWin ? 0xFF44FF44 : 0xFFFF4444);
        g.fill(px, py, px + pw, py + ph, 0xFF0A1A2A);

        String headline = myWin ? "§a🎉 你赢了！" : "§c游戏结束";
        g.drawCenteredString(font, headline, cx, py + 12, myWin ? 0x44FF44 : 0xFF4444);
        g.drawCenteredString(font, resultMsg, cx, py + 30, 0xFFFFFF);
        g.drawCenteredString(font, "§7N - 再来一局  |  ESC - 返回", cx, py + 50, 0xAAAAAA);
    }

    private int[] getBoardPos(int mx, int my) {
        int bx = Mth.floor((mx - boardStartX + cellSize/2) / (float)cellSize);
        int by = Mth.floor((my - boardStartY + cellSize/2) / (float)cellSize);
        if (bx >= 0 && bx < BOARD_SIZE && by >= 0 && by < BOARD_SIZE) return new int[]{bx, by};
        return null;
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; state = State.MENU; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (state == State.MENU) {
            int cx = width/2, cy = height/2;
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+30 && my <= cy+52) {
                resetGame(); state = State.PLAYING; return true;
            }
        }
        if (state == State.PLAYING && btn == 0 && !game.isGameOver()) {
            boolean canPlay = (lanMode == LAN_NONE) || myTurn;
            if (!canPlay) return true;

            int[] pos = getBoardPos((int)mx, (int)my);
            if (pos != null && game.canPlaceStone(pos[0], pos[1])) {
                if (game.placeStone(pos[0], pos[1])) {
                    if (lanMode != LAN_NONE) {
                        sendLanMove(pos[0] + "," + pos[1]);
                        myTurn = false;
                    }
                    if (game.isGameOver()) finishGame();
                    else if (lanMode == LAN_NONE && game.isAiMode()) {
                        // AI 在 tick 里触发
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override public boolean isPauseScreen() { return false; }
}

